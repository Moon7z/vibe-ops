package com.opszen.deploy;

import org.springframework.stereotype.Component;

/**
 * Generates GitHub Actions CI/CD workflow YAML files.
 */
@Component
public class GitHubActionsGenerator {

    public String generate(ProjectAnalyzer.ProjectInfo info, PipelineOptions options) {
        return switch (info.buildTool()) {
            case MAVEN -> generateMavenPipeline(info, options);
            case GRADLE -> generateGradlePipeline(info, options);
            default -> generateGenericPipeline(info, options);
        };
    }

    private String generateMavenPipeline(ProjectAnalyzer.ProjectInfo info, PipelineOptions options) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: CI/CD Pipeline\n\n");

        // Triggers
        sb.append("on:\n");
        sb.append("  push:\n");
        sb.append("    branches: [main, develop]\n");
        sb.append("  pull_request:\n");
        sb.append("    branches: [main]\n\n");

        sb.append("env:\n");
        sb.append("  JAVA_VERSION: '%s'\n".formatted(info.javaVersion()));
        sb.append("  REGISTRY: ghcr.io\n");
        sb.append("  IMAGE_NAME: ${{ github.repository }}\n\n");

        sb.append("jobs:\n\n");

        // ── Build & Test ──
        sb.append("  build-and-test:\n");
        sb.append("    name: Build & Test\n");
        sb.append("    runs-on: ubuntu-latest\n");
        sb.append("    steps:\n");
        sb.append("      - uses: actions/checkout@v4\n\n");
        sb.append("      - name: Set up JDK ${{ env.JAVA_VERSION }}\n");
        sb.append("        uses: actions/setup-java@v4\n");
        sb.append("        with:\n");
        sb.append("          java-version: ${{ env.JAVA_VERSION }}\n");
        sb.append("          distribution: 'temurin'\n");
        sb.append("          cache: 'maven'\n\n");

        sb.append("      - name: Build\n");
        sb.append("        run: mvn compile -B\n\n");

        sb.append("      - name: Run Tests\n");
        sb.append("        run: mvn test -B\n\n");

        sb.append("      - name: Upload Test Reports\n");
        sb.append("        if: always()\n");
        sb.append("        uses: actions/upload-artifact@v4\n");
        sb.append("        with:\n");
        sb.append("          name: test-reports\n");
        sb.append("          path: target/surefire-reports/\n\n");

        sb.append("      - name: Package\n");
        sb.append("        run: mvn package -DskipTests -B\n\n");

        sb.append("      - name: Upload Artifact\n");
        sb.append("        uses: actions/upload-artifact@v4\n");
        sb.append("        with:\n");
        sb.append("          name: app-jar\n");
        sb.append("          path: target/*.jar\n\n");

        // ── Docker Build ──
        if (options.includeDocker()) {
            sb.append("  docker:\n");
            sb.append("    name: Build & Push Docker Image\n");
            sb.append("    needs: build-and-test\n");
            sb.append("    runs-on: ubuntu-latest\n");
            sb.append("    if: github.event_name == 'push' && github.ref == 'refs/heads/main'\n");
            sb.append("    permissions:\n");
            sb.append("      contents: read\n");
            sb.append("      packages: write\n");
            sb.append("    steps:\n");
            sb.append("      - uses: actions/checkout@v4\n\n");

            sb.append("      - name: Log in to Container Registry\n");
            sb.append("        uses: docker/login-action@v3\n");
            sb.append("        with:\n");
            sb.append("          registry: ${{ env.REGISTRY }}\n");
            sb.append("          username: ${{ github.actor }}\n");
            sb.append("          password: ${{ secrets.GITHUB_TOKEN }}\n\n");

            sb.append("      - name: Extract metadata\n");
            sb.append("        id: meta\n");
            sb.append("        uses: docker/metadata-action@v5\n");
            sb.append("        with:\n");
            sb.append("          images: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}\n");
            sb.append("          tags: |\n");
            sb.append("            type=sha\n");
            sb.append("            type=ref,event=branch\n");
            sb.append("            type=semver,pattern={{version}}\n\n");

            sb.append("      - name: Build and Push\n");
            sb.append("        uses: docker/build-push-action@v5\n");
            sb.append("        with:\n");
            sb.append("          context: .\n");
            sb.append("          push: true\n");
            sb.append("          tags: ${{ steps.meta.outputs.tags }}\n");
            sb.append("          labels: ${{ steps.meta.outputs.labels }}\n\n");
        }

        // ── Deploy ──
        if (options.includeDeploy()) {
            sb.append("  deploy:\n");
            sb.append("    name: Deploy to Production\n");
            sb.append("    needs: %s\n".formatted(options.includeDocker() ? "docker" : "build-and-test"));
            sb.append("    runs-on: ubuntu-latest\n");
            sb.append("    if: github.event_name == 'push' && github.ref == 'refs/heads/main'\n");
            sb.append("    environment: production\n");
            sb.append("    steps:\n");
            sb.append("      - uses: actions/checkout@v4\n\n");
            sb.append("      - name: Deploy\n");
            sb.append("        run: |\n");
            sb.append("          echo \"Deploying ${{ env.IMAGE_NAME }}...\"\n");
            sb.append("          # Add your deployment commands here\n");
            sb.append("          # e.g., kubectl set image, docker compose up, etc.\n\n");
        }

        return sb.toString();
    }

    private String generateGradlePipeline(ProjectAnalyzer.ProjectInfo info, PipelineOptions options) {
        // Simplified — same structure, different build commands
        String pipeline = generateMavenPipeline(info, options);
        return pipeline
                .replace("cache: 'maven'", "cache: 'gradle'")
                .replace("mvn compile -B", "./gradlew compileJava")
                .replace("mvn test -B", "./gradlew test")
                .replace("mvn package -DskipTests -B", "./gradlew bootJar")
                .replace("target/surefire-reports/", "build/reports/tests/")
                .replace("target/*.jar", "build/libs/*.jar");
    }

    private String generateGenericPipeline(ProjectAnalyzer.ProjectInfo info, PipelineOptions options) {
        return generateMavenPipeline(info, options);
    }

    public record PipelineOptions(boolean includeDocker, boolean includeDeploy) {
        public static PipelineOptions defaults() {
            return new PipelineOptions(true, false);
        }

        public static PipelineOptions full() {
            return new PipelineOptions(true, true);
        }
    }
}
