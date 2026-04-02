package com.opszen.deploy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Analyzes a Maven/Gradle project structure to determine optimal Docker configuration.
 */
@Component
public class ProjectAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ProjectAnalyzer.class);

    public ProjectInfo analyze(String projectPath) throws IOException {
        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) {
            throw new IOException("Project path does not exist: " + projectPath);
        }

        boolean hasMaven = Files.exists(root.resolve("pom.xml"));
        boolean hasGradle = Files.exists(root.resolve("build.gradle")) ||
                            Files.exists(root.resolve("build.gradle.kts"));
        boolean hasMvnWrapper = Files.exists(root.resolve("mvnw")) ||
                                Files.exists(root.resolve("mvnw.cmd"));

        String projectName = root.getFileName().toString();
        String javaVersion = "21"; // default
        int serverPort = 8080;

        // Try to extract from pom.xml
        if (hasMaven) {
            try {
                String pomContent = Files.readString(root.resolve("pom.xml"));
                javaVersion = extractPomProperty(pomContent, "java.version", "21");
                String artifactId = extractPomElement(pomContent, "artifactId");
                if (artifactId != null) {
                    projectName = artifactId;
                }
            } catch (Exception e) {
                log.warn("Failed to parse pom.xml: {}", e.getMessage());
            }
        }

        // Try to extract server port from application.properties
        Path appProps = root.resolve("src/main/resources/application.properties");
        if (Files.exists(appProps)) {
            try {
                String props = Files.readString(appProps);
                for (String line : props.split("\n")) {
                    if (line.startsWith("server.port=")) {
                        serverPort = Integer.parseInt(line.split("=")[1].trim());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse application.properties: {}", e.getMessage());
            }
        }

        // Check for existing Dockerfile
        boolean hasDockerfile = Files.exists(root.resolve("Dockerfile"));
        boolean hasDockerCompose = Files.exists(root.resolve("docker-compose.yml")) ||
                                   Files.exists(root.resolve("docker-compose.yaml"));

        BuildTool buildTool = hasMaven ? BuildTool.MAVEN : hasGradle ? BuildTool.GRADLE : BuildTool.UNKNOWN;

        return new ProjectInfo(
                projectName, javaVersion, serverPort, buildTool,
                hasMvnWrapper, hasDockerfile, hasDockerCompose
        );
    }

    private String extractPomProperty(String pom, String property, String defaultValue) {
        String tag = "<" + property + ">";
        int start = pom.indexOf(tag);
        if (start < 0) return defaultValue;
        start += tag.length();
        int end = pom.indexOf("</" + property + ">", start);
        if (end < 0) return defaultValue;
        return pom.substring(start, end).trim();
    }

    private String extractPomElement(String pom, String element) {
        // Only look in the top-level project, not in parent/dependencies
        String tag = "<" + element + ">";
        int start = pom.indexOf(tag);
        if (start < 0) return null;
        start += tag.length();
        int end = pom.indexOf("</" + element + ">", start);
        if (end < 0) return null;
        return pom.substring(start, end).trim();
    }

    public enum BuildTool { MAVEN, GRADLE, UNKNOWN }

    public record ProjectInfo(
            String projectName,
            String javaVersion,
            int serverPort,
            BuildTool buildTool,
            boolean hasMvnWrapper,
            boolean hasDockerfile,
            boolean hasDockerCompose
    ) {}
}
