package com.opszen.test;

import com.opszen.mcp.McpProtocol;
import com.opszen.mcp.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MCP Tool: generate-tests
 * Reads changed source files, generates AI-ready test prompts,
 * and writes test stubs to the project's test directory.
 */
@Component
public class GenerateTestsTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateTestsTool.class);

    private final CodeDiffReader diffReader;
    private final TestPromptEngine promptEngine;
    private final TestQualityAnalyzer qualityAnalyzer;

    public GenerateTestsTool(CodeDiffReader diffReader, TestPromptEngine promptEngine,
                             TestQualityAnalyzer qualityAnalyzer) {
        this.diffReader = diffReader;
        this.promptEngine = promptEngine;
        this.qualityAnalyzer = qualityAnalyzer;
    }

    @Override
    public String name() {
        return "generate-tests";
    }

    @Override
    public String description() {
        return "Analyzes changed or specified Java source files and generates JUnit 5 test code. " +
               "Can either return AI-generated test prompts for Claude to complete, or write " +
               "structural test stubs directly to the project's test directory.";
    }

    @Override
    public McpProtocol.InputSchema inputSchema() {
        return new McpProtocol.InputSchema(
                "object",
                Map.of(
                        "projectPath", new McpProtocol.PropertyDef("string",
                                "Absolute path to the Maven project root", null),
                        "filePath", new McpProtocol.PropertyDef("string",
                                "Optional: specific file to generate tests for (otherwise scans changed files)", null),
                        "mode", new McpProtocol.PropertyDef("string",
                                "Mode: 'prompt' returns AI prompts for test generation, 'stub' writes test stubs to disk (default: prompt)", null),
                        "context", new McpProtocol.PropertyDef("string",
                                "Optional additional context for the AI prompt (e.g., business rules)", null),
                        "auto_validate", new McpProtocol.PropertyDef("string",
                                "Auto-validate generated tests quality (default: true)", null)
                ),
                List.of("projectPath")
        );
    }

    @Override
    public McpProtocol.ToolResult execute(Map<String, Object> arguments) {
        String projectPath = (String) arguments.get("projectPath");
        String filePath = (String) arguments.getOrDefault("filePath", null);
        String mode = (String) arguments.getOrDefault("mode", "prompt");
        String context = (String) arguments.getOrDefault("context", "");
        boolean autoValidate = !"false".equalsIgnoreCase(
                (String) arguments.getOrDefault("auto_validate", "true"));

        if (projectPath == null || projectPath.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'projectPath' is required.");
        }

        try {
            Map<String, String> filesToTest;

            if (filePath != null && !filePath.isBlank()) {
                // Single file mode
                String content = diffReader.readFile(filePath);
                String relPath = Path.of(projectPath).relativize(Path.of(filePath)).toString();
                filesToTest = Map.of(relPath.replace('\\', '/'), content);
            } else {
                // Scan changed files
                filesToTest = diffReader.readChangedFiles(projectPath);
            }

            if (filesToTest.isEmpty()) {
                return McpProtocol.ToolResult.success(
                        "# No Source Files Found\n\nNo Java source files detected for test generation.");
            }

            return switch (mode.toLowerCase()) {
                case "stub" -> generateStubs(projectPath, filesToTest, autoValidate);
                case "prompt" -> generatePrompts(filesToTest, context, autoValidate);
                default -> McpProtocol.ToolResult.error("Invalid mode: " + mode + ". Use 'prompt' or 'stub'.");
            };

        } catch (IOException e) {
            log.error("Test generation failed", e);
            return McpProtocol.ToolResult.error("Test generation failed: " + e.getMessage());
        }
    }

    private McpProtocol.ToolResult generatePrompts(Map<String, String> files, String context, boolean autoValidate) {
        StringBuilder report = new StringBuilder();
        report.append("# Test Generation Prompts\n\n");
        report.append("Generated prompts for **%d** source files.\n\n".formatted(files.size()));

        int index = 1;
        for (var entry : files.entrySet()) {
            String sourceCode = entry.getValue();
            String className = promptEngine.extractClassName(sourceCode);
            String pkg = promptEngine.extractPackage(sourceCode);

            report.append("---\n\n");
            report.append("## %d. %s (`%s`)\n\n".formatted(index++, className, entry.getKey()));
            report.append("**Package**: `%s`\n".formatted(pkg));
            report.append("**Test Class**: `%sTest`\n\n".formatted(className));

            String prompt = promptEngine.buildTestGenerationPrompt(sourceCode, className, context);
            report.append("### AI Prompt\n\n");
            report.append("```\n").append(prompt).append("\n```\n\n");

            if (sourceCode.contains("@RestController") || sourceCode.contains("@Controller")) {
                String integrationPrompt = promptEngine.buildIntegrationTestPrompt(sourceCode, className);
                report.append("### Integration Test Prompt\n\n");
                report.append("```\n").append(integrationPrompt).append("\n```\n\n");
            }
        }

        report.append("---\n*Use these prompts with Claude or another AI model to generate complete test classes.*\n");
        return McpProtocol.ToolResult.success(report.toString());
    }

    private McpProtocol.ToolResult generateStubs(String projectPath, Map<String, String> files, boolean autoValidate) {
        StringBuilder report = new StringBuilder();
        report.append("# Test Stubs Generated\n\n");

        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<TestQualityAnalyzer.QualityReport> qualityReports = new ArrayList<>();

        for (var entry : files.entrySet()) {
            String sourceCode = entry.getValue();
            String className = promptEngine.extractClassName(sourceCode);
            String pkg = promptEngine.extractPackage(sourceCode);

            String testPath = entry.getKey()
                    .replace("src/main/java", "src/test/java")
                    .replace(className + ".java", className + "Test.java");
            Path fullTestPath = Path.of(projectPath).resolve(testPath);

            if (Files.exists(fullTestPath)) {
                skipped.add(testPath);
                // Analyze existing test quality if auto_validate
                if (autoValidate) {
                    try {
                        String existingTest = Files.readString(fullTestPath);
                        qualityReports.add(qualityAnalyzer.analyze(existingTest, sourceCode));
                    } catch (IOException ignored) {}
                }
                continue;
            }

            String testStub = generateTestStub(pkg, className, sourceCode);
            try {
                Files.createDirectories(fullTestPath.getParent());
                Files.writeString(fullTestPath, testStub);
                created.add(testPath);
                if (autoValidate) {
                    qualityReports.add(qualityAnalyzer.analyze(testStub, sourceCode));
                }
            } catch (IOException e) {
                log.error("Failed to write test stub: {}", fullTestPath, e);
                report.append("- FAILED: `%s` — %s\n".formatted(testPath, e.getMessage()));
            }
        }

        if (!created.isEmpty()) {
            report.append("## Created (%d files)\n\n".formatted(created.size()));
            for (String path : created) {
                report.append("- `%s`\n".formatted(path));
            }
        }

        if (!skipped.isEmpty()) {
            report.append("\n## Skipped (already exist: %d files)\n\n".formatted(skipped.size()));
            for (String path : skipped) {
                report.append("- `%s`\n".formatted(path));
            }
        }

        // Quality report
        if (autoValidate && !qualityReports.isEmpty()) {
            report.append("\n## Quality Report\n\n");
            int totalScore = 0;
            int totalAssertions = 0;
            Set<String> allEdgeCases = new LinkedHashSet<>();
            Set<String> allExPaths = new LinkedHashSet<>();
            List<String> allSuggestions = new ArrayList<>();

            for (var qr : qualityReports) {
                totalScore += qr.effectivenessScore();
                totalAssertions += qr.assertionCount();
                allEdgeCases.addAll(qr.edgeCasesCovered());
                allExPaths.addAll(qr.exceptionPathsTested());
                allSuggestions.addAll(qr.suggestions());
            }

            int avgScore = totalScore / qualityReports.size();
            String level = avgScore >= 80 ? "good" : avgScore >= 60 ? "acceptable" : "needs_improvement";

            report.append("| Metric | Value |\n");
            report.append("|--------|-------|\n");
            report.append("| assertion_count | %d |\n".formatted(totalAssertions));
            report.append("| edge_cases_covered | %s |\n".formatted(allEdgeCases));
            report.append("| exception_paths_tested | %s |\n".formatted(allExPaths));
            report.append("| effectiveness_score | %d |\n".formatted(avgScore));
            report.append("| quality_level | %s |\n\n".formatted(level));

            if (!allSuggestions.isEmpty()) {
                report.append("### Suggestions\n\n");
                Set<String> unique = new LinkedHashSet<>(allSuggestions);
                for (String s : unique) {
                    report.append("- %s\n".formatted(s));
                }
            }
        }

        report.append("\n---\n*Stubs contain TODO markers. Use AI to fill in the test implementations.*\n");
        return McpProtocol.ToolResult.success(report.toString());
    }

    private String generateTestStub(String pkg, String className, String sourceCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import org.junit.jupiter.api.Test;\n");
        sb.append("import org.junit.jupiter.api.DisplayName;\n");

        if (sourceCode.contains("@RestController") || sourceCode.contains("@Controller")) {
            sb.append("import org.springframework.boot.test.context.SpringBootTest;\n");
            sb.append("import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;\n");
            sb.append("import org.springframework.beans.factory.annotation.Autowired;\n");
            sb.append("import org.springframework.test.web.servlet.MockMvc;\n");
        }

        if (sourceCode.contains("@Service") || sourceCode.contains("@Component")) {
            sb.append("import org.mockito.InjectMocks;\n");
            sb.append("import org.mockito.Mock;\n");
            sb.append("import org.mockito.junit.jupiter.MockitoExtension;\n");
            sb.append("import org.junit.jupiter.api.extension.ExtendWith;\n");
        }

        sb.append("import static org.junit.jupiter.api.Assertions.*;\n\n");

        // Add annotations based on source type
        if (sourceCode.contains("@RestController") || sourceCode.contains("@Controller")) {
            sb.append("@SpringBootTest\n");
            sb.append("@AutoConfigureMockMvc\n");
        } else if (sourceCode.contains("@Service") || sourceCode.contains("@Component")) {
            sb.append("@ExtendWith(MockitoExtension.class)\n");
        }

        sb.append("@DisplayName(\"").append(className).append(" Tests\")\n");
        sb.append("class ").append(className).append("Test {\n\n");

        sb.append("    // TODO: Generate test implementations using AI\n\n");

        sb.append("    @Test\n");
        sb.append("    @DisplayName(\"should initialize correctly\")\n");
        sb.append("    void should_initialize_correctly() {\n");
        sb.append("        // TODO: Implement\n");
        sb.append("        fail(\"Test not yet implemented\");\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }
}
