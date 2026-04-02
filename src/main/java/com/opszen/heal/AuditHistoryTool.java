package com.opszen.heal;

import com.opszen.mcp.McpProtocol;
import com.opszen.mcp.McpTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AuditHistoryTool implements McpTool {

    private final AuditLogger auditLogger;

    public AuditHistoryTool(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Override
    public String name() { return "audit-history"; }

    @Override
    public String description() {
        return "Query the auto-heal audit log history. Returns recent heal operations with timestamps, "
             + "modes, diagnoses, statuses, and durations.";
    }

    @Override
    public McpProtocol.InputSchema inputSchema() {
        return new McpProtocol.InputSchema("object", Map.of(
                "limit", new McpProtocol.PropertyDef("string", "Max entries to return (default: 20)", null)
        ), List.of());
    }

    @Override
    public McpProtocol.ToolResult execute(Map<String, Object> arguments) {
        int limit = 20;
        if (arguments.containsKey("limit")) {
            try { limit = Integer.parseInt(String.valueOf(arguments.get("limit"))); }
            catch (NumberFormatException ignored) {}
        }

        List<Map<String, Object>> history = auditLogger.queryHistory(limit);
        if (history.isEmpty()) {
            return McpProtocol.ToolResult.success("No audit history found.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Audit History (%d entries)\n\n".formatted(history.size()));
        sb.append("| Timestamp | Mode | Status | Diagnosis | Duration |\n");
        sb.append("|-----------|------|--------|-----------|----------|\n");
        for (Map<String, Object> entry : history) {
            String ts = String.valueOf(entry.getOrDefault("timestamp", "-"));
            String mode = String.valueOf(entry.getOrDefault("mode", "-"));
            String status = String.valueOf(entry.getOrDefault("status", "-"));
            String diag = String.valueOf(entry.getOrDefault("diagnosis", "-"));
            if (diag.length() > 60) diag = diag.substring(0, 57) + "...";
            String dur = String.valueOf(entry.getOrDefault("durationMs", "-"));
            sb.append("| %s | %s | %s | %s | %sms |\n".formatted(ts, mode, status, diag, dur));
        }
        return McpProtocol.ToolResult.success(sb.toString());
    }
}
