package com.opszen.test;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generates test code prompts and templates for AI-driven test generation.
 * Analyzes source code structure and produces targeted test generation instructions.
 */
@Component
public class TestPromptEngine {

    /**
     * Build a complete prompt for an AI model to generate JUnit 5 tests.
     */
    public String buildTestGenerationPrompt(String sourceCode, String className, String context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a senior Java test engineer. Generate comprehensive JUnit 5 tests.\n\n");
        prompt.append("## Rules\n");
        prompt.append("1. Use JUnit 5 (org.junit.jupiter.api) with AssertJ or standard assertions\n");
        prompt.append("2. Use @SpringBootTest only if Spring context is needed, otherwise plain unit tests\n");
        prompt.append("3. Use Mockito for external dependencies\n");
        prompt.append("4. Cover: happy path, edge cases, error scenarios, boundary values\n");
        prompt.append("5. Each test method must have a descriptive name: should_<expected>_when_<condition>\n");
        prompt.append("6. Include @DisplayName annotations\n");
        prompt.append("7. Test class name must be: ").append(className).append("Test\n");
        prompt.append("8. Package must match the source class package\n\n");

        prompt.append("## Source Code to Test\n\n");
        prompt.append("```java\n").append(sourceCode).append("\n```\n\n");

        if (context != null && !context.isBlank()) {
            prompt.append("## Additional Context\n\n").append(context).append("\n\n");
        }

        // Analyze code structure for targeted test guidance
        List<String> hints = analyzeForTestHints(sourceCode);
        if (!hints.isEmpty()) {
            prompt.append("## Test Focus Areas\n\n");
            for (String hint : hints) {
                prompt.append("- ").append(hint).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("## Output\n\n");
        prompt.append("Return ONLY the complete Java test class. No explanations, no markdown fences.\n");

        return prompt.toString();
    }

    /**
     * Build a prompt for generating integration tests.
     */
    public String buildIntegrationTestPrompt(String sourceCode, String className) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a senior Java test engineer. Generate a Spring Boot integration test.\n\n");
        prompt.append("## Rules\n");
        prompt.append("1. Use @SpringBootTest with @AutoConfigureMockMvc for controller tests\n");
        prompt.append("2. Use MockMvc for HTTP endpoint testing\n");
        prompt.append("3. Test request/response serialization\n");
        prompt.append("4. Test error responses (400, 404, 500)\n");
        prompt.append("5. Test class name must be: ").append(className).append("IntegrationTest\n\n");

        prompt.append("## Source Code\n\n");
        prompt.append("```java\n").append(sourceCode).append("\n```\n\n");

        prompt.append("## Output\n\n");
        prompt.append("Return ONLY the complete Java test class. No explanations, no markdown fences.\n");

        return prompt.toString();
    }

    /**
     * Analyze source code to extract test hints.
     */
    private List<String> analyzeForTestHints(String sourceCode) {
        var hints = new java.util.ArrayList<String>();

        if (sourceCode.contains("@RestController") || sourceCode.contains("@Controller")) {
            hints.add("This is a REST controller — test HTTP methods, status codes, and request validation");
        }
        if (sourceCode.contains("@Service") || sourceCode.contains("@Component")) {
            hints.add("This is a Spring-managed bean — consider dependency injection and mocking");
        }
        if (sourceCode.contains("throws") || sourceCode.contains("catch")) {
            hints.add("Exception handling detected — test both success and error paths");
        }
        if (sourceCode.contains("Optional")) {
            hints.add("Optional usage detected — test present and empty cases");
        }
        if (sourceCode.contains("List") || sourceCode.contains("Map") || sourceCode.contains("Collection")) {
            hints.add("Collections detected — test empty, single-element, and multi-element cases");
        }
        if (sourceCode.contains("@Valid") || sourceCode.contains("@NotNull") || sourceCode.contains("@NotBlank")) {
            hints.add("Bean validation detected — test constraint violations");
        }
        if (sourceCode.contains("synchronized") || sourceCode.contains("CompletableFuture")
                || sourceCode.contains("ExecutorService")) {
            hints.add("Concurrency detected — consider thread-safety tests");
        }
        if (sourceCode.contains("Files.") || sourceCode.contains("Path.")) {
            hints.add("File I/O detected — use @TempDir for filesystem tests");
        }

        return hints;
    }

    /**
     * Extract the class name from source code.
     */
    public String extractClassName(String sourceCode) {
        var matcher = java.util.regex.Pattern
                .compile("(?:class|interface|enum|record)\\s+(\\w+)")
                .matcher(sourceCode);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "Unknown";
    }

    /**
     * Extract the package name from source code.
     */
    public String extractPackage(String sourceCode) {
        var matcher = java.util.regex.Pattern
                .compile("package\\s+([\\w.]+)\\s*;")
                .matcher(sourceCode);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
}
