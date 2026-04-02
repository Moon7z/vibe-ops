package com.opszen.heal;

import com.opszen.mcp.McpProtocol;
import com.opszen.mcp.McpTool;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class RollbackTool implements McpTool {

    private final BackupManager backupManager;

    public RollbackTool(BackupManager backupManager) {
        this.backupManager = backupManager;
    }

    @Override
    public String name() { return "rollback"; }

    @Override
    public String description() {
        return "Rollback files to a previous backup snapshot created by auto-heal. "
             + "Use 'list' action to see available backups, or 'restore' with a timestamp to rollback. "
             + "Also supports 'clean' to remove expired backups.";
    }

    @Override
    public McpProtocol.InputSchema inputSchema() {
        return new McpProtocol.InputSchema("object", Map.of(
                "action", new McpProtocol.PropertyDef("string",
                        "Action: 'list', 'restore', or 'clean'", null),
                "timestamp", new McpProtocol.PropertyDef("string",
                        "Backup timestamp to restore (required for action='restore')", null),
                "projectPath", new McpProtocol.PropertyDef("string",
                        "Project root path for restoring files (required for action='restore')", null)
        ), List.of("action"));
    }

    @Override
    public McpProtocol.ToolResult execute(Map<String, Object> arguments) {
        String action = (String) arguments.get("action");
        if (action == null || action.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'action' is required (list, restore, or clean).");
        }

        return switch (action.toLowerCase()) {
            case "list" -> listBackups();
            case "restore" -> restore(arguments);
            case "clean" -> clean();
            default -> McpProtocol.ToolResult.error("Invalid action: '%s'. Use 'list', 'restore', or 'clean'.".formatted(action));
        };
    }

    private McpProtocol.ToolResult listBackups() {
        List<String> backups = backupManager.listBackups();
        if (backups.isEmpty()) {
            return McpProtocol.ToolResult.success("No backups found.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Available Backups\n\n");
        for (String ts : backups) {
            sb.append("- `%s`\n".formatted(ts));
        }
        sb.append("\nUse `action: 'restore', timestamp: '<value>'` to rollback.\n");
        return McpProtocol.ToolResult.success(sb.toString());
    }

    private McpProtocol.ToolResult restore(Map<String, Object> arguments) {
        String timestamp = (String) arguments.get("timestamp");
        String projectPath = (String) arguments.get("projectPath");
        if (timestamp == null || timestamp.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'timestamp' is required for restore.");
        }
        if (projectPath == null || projectPath.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'projectPath' is required for restore.");
        }
        try {
            boolean ok = backupManager.rollbackTo(timestamp, Path.of(projectPath));
            if (ok) {
                return McpProtocol.ToolResult.success("Rollback to `%s` completed successfully.".formatted(timestamp));
            } else {
                return McpProtocol.ToolResult.error("Backup '%s' not found or has no manifest.".formatted(timestamp));
            }
        } catch (Exception e) {
            return McpProtocol.ToolResult.error("Rollback failed: " + e.getMessage());
        }
    }

    private McpProtocol.ToolResult clean() {
        int cleaned = backupManager.cleanExpired();
        return McpProtocol.ToolResult.success("Cleaned %d expired backup(s).".formatted(cleaned));
    }
}
