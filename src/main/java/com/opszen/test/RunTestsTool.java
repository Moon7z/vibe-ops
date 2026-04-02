package com.opszen.test;

import com.opszen.mcp.McpProtocol;
import com.opszen.mcp.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP Tool: run-tests
 * Executes Maven tests in a project and returns a structured report.
 * Enforces the test gate: blocks merge recommendation if tests fail.
 */
@Component
public class RunTestsTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(RunTestsTool.class);

    private final MavenTestExecutor executor;

    public RunTestsTool(MavenTestExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "run-tests";
    }

    @Override
    public String description() {
        return "Executes Maven tests (mvn test) in the specified project directory. " +
               "Returns a structured report with pass/fail counts, gate decision, and failure details. " +
               "Supports running all tests or a specific test class. " +
               "Enforces the test gate: BLOCKED if any test fails, PASS if all succeed.";
    }

    @Override
    public McpProtocol.InputSchema inputSchema() {
        return new McpProtocol.InputSchema(
                "object",
                Map.of(
                        "projectPath", new McpProtocol.PropertyDef("string",
                                "Absolute path to the Maven project root", null),
                        "testClass", new McpProtocol.PropertyDef("string",
                                "Optional: specific test class to run (e.g., 'com.example.FooTest')", null)
                ),
                List.of("projectPath")
        );
    }

    @Override
    public McpProtocol.ToolResult execute(Map<String, Object> arguments) {
        String projectPath = (String) arguments.get("projectPath");
        String testClass = (String) arguments.getOrDefault("testClass", null);

        if (projectPath == null || projectPath.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'projectPath' is required.");
        }

        log.info("Running tests in: {} (class: {})", projectPath, testClass != null ? testClass : "ALL");

        MavenTestExecutor.TestExecutionResult result = executor.runTests(projectPath, testClass);
        String report = generateReport(result, projectPath, testClass);

        return McpProtocol.ToolResult.success(report);
    }

    private String generateReport(MavenTestExecutor.TestExecutionResult result,
                                  String projectPath, String testClass) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Test Execution Report\n\n");
        sb.append("**Project**: `%s`\n".formatted(projectPath));
        if (testClass != null) {
            sb.append("**Test Class**: `%s`\n".formatted(testClass));
        }
        sb.append("\n");

        // Gate decision
        if (result.success()) {
            sb.append("## Gate: PASS\n\n");
            sb.append("All tests passed. Code is eligible for merge.\n\n");
        } else {
            sb.append("## Gate: BLOCKED\n\n");
            sb.append("Tests failed. **Merge is not allowed** until all tests pass.\n\n");
        }

        // Metrics
        sb.append("## Results\n\n");
        sb.append("| Metric | Count |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Tests Run | %d |\n".formatted(result.testsRun()));
        sb.append("| Passed | %d |\n".formatted(result.testsRun() - result.failures() - result.errors()));
        sb.append("| Failures | %d |\n".formatted(result.failures()));
        sb.append("| Errors | %d |\n".formatted(result.errors()));
        sb.append("| Skipped | %d |\n".formatted(result.skipped()));
        sb.append("| Exit Code | %d |\n\n".formatted(result.exitCode()));

        // Pass rate
        if (result.testsRun() > 0) {
            int passed = result.testsRun() - result.failures() - result.errors();
            double rate = (passed * 100.0) / result.testsRun();
            int filled = (int) (rate / 5);
            sb.append("**Pass Rate**: %.1f%%\n".formatted(rate));
            sb.append("`[" + "█".repeat(Math.max(0, filled)) + "░".repeat(Math.max(0, 20 - filled)) + "]`\n\n");
        }

        // Surefire summary
        if (result.summary() != null && !result.summary().isBlank()) {
            sb.append("## Maven Output Summary\n\n");
            sb.append("```\n").append(result.summary()).append("```\n\n");
        }

        // Failure details
        if (!result.success() && result.fullOutput() != null) {
            String failureDetails = extractFailureDetails(result.fullOutput());
            if (!failureDetails.isBlank()) {
                sb.append("## Failure Details\n\n");
                sb.append("```\n").append(failureDetails).append("```\n\n");
            }

            sb.append("## Recommended Actions\n\n");
            sb.append("1. Review the failure details above\n");
            sb.append("2. Fix the failing tests or the source code\n");
            sb.append("3. Re-run `run-tests` to verify the fix\n");
            sb.append("4. Only proceed with merge after all tests pass\n\n");
        }

        sb.append("---\n*Generated by OpsZen Test-Autopilot v0.1.0*\n");
        return sb.toString();
    }

    private String extractFailureDetails(String output) {
        StringBuilder details = new StringBuilder();
        String[] lines = output.split("\n");
        boolean inFailure = false;
        int failureLines = 0;

        for (String line : lines) {
            if (line.contains("<<< FAILURE!") || line.contains("<<< ERROR!")) {
                inFailure = true;
                failureLines = 0;
            }
            if (inFailure) {
                details.append(line).append("\n");
                failureLines++;
                if (failureLines > 20 || line.contains("[INFO] ---") || line.contains("[ERROR] ---")) {
                    inFailure = false;
                    details.append("...\n\n");
                }
            }
        }

        // Limit output size
        String result = details.toString();
        if (result.length() > 3000) {
            result = result.substring(0, 3000) + "\n... (truncated)";
        }
        return result;
    }
}
