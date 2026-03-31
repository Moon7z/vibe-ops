package com.vibeops.scan;

import com.vibeops.mcp.McpProtocol;
import com.vibeops.mcp.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * run-vibe-check: Static code scanner for common production anti-patterns.
 * Scans Java source files for hardcoded secrets, missing error handling,
 * TODO/FIXME markers, and other quality issues.
 */
@Component
public class RunVibeCheckTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(RunVibeCheckTool.class);

    private static final List<ScanRule> RULES = List.of(
            // Security
            new ScanRule("SEC-001", "CRITICAL", "Hardcoded Secret",
                    Pattern.compile("(password|secret|api[_-]?key|token)\\s*=\\s*\"[^\"]+\"", Pattern.CASE_INSENSITIVE)),
            new ScanRule("SEC-002", "HIGH", "SQL Injection Risk",
                    Pattern.compile("\"\\s*SELECT.*\\+\\s*\\w+|String\\.format\\(\"\\s*SELECT", Pattern.CASE_INSENSITIVE)),
            new ScanRule("SEC-003", "HIGH", "Disabled Security",
                    Pattern.compile("\\.csrf\\(\\)\\s*\\.disable\\(\\)|@SuppressWarnings.*security", Pattern.CASE_INSENSITIVE)),

            // Reliability
            new ScanRule("REL-001", "HIGH", "Empty Catch Block",
                    Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")),
            new ScanRule("REL-002", "MEDIUM", "Generic Exception Catch",
                    Pattern.compile("catch\\s*\\(\\s*Exception\\s+\\w+\\s*\\)")),
            new ScanRule("REL-003", "MEDIUM", "System.out/err Usage",
                    Pattern.compile("System\\.(out|err)\\.(print|println)")),
            new ScanRule("REL-004", "LOW", "TODO/FIXME/HACK Marker",
                    Pattern.compile("//\\s*(TODO|FIXME|HACK|XXX|TEMP)\\b", Pattern.CASE_INSENSITIVE)),

            // Performance
            new ScanRule("PERF-001", "MEDIUM", "Thread.sleep Usage",
                    Pattern.compile("Thread\\.sleep\\(")),
            new ScanRule("PERF-002", "MEDIUM", "Synchronized Block in Virtual Thread Context",
                    Pattern.compile("synchronized\\s*\\(")),

            // Quality
            new ScanRule("QUAL-001", "LOW", "Magic Number",
                    Pattern.compile("(?<![\\w.])\\d{3,}(?![\\w.L])")),
            new ScanRule("QUAL-002", "MEDIUM", "Wildcard Import",
                    Pattern.compile("import\\s+[\\w.]+\\.\\*;"))
    );

    @Override
    public String name() {
        return "run-vibe-check";
    }

    @Override
    public String description() {
        return "Performs a static code scan on Java source files in the specified directory. " +
               "Detects hardcoded secrets, SQL injection risks, empty catch blocks, TODO markers, " +
               "and other production anti-patterns. Returns a structured Markdown report with findings.";
    }

    @Override
    public McpProtocol.InputSchema inputSchema() {
        return new McpProtocol.InputSchema(
                "object",
                Map.of(
                        "path", new McpProtocol.PropertyDef("string",
                                "Absolute path to the directory or file to scan", null),
                        "severity", new McpProtocol.PropertyDef("string",
                                "Minimum severity to report: CRITICAL, HIGH, MEDIUM, LOW (default: LOW)", null)
                ),
                List.of("path")
        );
    }

    @Override
    public McpProtocol.ToolResult execute(Map<String, Object> arguments) {
        String pathStr = (String) arguments.get("path");
        String minSeverity = (String) arguments.getOrDefault("severity", "LOW");

        if (pathStr == null || pathStr.isBlank()) {
            return McpProtocol.ToolResult.error("Parameter 'path' is required.");
        }

        Path scanPath = Path.of(pathStr);
        if (!Files.exists(scanPath)) {
            return McpProtocol.ToolResult.error("Path does not exist: " + pathStr);
        }

        int minLevel = severityLevel(minSeverity);
        List<Finding> findings = new ArrayList<>();

        try {
            List<Path> javaFiles = collectJavaFiles(scanPath);
            for (Path file : javaFiles) {
                scanFile(file, findings, minLevel);
            }
        } catch (IOException e) {
            log.error("Scan error", e);
            return McpProtocol.ToolResult.error("Scan failed: " + e.getMessage());
        }

        String report = generateReport(scanPath, findings, minSeverity);
        return McpProtocol.ToolResult.success(report);
    }

    private List<Path> collectJavaFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(root) && root.toString().endsWith(".java")) {
            files.add(root);
            return files;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.equals("target") || name.equals(".git") || name.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private void scanFile(Path file, List<Finding> findings, int minLevel) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (ScanRule rule : RULES) {
                if (severityLevel(rule.severity()) >= minLevel &&
                    rule.pattern().matcher(line).find()) {
                    findings.add(new Finding(rule, file, i + 1, line.trim()));
                }
            }
        }
    }

    private String generateReport(Path scanPath, List<Finding> findings, String minSeverity) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Vibe-Check Scan Report\n\n");
        sb.append("**Scanned**: `%s`\n".formatted(scanPath));
        sb.append("**Minimum Severity**: %s\n\n".formatted(minSeverity));

        if (findings.isEmpty()) {
            sb.append("## Result: ALL CLEAR\n\n");
            sb.append("No issues found. Code passes the vibe check.\n");
            return sb.toString();
        }

        // Summary by severity
        Map<String, Long> bySeverity = findings.stream()
                .collect(Collectors.groupingBy(f -> f.rule().severity(), Collectors.counting()));
        sb.append("## Summary\n\n");
        sb.append("| Severity | Count |\n");
        sb.append("|----------|-------|\n");
        for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
            long count = bySeverity.getOrDefault(sev, 0L);
            if (count > 0) {
                sb.append("| %s | %d |\n".formatted(sev, count));
            }
        }
        sb.append("\n**Total Findings**: %d\n\n".formatted(findings.size()));

        // Gate decision
        boolean hasCritical = bySeverity.containsKey("CRITICAL");
        boolean hasHigh = bySeverity.containsKey("HIGH");
        if (hasCritical) {
            sb.append("## Gate: BLOCKED\n\n");
            sb.append("Critical issues must be resolved before deployment.\n\n");
        } else if (hasHigh) {
            sb.append("## Gate: WARNING\n\n");
            sb.append("High-severity issues detected. Review required before deployment.\n\n");
        } else {
            sb.append("## Gate: PASS (with notes)\n\n");
        }

        // Detailed findings
        sb.append("## Findings\n\n");
        for (Finding f : findings) {
            sb.append("### [%s] %s — %s\n".formatted(f.rule().id(), f.rule().severity(), f.rule().label()));
            sb.append("- **File**: `%s:%d`\n".formatted(f.file(), f.line()));
            sb.append("- **Code**: `%s`\n\n".formatted(truncate(f.snippet(), 120)));
        }

        sb.append("---\n*Generated by Vibe-Ops Scanner v0.1.0*\n");
        return sb.toString();
    }

    private static int severityLevel(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private record ScanRule(String id, String severity, String label, Pattern pattern) {}
    private record Finding(ScanRule rule, Path file, int line, String snippet) {}
}
