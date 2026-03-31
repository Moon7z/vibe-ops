package com.vibeops.deploy;

import org.springframework.stereotype.Component;

/**
 * Generates optimized Dockerfiles based on project analysis.
 * Uses multi-stage builds for minimal image size and security.
 */
@Component
public class DockerfileGenerator {

    public String generate(ProjectAnalyzer.ProjectInfo info) {
        return switch (info.buildTool()) {
            case MAVEN -> generateMavenDockerfile(info);
            case GRADLE -> generateGradleDockerfile(info);
            default -> generateGenericDockerfile(info);
        };
    }

    private String generateMavenDockerfile(ProjectAnalyzer.ProjectInfo info) {
        String buildCmd = info.hasMvnWrapper() ? "./mvnw" : "mvn";

        return """
                # ── Stage 1: Build ──
                FROM eclipse-temurin:%s-jdk AS build
                WORKDIR /app

                # Cache dependencies first (layer caching optimization)
                COPY pom.xml .
                %sRUN %s dependency:go-offline -B

                # Copy source and build
                COPY src ./src
                RUN %s package -DskipTests -B && \\
                    mv target/*.jar app.jar

                # ── Stage 2: Runtime ──
                FROM eclipse-temurin:%s-jre
                WORKDIR /app

                # Security: run as non-root
                RUN groupadd -r appuser && useradd -r -g appuser appuser
                USER appuser

                # Copy artifact from build stage
                COPY --from=build /app/app.jar app.jar

                # Health check
                HEALTHCHECK --interval=30s --timeout=3s --retries=3 \\
                    CMD curl -f http://localhost:%d/actuator/health || exit 1

                # JVM tuning for containers
                ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseZGC"

                EXPOSE %d
                ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
                """.formatted(
                info.javaVersion(),
                info.hasMvnWrapper() ? "COPY mvnw .\nCOPY .mvn .mvn\nRUN chmod +x mvnw\n" : "",
                buildCmd,
                buildCmd,
                info.javaVersion(),
                info.serverPort(),
                info.serverPort()
        );
    }

    private String generateGradleDockerfile(ProjectAnalyzer.ProjectInfo info) {
        return """
                # ── Stage 1: Build ──
                FROM eclipse-temurin:%s-jdk AS build
                WORKDIR /app

                COPY build.gradle* settings.gradle* ./
                COPY gradle ./gradle
                COPY gradlew .
                RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

                COPY src ./src
                RUN ./gradlew bootJar --no-daemon && \\
                    mv build/libs/*.jar app.jar

                # ── Stage 2: Runtime ──
                FROM eclipse-temurin:%s-jre
                WORKDIR /app

                RUN groupadd -r appuser && useradd -r -g appuser appuser
                USER appuser

                COPY --from=build /app/app.jar app.jar

                HEALTHCHECK --interval=30s --timeout=3s --retries=3 \\
                    CMD curl -f http://localhost:%d/actuator/health || exit 1

                ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseZGC"

                EXPOSE %d
                ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
                """.formatted(
                info.javaVersion(),
                info.javaVersion(),
                info.serverPort(),
                info.serverPort()
        );
    }

    private String generateGenericDockerfile(ProjectAnalyzer.ProjectInfo info) {
        return """
                FROM eclipse-temurin:%s-jre
                WORKDIR /app

                RUN groupadd -r appuser && useradd -r -g appuser appuser
                USER appuser

                COPY target/*.jar app.jar

                HEALTHCHECK --interval=30s --timeout=3s --retries=3 \\
                    CMD curl -f http://localhost:%d/actuator/health || exit 1

                ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

                EXPOSE %d
                ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
                """.formatted(info.javaVersion(), info.serverPort(), info.serverPort());
    }
}
