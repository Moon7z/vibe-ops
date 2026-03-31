package com.vibeops.spec;

import com.vibeops.mcp.McpProtocol;
import com.vibeops.mcp.McpTool;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Spec-Verifier: analyze-vibe tool.
 * Analyzes a user prompt / requirement for production-readiness completeness.
 * Checks for error handling, idempotency, auth, observability, and more.
 */
@Component
public class AnalyzeVibeTool implements McpTool {

    private static final List<QualityDimension> DIMENSIONS = List.of(
            new QualityDimension("error_handling",
                    "Error Handling & Resilience",
                    List.of("error", "exception", "fail", "retry", "fallback", "timeout", "circuit.?breaker"),
                    "Does the spec define how errors, timeouts, and failures should be handled?"),
            new QualityDimension("idempotency",
                    "Idempotency & Safety",
                    List.of("idempoten", "retry.?safe", "dedup", "unique.*key", "at.?most.?once", "at.?least.?once", "exactly.?once"),
                    "Are operations designed to be safely retryable?"),
            new QualityDimension("auth_security",
                    "Authentication & Security",
                    List.of("auth", "token", "jwt", "oauth", "rbac", "permission", "encrypt", "secret", "credential"),
                    "Are authentication, authorization, and data security addressed?"),
            new QualityDimension("observability",
                    "Observability & Monitoring",
                    List.of("log", "metric", "trace", "monitor", "alert", "dashboard", "health.?check"),
                    "Is there a plan for logging, metrics, tracing, and alerting?"),
            new QualityDimension("data_integrity",
                    "Data Integrity & Validation",
                    List.of("valid", "schema", "constraint", "migration", "rollback", "backup", "transaction"),
                    "Are input validation, data constraints, and migration strategies defined?"),
            new QualityDimension("scalability",
                    "Scalability & Performance",
                    List.of("cache", "concurren", "throughput", "latency", "rate.?limit", "queue", "async", "batch", "pagination"),
                    "Are performance targets, caching, and concurrency strategies considered?"),
            new QualityDimension("testing",
                    "Testing Strategy",
                    List.of("test", "unit.?test", "integration.?test", "e2e", "mock", "fixture", "coverage"),
                    "Is there a defined testing strategy (unit, integration, e2e)?"),
            new QualityDimension("deployment",
                    "Deployment & Rollback",
                    List.of("deploy", "ci/?cd", "pipeline", "rollback", "canary", "blue.?green", "feature.?flag"),
                    "Are deployment strategy and rollback procedures defined?")
    );

    @Override
    public String name() {
        return "analyze-vibe";
    }

    @Override
    public String description() {
        return "Analyzes a development prompt/requirement for production-readiness completeness. " +
               "Checks for error handling, idempotency, security, observability, testing, and deployment considerations. " +
               "Returns a structured Markdown report with a readiness score and actionable recommendations.";
    }

    @Override
    public McpProtocol.InputSchema inputSchema() {
        return new McpProtocol.InputSchema(
                "object",
                Map.of(
                        "prompt", new McpProtocol.PropertyDef("string",
                                "The development prompt or requirement text to analyze", null),
                        "context", new McpProtocol.PropertyDef("string",
                                "Optional additional context (e.g., existing architecture, constraints)", null)
                ),
                List.of("prompt")
        );
    }

    @Override
    public McpProtocol.ToolResult execute(Map<String, Object> arguments) {
        String prompt = (String) arguments.get("prompt");
        String context = (String) arguments.getOrDefault("context", "");

        if (prompt == null || prompt.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'prompt' is required and must not be empty.");
        }

        String fullText = (prompt + " " + context).toLowerCase();
        List<DimensionResult> results = analyzeDimensions(fullText);

        int totalScore = results.stream().mapToInt(DimensionResult::score).sum();
        int maxScore = DIMENSIONS.size() * 10;
        double percentage = (totalScore * 100.0) / maxScore;

        String report = generateReport(prompt, results, totalScore, maxScore, percentage);
        return McpProtocol.ToolResult.success(report);
    }

    private List<DimensionResult> analyzeDimensions(String text) {
        List<DimensionResult> results = new ArrayList<>();
        for (QualityDimension dim : DIMENSIONS) {
            long matchCount = dim.keywords().stream()
                    .filter(kw -> text.matches("(?s).*" + kw + ".*"))
                    .count();
            int score;
            String status;
            if (matchCount >= 3) {
                score = 10;
                status = "COVERED";
            } else if (matchCount >= 1) {
                score = 5;
                status = "PARTIAL";
            } else {
                score = 0;
                status = "MISSING";
            }
            results.add(new DimensionResult(dim, score, status, matchCount));
        }
        return results;
    }

    private String generateReport(String prompt, List<DimensionResult> results,
                                  int totalScore, int maxScore, double percentage) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Vibe Analysis Report\n\n");

        // Overall score
        String grade = percentage >= 80 ? "PRODUCTION-READY" :
                       percentage >= 50 ? "NEEDS-IMPROVEMENT" : "HIGH-RISK";
        sb.append("## Overall Score: %d/%d (%.0f%%) — **%s**\n\n".formatted(totalScore, maxScore, percentage, grade));

        // Score bar
        int filled = (int) (percentage / 5);
        sb.append("`[" + "█".repeat(filled) + "░".repeat(20 - filled) + "]`\n\n");

        // Dimension breakdown
        sb.append("## Dimension Breakdown\n\n");
        sb.append("| Dimension | Score | Status |\n");
        sb.append("|-----------|-------|--------|\n");
        for (DimensionResult r : results) {
            String icon = switch (r.status()) {
                case "COVERED" -> "OK";
                case "PARTIAL" -> "WARN";
                default -> "FAIL";
            };
            sb.append("| %s | %d/10 | %s %s |\n".formatted(
                    r.dimension().label(), r.score(), icon, r.status()));
        }

        // Missing dimensions
        List<DimensionResult> missing = results.stream()
                .filter(r -> "MISSING".equals(r.status()))
                .toList();
        if (!missing.isEmpty()) {
            sb.append("\n## Action Required\n\n");
            sb.append("The following dimensions need to be addressed before deployment:\n\n");
            for (DimensionResult r : missing) {
                sb.append("### %s\n".formatted(r.dimension().label()));
                sb.append("- **Question**: %s\n".formatted(r.dimension().question()));
                sb.append("- **Suggestion**: Add explicit requirements for this dimension to your prompt.\n\n");
            }
        }

        // Partial dimensions
        List<DimensionResult> partial = results.stream()
                .filter(r -> "PARTIAL".equals(r.status()))
                .toList();
        if (!partial.isEmpty()) {
            sb.append("\n## Improvements Suggested\n\n");
            for (DimensionResult r : partial) {
                sb.append("- **%s**: Partially mentioned but lacks detail. %s\n".formatted(
                        r.dimension().label(), r.dimension().question()));
            }
        }

        sb.append("\n---\n*Generated by Vibe-Ops Spec-Verifier v0.1.0*\n");
        return sb.toString();
    }

    private record QualityDimension(String id, String label, List<String> keywords, String question) {}
    private record DimensionResult(QualityDimension dimension, int score, String status, long matchCount) {}
}
