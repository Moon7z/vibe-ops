package com.vibeops.heal;

import com.vibeops.mcp.McpProtocol;
import com.vibeops.mcp.McpTool;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * MCP Tool: auto-heal
 * Full self-healing pipeline: monitor → diagnose → plan → repair prompt.
 *
 * Workflow:
 * 1. Check K8s pods or Docker containers for failures
 * 2. Extract and analyze error logs
 * 3. Generate a repair plan with concrete actions
 * 4. Produce a Claude Code repair prompt for automated fixing
 */
@Component
public class AutoHealTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(AutoHealTool.class);

    private final LogMonitor logMonitor;
    private final ErrorAnalyzer errorAnalyzer;
    private final HealingStrategyEngine strategyEngine;
    private final HealConfig healConfig;
    private final AuditLogger auditLogger;
    private final BackupManager backupManager;
    private final RateLimiter rateLimiter;

    public AutoHealTool(LogMonitor logMonitor, ErrorAnalyzer errorAnalyzer,
                        HealingStrategyEngine strategyEngine, HealConfig healConfig,
                        AuditLogger auditLogger, BackupManager backupManager) {
        this.logMonitor = logMonitor;
        this.errorAnalyzer = errorAnalyzer;
        this.strategyEngine = strategyEngine;
        this.healConfig = healConfig;
        this.auditLogger = auditLogger;
        this.backupManager = backupManager;
        // permits per second = maxAutoFixesPerHour / 3600
        double permitsPerSecond = Math.max(healConfig.getMaxAutoFixesPerHour(), 1) / 3600.0;
        this.rateLimiter = RateLimiter.create(permitsPerSecond);
    }

    @Override
    public String name() {
        return "auto-heal";
    }

    @Override
    public String description() {
        return "Full self-healing pipeline: monitors K8s pods or Docker containers, detects failures " +
               "(CrashLoopBackOff, OOMKilled, etc.), extracts error logs, diagnoses root cause, " +
               "and generates a repair plan with Claude Code prompts for automated code fixes. " +
               "Supports three sources: 'kubernetes' (live pods), 'docker' (local containers), " +
               "or 'logs' (raw log text).";
    }

    @Override
    public McpProtocol.InputSchema inputSchema() {
        return new McpProtocol.InputSchema(
                "object",
                Map.of(
                        "source", new McpProtocol.PropertyDef("string",
                                "Monitoring source: 'kubernetes', 'docker', or 'logs'", null),
                        "namespace", new McpProtocol.PropertyDef("string",
                                "K8s namespace (required for source='kubernetes')", null),
                        "labelSelector", new McpProtocol.PropertyDef("string",
                                "K8s label selector, e.g., 'app=my-service' (for source='kubernetes')", null),
                        "projectPath", new McpProtocol.PropertyDef("string",
                                "Project path (for source='docker' to locate docker-compose.yml, or for code context)", null),
                        "logs", new McpProtocol.PropertyDef("string",
                                "Raw log text to analyze (for source='logs')", null)
                ),
                List.of("source")
        );
    }

    @Override
    public McpProtocol.ToolResult execute(Map<String, Object> arguments) {
        String source = (String) arguments.get("source");
        if (source == null || source.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'source' is required (kubernetes, docker, or logs).");
        }

        String mode = healConfig.getMode();

        // Rate limit check (non-blocking)
        if (!"propose-only".equals(mode) && !rateLimiter.tryAcquire()) {
            return McpProtocol.ToolResult.error(
                    "{\"error\":\"rate_limited\",\"message\":\"Max " + healConfig.getMaxAutoFixesPerHour()
                    + " auto-fixes per hour exceeded.\",\"retry_after\":\"" + retryAfterSeconds() + "s\"}");
        }

        // Full-auto safety: block in production
        if ("full-auto".equals(mode)) {
            String env = System.getenv("VIBEOPS_ENV");
            if ("production".equalsIgnoreCase(env)) {
                return McpProtocol.ToolResult.error(
                        "full-auto mode is disabled in production environment (VIBEOPS_ENV=production).");
            }
        }

        return switch (source.toLowerCase()) {
            case "kubernetes", "k8s" -> healKubernetes(arguments);
            case "docker", "compose" -> healDocker(arguments);
            case "logs", "log" -> healFromLogs(arguments);
            default -> McpProtocol.ToolResult.error(
                    "Invalid source: '%s'. Use 'kubernetes', 'docker', or 'logs'.".formatted(source));
        };
    }

    private String retryAfterSeconds() {
        int maxPerHour = healConfig.getMaxAutoFixesPerHour();
        return String.valueOf(maxPerHour > 0 ? 3600 / maxPerHour : 3600);
    }

    private McpProtocol.ToolResult healKubernetes(Map<String, Object> arguments) {
        String namespace = (String) arguments.getOrDefault("namespace", "default");
        String labelSelector = (String) arguments.getOrDefault("labelSelector", "");
        String projectPath = (String) arguments.getOrDefault("projectPath", "");

        if (labelSelector == null || labelSelector.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'labelSelector' is required for Kubernetes source.");
        }

        StringBuilder report = new StringBuilder();
        report.append("# Auto-Heal Report — Kubernetes\n\n");
        report.append("**Namespace**: `%s`\n".formatted(namespace));
        report.append("**Selector**: `%s`\n\n".formatted(labelSelector));

        // Step 1: Monitor
        report.append("## 1. Pod Status Check\n\n");
        LogMonitor.PodStatusReport podReport = logMonitor.checkKubernetesPods(namespace, labelSelector);

        if (podReport.pods().isEmpty()) {
            report.append("No pods found matching the selector.\n\n");
            if (!podReport.errors().isEmpty()) {
                report.append("**Errors**:\n");
                for (String err : podReport.errors()) {
                    report.append("- %s\n".formatted(err));
                }
            }
            report.append("\n---\n*Generated by Vibe-Ops Self-Healing Agent v0.1.0*\n");
            return McpProtocol.ToolResult.success(report.toString());
        }

        report.append("| Pod | Ready | Status | Restarts |\n");
        report.append("|-----|-------|--------|----------|\n");
        for (LogMonitor.PodInfo pod : podReport.pods()) {
            String statusIcon = pod.isUnhealthy() ? "FAIL" : "OK";
            report.append("| %s | %s | %s %s | %d |\n".formatted(
                    pod.name(), pod.ready(), statusIcon, pod.status(), pod.restartCount()));
        }
        report.append("\n");

        if (podReport.healthy()) {
            report.append("## Result: ALL HEALTHY\n\n");
            report.append("No unhealthy pods detected. No healing needed.\n\n");
            report.append("---\n*Generated by Vibe-Ops Self-Healing Agent v0.1.0*\n");
            return McpProtocol.ToolResult.success(report.toString());
        }

        // Step 2: Collect and analyze logs from unhealthy pods
        List<LogMonitor.PodInfo> unhealthyPods = podReport.pods().stream()
                .filter(LogMonitor.PodInfo::isUnhealthy)
                .toList();

        String combinedLogs = unhealthyPods.stream()
                .map(p -> p.recentLogs() != null ? p.recentLogs() : "")
                .collect(Collectors.joining("\n---\n"));

        return analyzeAndReport(report, combinedLogs, projectPath);
    }

    private McpProtocol.ToolResult healDocker(Map<String, Object> arguments) {
        String projectPath = (String) arguments.getOrDefault("projectPath", ".");

        StringBuilder report = new StringBuilder();
        report.append("# Auto-Heal Report — Docker\n\n");
        report.append("**Project**: `%s`\n\n".formatted(projectPath));

        // Step 1: Monitor
        report.append("## 1. Container Status Check\n\n");
        LogMonitor.ContainerStatusReport containerReport = logMonitor.checkDockerContainers(projectPath);

        if (containerReport.error() != null) {
            report.append("Error: %s\n\n".formatted(containerReport.error()));
            report.append("---\n*Generated by Vibe-Ops Self-Healing Agent v0.1.0*\n");
            return McpProtocol.ToolResult.success(report.toString());
        }

        if (containerReport.containers().isEmpty()) {
            report.append("No containers found.\n\n");
            report.append("---\n*Generated by Vibe-Ops Self-Healing Agent v0.1.0*\n");
            return McpProtocol.ToolResult.success(report.toString());
        }

        report.append("| Container | Status | State | Health |\n");
        report.append("|-----------|--------|-------|--------|\n");
        for (LogMonitor.ContainerInfo c : containerReport.containers()) {
            String icon = c.unhealthy() ? "FAIL" : "OK";
            report.append("| %s | %s | %s | %s |\n".formatted(
                    c.name(), c.status(), c.state(), icon));
        }
        report.append("\n");

        if (containerReport.healthy()) {
            report.append("## Result: ALL HEALTHY\n\n");
            report.append("No unhealthy containers detected.\n\n");
            report.append("---\n*Generated by Vibe-Ops Self-Healing Agent v0.1.0*\n");
            return McpProtocol.ToolResult.success(report.toString());
        }

        String combinedLogs = containerReport.containers().stream()
                .filter(LogMonitor.ContainerInfo::unhealthy)
                .map(c -> c.recentLogs() != null ? c.recentLogs() : "")
                .collect(Collectors.joining("\n---\n"));

        return analyzeAndReport(report, combinedLogs, projectPath);
    }

    private McpProtocol.ToolResult healFromLogs(Map<String, Object> arguments) {
        String logs = (String) arguments.get("logs");
        String projectPath = (String) arguments.getOrDefault("projectPath", "");

        if (logs == null || logs.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'logs' is required for source='logs'.");
        }

        StringBuilder report = new StringBuilder();
        report.append("# Auto-Heal Report — Log Analysis\n\n");

        return analyzeAndReport(report, logs, projectPath);
    }

    private McpProtocol.ToolResult analyzeAndReport(StringBuilder report, String logs, String projectPath) {
        long startTime = System.currentTimeMillis();
        String mode = healConfig.getMode();

        report.append("**Mode**: `%s`\n\n".formatted(mode));

        // Step 2: Analyze errors
        report.append("## 2. Error Analysis\n\n");
        ErrorAnalyzer.AnalysisResult analysis = errorAnalyzer.analyze(logs);

        if (analysis.errors().isEmpty()) {
            report.append("No known error patterns detected in logs.\n\n");
            if (!analysis.stackTraces().isEmpty()) {
                report.append("Found **%d stack trace(s)** — manual review recommended.\n\n".formatted(
                        analysis.stackTraces().size()));
                for (String trace : analysis.stackTraces()) {
                    report.append("```\n").append(truncate(trace, 600)).append("\n```\n\n");
                }
            }
            writeAudit(mode, "No errors detected", null, null, "proposed", startTime);
            report.append("---\n*Generated by Vibe-Ops Self-Healing Agent v0.1.0*\n");
            return McpProtocol.ToolResult.success(report.toString());
        }

        report.append("**Severity**: %s\n".formatted(analysis.severity()));
        report.append("**Errors found**: %d\n\n".formatted(analysis.errors().size()));

        for (ErrorAnalyzer.MatchedError error : analysis.errors()) {
            report.append("- **%s** (%s): %s\n".formatted(
                    error.pattern().id(), error.pattern().category(), error.pattern().description()));
        }
        report.append("\n");

        // Step 3: Repair plan
        report.append("## 3. Repair Plan\n\n");
        HealingStrategyEngine.RepairPlan plan = strategyEngine.createRepairPlan(analysis);

        for (int i = 0; i < plan.actions().size(); i++) {
            HealingStrategyEngine.RepairAction action = plan.actions().get(i);
            String autoTag = action.automatable() ? " [AUTO]" : " [MANUAL]";
            report.append("### Step %d: %s%s\n\n".formatted(i + 1, action.title(), autoTag));
            report.append("%s\n\n".formatted(action.description()));
            report.append("```bash\n");
            for (String cmd : action.commands()) {
                report.append("%s\n".formatted(cmd));
            }
            report.append("```\n\n");
        }

        // Step 4: AI Repair Prompt
        String repairPrompt = null;
        if (plan.requiresCodeChange()) {
            report.append("## 4. AI Repair Prompt\n\n");
            repairPrompt = errorAnalyzer.buildRepairPrompt(analysis, projectPath);
            report.append("```\n").append(truncate(repairPrompt, 3000)).append("\n```\n\n");
        }

        // Mode-specific behavior
        String auditStatus = "proposed";
        if ("propose-only".equals(mode)) {
            report.append("## Execution Mode: PROPOSE ONLY\n\n");
            report.append("> `dry_run: true` — No files were modified. Review the plan above and apply manually.\n\n");
        } else if ("semi-auto".equals(mode)) {
            report.append("## Execution Mode: SEMI-AUTO\n\n");
            report.append("> A repair branch `vibeops-heal/%s` should be created with the proposed fixes.\n".formatted(
                    Instant.now().getEpochSecond()));
            report.append("> Review and merge manually after validation.\n\n");
            auditStatus = "applied";
        } else if ("full-auto".equals(mode)) {
            report.append("## Execution Mode: FULL-AUTO\n\n");
            report.append("> Fixes will be applied, tests executed, and auto-rolled back on failure.\n\n");
            auditStatus = "applied";
        }

        // Summary
        report.append("## Summary\n\n");
        report.append("| Metric | Value |\n");
        report.append("|--------|-------|\n");
        report.append("| Mode | %s |\n".formatted(mode));
        report.append("| Severity | %s |\n".formatted(analysis.severity()));
        report.append("| Errors Detected | %d |\n".formatted(analysis.errors().size()));
        report.append("| Stack Traces | %d |\n".formatted(analysis.stackTraces().size()));
        report.append("| Repair Actions | %d |\n".formatted(plan.actions().size()));
        report.append("| Automatable | %s |\n".formatted(plan.hasAutomatableActions() ? "Yes" : "No"));
        report.append("| Requires Code Fix | %s |\n\n".formatted(plan.requiresCodeChange() ? "Yes" : "No"));

        // Write audit log
        String diagnosis = analysis.errors().stream()
                .map(e -> e.pattern().id() + ": " + e.pattern().description())
                .collect(Collectors.joining("; "));
        writeAudit(mode, diagnosis, repairPrompt, null, auditStatus, startTime);

        report.append("---\n*Generated by Vibe-Ops Self-Healing Agent v0.1.0*\n");
        return McpProtocol.ToolResult.success(report.toString());
    }

    private void writeAudit(String mode, String diagnosis, String fixPrompt,
                            String fixCodeSnapshot, String status, long startTime) {
        try {
            auditLogger.logEntry(new AuditLogger.AuditEntry(
                    Instant.now().toString(), mode, "mcp-tool", diagnosis,
                    fixPrompt, fixCodeSnapshot, status,
                    System.currentTimeMillis() - startTime
            ));
        } catch (Exception e) {
            log.warn("Failed to write audit log: {}", e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "\n... (truncated)";
    }
}
