package com.opszen.deploy;

import org.springframework.stereotype.Component;

/**
 * Generates docker-compose.yml with the application service
 * and common infrastructure services (database, redis, etc.).
 */
@Component
public class DockerComposeGenerator {

    public String generate(ProjectAnalyzer.ProjectInfo info, ComposeOptions options) {
        StringBuilder sb = new StringBuilder();
        sb.append("services:\n\n");

        // Application service
        sb.append("  %s:\n".formatted(info.projectName()));
        sb.append("    build:\n");
        sb.append("      context: .\n");
        sb.append("      dockerfile: Dockerfile\n");
        sb.append("    container_name: %s\n".formatted(info.projectName()));
        sb.append("    ports:\n");
        sb.append("      - \"%d:%d\"\n".formatted(info.serverPort(), info.serverPort()));
        sb.append("    environment:\n");
        sb.append("      - SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-docker}\n");
        sb.append("      - JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0\n");

        if (options.includePostgres()) {
            sb.append("      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/%s\n".formatted(info.projectName()));
            sb.append("      - SPRING_DATASOURCE_USERNAME=app\n");
            sb.append("      - SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD:-changeme}\n");
        }

        if (options.includeRedis()) {
            sb.append("      - SPRING_DATA_REDIS_HOST=redis\n");
            sb.append("      - SPRING_DATA_REDIS_PORT=6379\n");
        }

        sb.append("    healthcheck:\n");
        sb.append("      test: [\"CMD\", \"curl\", \"-f\", \"http://localhost:%d/actuator/health\"]\n".formatted(info.serverPort()));
        sb.append("      interval: 30s\n");
        sb.append("      timeout: 3s\n");
        sb.append("      retries: 3\n");
        sb.append("      start_period: 40s\n");
        sb.append("    restart: unless-stopped\n");

        // Dependencies
        var deps = new java.util.ArrayList<String>();
        if (options.includePostgres()) deps.add("postgres");
        if (options.includeRedis()) deps.add("redis");
        if (!deps.isEmpty()) {
            sb.append("    depends_on:\n");
            for (String dep : deps) {
                sb.append("      %s:\n".formatted(dep));
                sb.append("        condition: service_healthy\n");
            }
        }

        // PostgreSQL
        if (options.includePostgres()) {
            sb.append("\n  postgres:\n");
            sb.append("    image: postgres:16-alpine\n");
            sb.append("    container_name: %s-postgres\n".formatted(info.projectName()));
            sb.append("    environment:\n");
            sb.append("      - POSTGRES_DB=%s\n".formatted(info.projectName()));
            sb.append("      - POSTGRES_USER=app\n");
            sb.append("      - POSTGRES_PASSWORD=${DB_PASSWORD:-changeme}\n");
            sb.append("    volumes:\n");
            sb.append("      - postgres_data:/var/lib/postgresql/data\n");
            sb.append("    ports:\n");
            sb.append("      - \"5432:5432\"\n");
            sb.append("    healthcheck:\n");
            sb.append("      test: [\"CMD-SHELL\", \"pg_isready -U app -d %s\"]\n".formatted(info.projectName()));
            sb.append("      interval: 10s\n");
            sb.append("      timeout: 3s\n");
            sb.append("      retries: 5\n");
            sb.append("    restart: unless-stopped\n");
        }

        // Redis
        if (options.includeRedis()) {
            sb.append("\n  redis:\n");
            sb.append("    image: redis:7-alpine\n");
            sb.append("    container_name: %s-redis\n".formatted(info.projectName()));
            sb.append("    ports:\n");
            sb.append("      - \"6379:6379\"\n");
            sb.append("    healthcheck:\n");
            sb.append("      test: [\"CMD\", \"redis-cli\", \"ping\"]\n");
            sb.append("      interval: 10s\n");
            sb.append("      timeout: 3s\n");
            sb.append("      retries: 5\n");
            sb.append("    restart: unless-stopped\n");
        }

        // Volumes
        if (options.includePostgres()) {
            sb.append("\nvolumes:\n");
            sb.append("  postgres_data:\n");
        }

        return sb.toString();
    }

    public record ComposeOptions(boolean includePostgres, boolean includeRedis) {
        public static ComposeOptions defaults() {
            return new ComposeOptions(false, false);
        }
    }
}
