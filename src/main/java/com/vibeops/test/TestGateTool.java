package com.vibeops.test;

import com.vibeops.mcp.McpProtocol;
import com.vibeops.mcp.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP Tool: test-gate
 * Comprehensive test gate enforcer that runs analysis + tests + gate decision.
 * This is the "single command" entry point that combines generate-tests + run-tests
 * into one atomic quality gate operation.
 */
@Component
public class TestGateTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(TestGateTool.class);

    private final CodeDiffReader diffReader;
    private final TestPromptEngine promptEngine;
    private final MavenTestExecutor executor;

    public TestGateTool(CodeDiffReader diffReader, TestPromptEngine promptEngine, MavenTestExecutor executor) {
        this.diffReader = diffReader;
        this.promptEngine = promptEngine;
        this.executor = executor;
    }

    @Override
    public String name() {
        return "test-gate";
    }

    @Override
    public String description() {
        return "Comprehensive test gate: analyzes changed files for test coverage, runs all tests, " +
               "and returns a merge/deploy gate decision. Combines file analysis + test execution + " +
               "gate enforcement into a single operation. Use this before any merge or deployment.";
    }

    @Override
    public McpProtocol.InputSchema inputSchema() {
        return new McpProtocol.InputSchema(
                "object",
                Map.of(
                        "projectPath", new McpProtocol.PropertyDef("string",
                                "Absolute path to the Maven project root", null),
                        "strict", new McpProtocol.PropertyDef("string",
                                "If 'true', also checks that all changed source files have corresponding test files (default: false)", null)
                ),
                List.of("projectPath")
        );
    }

    @Override
    public McpProtocol.ToolResult execute(Map<String, Object> arguments) {
        String projectPath = (String) arguments.get("projectPath");
        boolean strict = "true".equalsIgnoreCase((String) arguments.getOrDefault("strict", "false"));

        if (projectPath == null || projectPath.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'projectPath' is required.");
        }

        StringBuilder report = new StringBuilder();
        report.append("# Test Gate Report\n\n");

        boolean gatePass = true;
        List<String> issues = new java.util.ArrayList<>();

        // Phase 1: Analyze changed files
        report.append("## 1. Changed File Analysis\n\n");
        try {
            Map<String, String> changedFiles = diffReader.readChangedFiles(projectPath);
            report.append("**Changed source files**: %d\n\n".formatted(changedFiles.size()));

            if (strict && !changedFiles.isEmpty()) {
                List<String> untested = findUntestedFiles(projectPath, changedFiles);
                if (!untested.isEmpty()) {
                    gatePass = false;
                    issues.add("Missing test files for %d source files".formatted(untested.size()));
                    report.append("### Missing Test Files\n\n");
                    for (String file : untested) {
                        report.append("- `%s` — no corresponding test file found\n".formatted(file));
                    }
                    report.append("\n");
                } else {
                    report.append("All changed source files have corresponding test files.\n\n");
                }
            }

            // Report class analysis
            if (!changedFiles.isEmpty()) {
                report.append("| File | Class | Has Tests |\n");
                report.append("|------|-------|----------|\n");
                for (var entry : changedFiles.entrySet()) {
                    String className = promptEngine.extractClassName(entry.getValue());
                    boolean hasTest = hasTestFile(projectPath, entry.getKey(), className);
                    report.append("| `%s` | %s | %s |\n".formatted(
                            entry.getKey(), className, hasTest ? "Yes" : "No"));
                }
                report.append("\n");
            }
        } catch (Exception e) {
            report.append("File analysis failed: %s\n\n".formatted(e.getMessage()));
            issues.add("File analysis error: " + e.getMessage());
        }

        // Phase 2: Execute tests
        report.append("## 2. Test Execution\n\n");
        MavenTestExecutor.TestExecutionResult testResult = executor.runTests(projectPath);

        report.append("| Metric | Value |\n");
        report.append("|--------|-------|\n");
        report.append("| Tests Run | %d |\n".formatted(testResult.testsRun()));
        report.append("| Passed | %d |\n".formatted(
                testResult.testsRun() - testResult.failures() - testResult.errors()));
        report.append("| Failures | %d |\n".formatted(testResult.failures()));
        report.append("| Errors | %d |\n".formatted(testResult.errors()));
        report.append("| Skipped | %d |\n\n".formatted(testResult.skipped()));

        if (!testResult.success()) {
            gatePass = false;
            issues.add("Test execution failed (%d failures, %d errors)".formatted(
                    testResult.failures(), testResult.errors()));
        }

        if (testResult.testsRun() == 0) {
            issues.add("No tests were executed");
            if (strict) {
                gatePass = false;
            }
        }

        // Phase 3: Gate decision
        report.append("## 3. Gate Decision\n\n");
        if (gatePass && issues.isEmpty()) {
            report.append("### PASS — Merge/Deploy Allowed\n\n");
            report.append("All tests pass. No blocking issues found.\n\n");
        } else if (!gatePass) {
            report.append("### BLOCKED — Merge/Deploy Denied\n\n");
            report.append("The following issues must be resolved:\n\n");
            for (String issue : issues) {
                report.append("- %s\n".formatted(issue));
            }
            report.append("\n");
        } else {
            report.append("### PASS WITH WARNINGS\n\n");
            for (String issue : issues) {
                report.append("- WARNING: %s\n".formatted(issue));
            }
            report.append("\n");
        }

        // Failure details
        if (!testResult.success() && testResult.summary() != null) {
            report.append("## Test Output\n\n");
            report.append("```\n").append(truncate(testResult.summary(), 2000)).append("```\n\n");
        }

        report.append("---\n*Generated by Vibe-Ops Test Gate v0.1.0*\n");
        return McpProtocol.ToolResult.success(report.toString());
    }

    private List<String> findUntestedFiles(String projectPath, Map<String, String> changedFiles) {
        List<String> untested = new java.util.ArrayList<>();
        for (var entry : changedFiles.entrySet()) {
            String className = promptEngine.extractClassName(entry.getValue());
            if (!hasTestFile(projectPath, entry.getKey(), className)) {
                untested.add(entry.getKey());
            }
        }
        return untested;
    }

    private boolean hasTestFile(String projectPath, String sourcePath, String className) {
        String testPath = sourcePath
                .replace("src/main/java", "src/test/java")
                .replace(className + ".java", className + "Test.java");
        return java.nio.file.Files.exists(java.nio.file.Path.of(projectPath).resolve(testPath));
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "\n... (truncated)";
    }
}
