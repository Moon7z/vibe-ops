package com.vibeops.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProjectAnalyzer Tests")
class ProjectAnalyzerTest {

    private final ProjectAnalyzer analyzer = new ProjectAnalyzer();

    @Test
    @DisplayName("should detect Maven project")
    void should_detectMavenProject(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project><artifactId>my-app</artifactId>" +
                "<properties><java.version>21</java.version></properties></project>");

        var info = analyzer.analyze(tempDir.toString());

        assertEquals(ProjectAnalyzer.BuildTool.MAVEN, info.buildTool());
        assertEquals("21", info.javaVersion());
        assertEquals("my-app", info.projectName());
    }

    @Test
    @DisplayName("should detect server port from application.properties")
    void should_detectServerPort(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Path resources = tempDir.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application.properties"), "server.port=3100\n");

        var info = analyzer.analyze(tempDir.toString());
        assertEquals(3100, info.serverPort());
    }

    @Test
    @DisplayName("should throw on invalid path")
    void should_throw_on_invalidPath() {
        assertThrows(IOException.class, () -> analyzer.analyze("/nonexistent/path"));
    }

    @Test
    @DisplayName("should detect existing Docker files")
    void should_detectExistingDockerFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM java:21");
        Files.writeString(tempDir.resolve("docker-compose.yml"), "services:");

        var info = analyzer.analyze(tempDir.toString());
        assertTrue(info.hasDockerfile());
        assertTrue(info.hasDockerCompose());
    }
}
