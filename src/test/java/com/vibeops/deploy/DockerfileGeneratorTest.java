package com.vibeops.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DockerfileGenerator Tests")
class DockerfileGeneratorTest {

    private final DockerfileGenerator generator = new DockerfileGenerator();

    @Test
    @DisplayName("should generate multi-stage Maven Dockerfile")
    void should_generateMavenDockerfile() {
        var info = new ProjectAnalyzer.ProjectInfo(
                "my-app", "21", 8080,
                ProjectAnalyzer.BuildTool.MAVEN, false, false, false);

        String result = generator.generate(info);

        assertTrue(result.contains("eclipse-temurin:21-jdk"));
        assertTrue(result.contains("eclipse-temurin:21-jre"));
        assertTrue(result.contains("mvn package"));
        assertTrue(result.contains("groupadd -r appuser"));
        assertTrue(result.contains("HEALTHCHECK"));
        assertTrue(result.contains("UseZGC"));
        assertTrue(result.contains("EXPOSE 8080"));
    }

    @Test
    @DisplayName("should use mvnw when wrapper is available")
    void should_useMvnWrapper() {
        var info = new ProjectAnalyzer.ProjectInfo(
                "my-app", "21", 8080,
                ProjectAnalyzer.BuildTool.MAVEN, true, false, false);

        String result = generator.generate(info);
        assertTrue(result.contains("./mvnw"));
        assertTrue(result.contains("COPY mvnw"));
    }

    @Test
    @DisplayName("should generate Gradle Dockerfile")
    void should_generateGradleDockerfile() {
        var info = new ProjectAnalyzer.ProjectInfo(
                "my-app", "21", 3000,
                ProjectAnalyzer.BuildTool.GRADLE, false, false, false);

        String result = generator.generate(info);
        assertTrue(result.contains("gradlew"));
        assertTrue(result.contains("bootJar"));
        assertTrue(result.contains("EXPOSE 3000"));
    }

    @Test
    @DisplayName("should generate Compose with PostgreSQL")
    void should_generateComposeWithPostgres() {
        var composeGen = new DockerComposeGenerator();
        var info = new ProjectAnalyzer.ProjectInfo(
                "my-app", "21", 8080,
                ProjectAnalyzer.BuildTool.MAVEN, false, false, false);

        String result = composeGen.generate(info,
                new DockerComposeGenerator.ComposeOptions(true, false));

        assertTrue(result.contains("postgres:16-alpine"));
        assertTrue(result.contains("POSTGRES_DB=my-app"));
        assertTrue(result.contains("postgres_data"));
        assertTrue(result.contains("depends_on"));
    }

    @Test
    @DisplayName("should generate GitHub Actions pipeline")
    void should_generateGitHubActions() {
        var actionsGen = new GitHubActionsGenerator();
        var info = new ProjectAnalyzer.ProjectInfo(
                "my-app", "21", 8080,
                ProjectAnalyzer.BuildTool.MAVEN, false, false, false);

        String result = actionsGen.generate(info,
                GitHubActionsGenerator.PipelineOptions.defaults());

        assertTrue(result.contains("CI/CD Pipeline"));
        assertTrue(result.contains("setup-java@v4"));
        assertTrue(result.contains("mvn test"));
        assertTrue(result.contains("docker/build-push-action"));
    }
}
