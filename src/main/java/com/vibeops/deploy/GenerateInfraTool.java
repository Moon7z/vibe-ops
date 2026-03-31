package com.vibeops.deploy;

import com.vibeops.mcp.McpProtocol;
import com.vibeops.mcp.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool: generate-infra
 * Unified IaC generator — produces Dockerfile, docker-compose.yml,
 * and GitHub Actions pipeline from project analysis.
 */
@Component
public class GenerateInfraTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateInfraTool.class);

    private final ProjectAnalyzer analyzer;
    private final DockerfileGenerator dockerfileGen;
    private final DockerComposeGenerator composeGen;
    private final GitHubActionsGenerator actionsGen;

    public GenerateInfraTool(ProjectAnalyzer analyzer,
                             DockerfileGenerator dockerfileGen,
                             DockerComposeGenerator composeGen,
                             GitHubActionsGenerator actionsGen) {
        this.analyzer = analyzer;
        this.dockerfileGen = dockerfileGen;
        this.composeGen = composeGen;
        this.actionsGen = actionsGen;
    }

    @Override
    public String name() {
        return "generate-infra";
    }

    @Override
    public String description() {
        return "Analyzes a Java project and generates production-ready infrastructure files: " +
               "Dockerfile (multi-stage build), docker-compose.yml, and GitHub Actions CI/CD pipeline. " +
               "Can either preview the files or write them to disk.";
    }

    @Override
    public McpProtocol.InputSchema inputSchema() {
        return new McpProtocol.InputSchema(
                "object",
                Map.of(
                        "projectPath", new McpProtocol.PropertyDef("string",
                                "Absolute path to the project root", null),
                        "mode", new McpProtocol.PropertyDef("string",
                                "Mode: 'preview' returns file contents, 'write' writes files to disk (default: preview)", null),
                        "postgres", new McpProtocol.PropertyDef("string",
                                "Include PostgreSQL in docker-compose: 'true' or 'false' (default: false)", null),
                        "redis", new McpProtocol.PropertyDef("string",
                                "Include Redis in docker-compose: 'true' or 'false' (default: false)", null),
                        "deploy", new McpProtocol.PropertyDef("string",
                                "Include deploy stage in GitHub Actions: 'true' or 'false' (default: false)", null)
                ),
                List.of("projectPath")
        );
    }

    @Override
    public McpProtocol.ToolResult execute(Map<String, Object> arguments) {
        String projectPath = (String) arguments.get("projectPath");
        String mode = (String) arguments.getOrDefault("mode", "preview");
        boolean postgres = "true".equalsIgnoreCase((String) arguments.getOrDefault("postgres", "false"));
        boolean redis = "true".equalsIgnoreCase((String) arguments.getOrDefault("redis", "false"));
        boolean deploy = "true".equalsIgnoreCase((String) arguments.getOrDefault("deploy", "false"));

        if (projectPath == null || projectPath.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'projectPath' is required.");
        }

        try {
            ProjectAnalyzer.ProjectInfo info = analyzer.analyze(projectPath);

            // Generate all files
            String dockerfile = dockerfileGen.generate(info);
            String compose = composeGen.generate(info,
                    new DockerComposeGenerator.ComposeOptions(postgres, redis));
            String pipeline = actionsGen.generate(info,
                    new GitHubActionsGenerator.PipelineOptions(true, deploy));

            if ("write".equalsIgnoreCase(mode)) {
                return writeFiles(projectPath, info, dockerfile, compose, pipeline);
            } else {
                return previewFiles(info, dockerfile, compose, pipeline);
            }

        } catch (IOException e) {
            log.error("Infrastructure generation failed", e);
            return McpProtocol.ToolResult.error("Failed: " + e.getMessage());
        }
    }

    private McpProtocol.ToolResult previewFiles(ProjectAnalyzer.ProjectInfo info,
                                                 String dockerfile, String compose, String pipeline) {
        StringBuilder report = new StringBuilder();
        report.append("# Infrastructure Generation Preview\n\n");

        report.append("## Project Analysis\n\n");
        report.append("| Property | Value |\n");
        report.append("|----------|-------|\n");
        report.append("| Project | %s |\n".formatted(info.projectName()));
        report.append("| Java Version | %s |\n".formatted(info.javaVersion()));
        report.append("| Build Tool | %s |\n".formatted(info.buildTool()));
        report.append("| Server Port | %d |\n".formatted(info.serverPort()));
        report.append("| Existing Dockerfile | %s |\n".formatted(info.hasDockerfile() ? "Yes" : "No"));
        report.append("| Existing Docker Compose | %s |\n\n".formatted(info.hasDockerCompose() ? "Yes" : "No"));

        report.append("---\n\n");
        report.append("## Dockerfile\n\n");
        report.append("```dockerfile\n").append(dockerfile).append("```\n\n");

        report.append("---\n\n");
        report.append("## docker-compose.yml\n\n");
        report.append("```yaml\n").append(compose).append("```\n\n");

        report.append("---\n\n");
        report.append("## .github/workflows/ci.yml\n\n");
        report.append("```yaml\n").append(pipeline).append("```\n\n");

        report.append("---\n*To write these files to disk, re-run with `mode: \"write\"`.*\n");
        report.append("*Generated by Vibe-Ops Ghost-Deployer v0.1.0*\n");

        return McpProtocol.ToolResult.success(report.toString());
    }

    private McpProtocol.ToolResult writeFiles(String projectPath, ProjectAnalyzer.ProjectInfo info,
                                               String dockerfile, String compose, String pipeline) {
        Path root = Path.of(projectPath);
        StringBuilder report = new StringBuilder();
        report.append("# Infrastructure Files Written\n\n");

        List<FileWrite> writes = List.of(
                new FileWrite("Dockerfile", root.resolve("Dockerfile"), dockerfile),
                new FileWrite("docker-compose.yml", root.resolve("docker-compose.yml"), compose),
                new FileWrite(".github/workflows/ci.yml",
                        root.resolve(".github/workflows/ci.yml"), pipeline)
        );

        for (FileWrite fw : writes) {
            try {
                if (Files.exists(fw.path())) {
                    report.append("- SKIPPED `%s` (already exists)\n".formatted(fw.name()));
                } else {
                    Files.createDirectories(fw.path().getParent());
                    Files.writeString(fw.path(), fw.content());
                    report.append("- CREATED `%s`\n".formatted(fw.name()));
                }
            } catch (IOException e) {
                report.append("- FAILED `%s`: %s\n".formatted(fw.name(), e.getMessage()));
            }
        }

        // Generate .dockerignore if missing
        Path dockerignore = root.resolve(".dockerignore");
        if (!Files.exists(dockerignore)) {
            try {
                Files.writeString(dockerignore, generateDockerignore());
                report.append("- CREATED `.dockerignore`\n");
            } catch (IOException e) {
                report.append("- FAILED `.dockerignore`: %s\n".formatted(e.getMessage()));
            }
        }

        report.append("\n---\n*Generated by Vibe-Ops Ghost-Deployer v0.1.0*\n");
        return McpProtocol.ToolResult.success(report.toString());
    }

    private String generateDockerignore() {
        return """
                target/
                .git/
                .github/
                .idea/
                *.iml
                .vscode/
                .DS_Store
                *.md
                docker-compose*.yml
                .env
                .env.*
                """;
    }

    private record FileWrite(String name, Path path, String content) {}
}
