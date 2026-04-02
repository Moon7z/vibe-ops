package com.opszen.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Executes Maven test commands and captures structured output.
 * Supports timeout control and retry mechanisms (Reliability-First principle).
 */
@Component
public class MavenTestExecutor {

    private static final Logger log = LoggerFactory.getLogger(MavenTestExecutor.class);

    @Value("${opszen.execution.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${opszen.execution.max-retries:3}")
    private int maxRetries;

    /**
     * Run `mvn test` in the given project directory and capture results.
     */
    public TestExecutionResult runTests(String projectPath) {
        return runTests(projectPath, null);
    }

    /**
     * Run `mvn test` for a specific test class.
     */
    public TestExecutionResult runTests(String projectPath, String testClassName) {
        Path projectDir = Path.of(projectPath);
        if (!Files.isDirectory(projectDir)) {
            return TestExecutionResult.failure("Project directory does not exist: " + projectPath);
        }

        // Check pom.xml exists
        if (!Files.exists(projectDir.resolve("pom.xml"))) {
            return TestExecutionResult.failure("No pom.xml found in: " + projectPath);
        }

        int attempt = 0;
        TestExecutionResult lastResult = null;

        while (attempt < maxRetries) {
            attempt++;
            log.info("Test execution attempt {}/{} for {}", attempt, maxRetries, projectPath);

            lastResult = executeWithTimeout(projectDir, testClassName);

            if (lastResult.success() || !lastResult.retryable()) {
                break;
            }

            log.warn("Test execution attempt {} failed (retryable), retrying...", attempt);
        }

        return lastResult;
    }

    private TestExecutionResult executeWithTimeout(Path projectDir, String testClassName) {
        try {
            // Detect maven wrapper or fall back to mvn
            String mvnCmd = detectMavenCommand(projectDir);

            var command = new java.util.ArrayList<String>();
            command.add(mvnCmd);
            command.add("test");
            command.add("-B"); // batch mode, no interactive
            command.add("--fail-at-end");

            if (testClassName != null && !testClassName.isBlank()) {
                command.add("-Dtest=" + testClassName);
                command.add("-DfailIfNoTests=false");
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Capture output asynchronously
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                } catch (Exception e) {
                    return "Error reading output: " + e.getMessage();
                }
            });

            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return TestExecutionResult.failure("Test execution timed out after " + timeoutSeconds + "s");
            }

            String output = outputFuture.get(5, TimeUnit.SECONDS);
            int exitCode = process.exitValue();

            return parseTestOutput(output, exitCode);

        } catch (Exception e) {
            log.error("Test execution error", e);
            return TestExecutionResult.retryableFailure("Execution error: " + e.getMessage());
        }
    }

    private String detectMavenCommand(Path projectDir) {
        // Check for mvnw (Maven Wrapper)
        Path mvnw = projectDir.resolve("mvnw");
        Path mvnwCmd = projectDir.resolve("mvnw.cmd");

        if (Files.isExecutable(mvnw)) {
            return mvnw.toString();
        }
        if (Files.exists(mvnwCmd)) {
            return mvnwCmd.toString();
        }
        return "mvn";
    }

    private TestExecutionResult parseTestOutput(String output, int exitCode) {
        int testsRun = extractCount(output, "Tests run:");
        int failures = extractCount(output, "Failures:");
        int errors = extractCount(output, "Errors:");
        int skipped = extractCount(output, "Skipped:");

        boolean success = exitCode == 0;

        // Extract surefire summary section
        String summary = extractSurefireSummary(output);

        return new TestExecutionResult(
                success,
                false,
                exitCode,
                testsRun,
                failures,
                errors,
                skipped,
                summary,
                output
        );
    }

    private int extractCount(String output, String label) {
        try {
            int idx = output.lastIndexOf(label);
            if (idx < 0) return 0;
            String after = output.substring(idx + label.length()).trim();
            // Extract number: "Tests run: 5," -> "5"
            StringBuilder num = new StringBuilder();
            for (char c : after.toCharArray()) {
                if (Character.isDigit(c)) num.append(c);
                else break;
            }
            return num.isEmpty() ? 0 : Integer.parseInt(num.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private String extractSurefireSummary(String output) {
        String[] lines = output.split("\n");
        StringBuilder summary = new StringBuilder();
        boolean inSummary = false;

        for (String line : lines) {
            if (line.contains("T E S T S") || line.contains("Results:") || line.contains("Tests run:")) {
                inSummary = true;
            }
            if (inSummary) {
                summary.append(line).append("\n");
            }
            if (inSummary && line.contains("BUILD")) {
                break;
            }
        }

        return summary.isEmpty() ? "No test summary available" : summary.toString();
    }

    public record TestExecutionResult(
            boolean success,
            boolean retryable,
            int exitCode,
            int testsRun,
            int failures,
            int errors,
            int skipped,
            String summary,
            String fullOutput
    ) {
        public static TestExecutionResult failure(String message) {
            return new TestExecutionResult(false, false, -1, 0, 0, 0, 0, message, message);
        }

        public static TestExecutionResult retryableFailure(String message) {
            return new TestExecutionResult(false, true, -1, 0, 0, 0, 0, message, message);
        }
    }
}
