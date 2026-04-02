package com.opszen.heal;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses error logs and stack traces to extract structured failure information.
 * Classifies errors and maps them to healing strategies.
 */
@Component
public class ErrorAnalyzer {

    private static final List<ErrorPattern> PATTERNS = List.of(
            // JVM / Runtime
            new ErrorPattern("OOM", ErrorCategory.RESOURCE,
                    Pattern.compile("(OutOfMemoryError|OOMKilled|Cannot allocate memory|java\\.lang\\.OutOfMemoryError)", Pattern.CASE_INSENSITIVE),
                    "Application ran out of memory",
                    List.of("Increase JVM heap: -Xmx", "Increase container memory limits", "Check for memory leaks")),

            new ErrorPattern("STACK_OVERFLOW", ErrorCategory.CODE_BUG,
                    Pattern.compile("StackOverflowError", Pattern.CASE_INSENSITIVE),
                    "Infinite recursion or stack overflow",
                    List.of("Check for infinite recursion", "Increase -Xss if legitimately deep stack")),

            // Database
            new ErrorPattern("DB_CONNECTION", ErrorCategory.CONNECTIVITY,
                    Pattern.compile("(Connection refused|Unable to acquire JDBC|Cannot create PoolableConnectionFactory|Communications link failure)", Pattern.CASE_INSENSITIVE),
                    "Database connection failure",
                    List.of("Verify database host/port", "Check database credentials", "Ensure DB is running")),

            new ErrorPattern("DB_MIGRATION", ErrorCategory.DATA,
                    Pattern.compile("(FlywayException|LiquibaseException|Migration.*failed|Schema.*mismatch)", Pattern.CASE_INSENSITIVE),
                    "Database migration failure",
                    List.of("Review migration scripts", "Check database schema state", "Consider rollback")),

            // Spring Boot
            new ErrorPattern("BEAN_CREATION", ErrorCategory.CONFIGURATION,
                    Pattern.compile("(BeanCreationException|UnsatisfiedDependencyException|NoSuchBeanDefinitionException)", Pattern.CASE_INSENSITIVE),
                    "Spring bean wiring failure",
                    List.of("Check @Component/@Service annotations", "Verify dependency injection", "Review @Configuration classes")),

            new ErrorPattern("PORT_CONFLICT", ErrorCategory.CONFIGURATION,
                    Pattern.compile("(Address already in use|Port.*already.*bound|BindException)", Pattern.CASE_INSENSITIVE),
                    "Port binding conflict",
                    List.of("Change server.port", "Kill process on conflicting port", "Check container port mappings")),

            new ErrorPattern("MISSING_CONFIG", ErrorCategory.CONFIGURATION,
                    Pattern.compile("(Could not resolve placeholder|Missing required property|ConfigurationException)", Pattern.CASE_INSENSITIVE),
                    "Missing configuration property",
                    List.of("Set missing environment variables", "Check application.properties/yml", "Verify config map/secrets")),

            // Docker / K8s
            new ErrorPattern("IMAGE_PULL", ErrorCategory.DEPLOYMENT,
                    Pattern.compile("(ImagePullBackOff|ErrImagePull|manifest unknown|unauthorized.*registry)", Pattern.CASE_INSENSITIVE),
                    "Container image pull failure",
                    List.of("Verify image name and tag", "Check registry credentials", "Ensure image exists in registry")),

            new ErrorPattern("CRASH_LOOP", ErrorCategory.RUNTIME,
                    Pattern.compile("(CrashLoopBackOff|Back-off restarting failed container)", Pattern.CASE_INSENSITIVE),
                    "Container crash loop detected",
                    List.of("Check application startup logs", "Verify health check endpoints", "Review resource limits")),

            new ErrorPattern("READINESS_PROBE", ErrorCategory.RUNTIME,
                    Pattern.compile("(Readiness probe failed|Liveness probe failed|health check.*failed)", Pattern.CASE_INSENSITIVE),
                    "Health check probe failure",
                    List.of("Verify /actuator/health endpoint", "Increase probe timeouts", "Check application startup time")),

            // Security / Auth
            new ErrorPattern("AUTH_FAILURE", ErrorCategory.SECURITY,
                    Pattern.compile("(401 Unauthorized|403 Forbidden|AccessDeniedException|AuthenticationException)", Pattern.CASE_INSENSITIVE),
                    "Authentication or authorization failure",
                    List.of("Verify API keys/tokens", "Check RBAC permissions", "Review security configuration")),

            // Generic Java
            new ErrorPattern("NPE", ErrorCategory.CODE_BUG,
                    Pattern.compile("NullPointerException", Pattern.CASE_INSENSITIVE),
                    "Null pointer exception",
                    List.of("Add null checks", "Review Optional usage", "Check initialization order")),

            new ErrorPattern("CLASS_NOT_FOUND", ErrorCategory.DEPENDENCY,
                    Pattern.compile("(ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError)", Pattern.CASE_INSENSITIVE),
                    "Missing class or incompatible dependency",
                    List.of("Check dependency versions", "Run mvn dependency:tree", "Verify classpath"))
    );

    /**
     * Analyze log text and extract all matching error patterns.
     */
    public AnalysisResult analyze(String logText) {
        if (logText == null || logText.isBlank()) {
            return new AnalysisResult(List.of(), List.of(), Severity.UNKNOWN, null);
        }

        List<MatchedError> matchedErrors = new ArrayList<>();
        Set<String> seenPatterns = new HashSet<>();

        for (ErrorPattern pattern : PATTERNS) {
            Matcher matcher = pattern.pattern().matcher(logText);
            while (matcher.find()) {
                if (seenPatterns.add(pattern.id())) {
                    // Extract surrounding context (±2 lines)
                    String context = extractContext(logText, matcher.start(), 2);
                    matchedErrors.add(new MatchedError(pattern, matcher.group(), context));
                }
            }
        }

        // Extract stack traces
        List<String> stackTraces = extractStackTraces(logText);

        // Determine overall severity
        Severity severity = determineSeverity(matchedErrors);

        // Determine primary error (first critical, or first match)
        String primaryError = matchedErrors.isEmpty() ? null : matchedErrors.get(0).pattern().id();

        return new AnalysisResult(matchedErrors, stackTraces, severity, primaryError);
    }

    /**
     * Generate a Claude-ready repair prompt from the analysis.
     */
    public String buildRepairPrompt(AnalysisResult analysis, String projectContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a senior DevOps engineer. Diagnose and fix the following production failure.\n\n");

        prompt.append("## Error Summary\n\n");
        for (MatchedError error : analysis.errors()) {
            prompt.append("### %s — %s\n".formatted(error.pattern().id(), error.pattern().description()));
            prompt.append("- **Category**: %s\n".formatted(error.pattern().category()));
            prompt.append("- **Matched**: `%s`\n".formatted(error.matchedText()));
            prompt.append("- **Suggested Fixes**:\n");
            for (String fix : error.pattern().suggestions()) {
                prompt.append("  - %s\n".formatted(fix));
            }
            prompt.append("\n**Context**:\n```\n%s\n```\n\n".formatted(error.context()));
        }

        if (!analysis.stackTraces().isEmpty()) {
            prompt.append("## Stack Traces\n\n");
            for (String trace : analysis.stackTraces()) {
                prompt.append("```\n%s\n```\n\n".formatted(truncate(trace, 1500)));
            }
        }

        if (projectContext != null && !projectContext.isBlank()) {
            prompt.append("## Project Context\n\n%s\n\n".formatted(projectContext));
        }

        prompt.append("## Instructions\n\n");
        prompt.append("1. Identify the root cause from the error patterns and stack traces above\n");
        prompt.append("2. Provide the exact code or configuration changes needed to fix the issue\n");
        prompt.append("3. Include any infrastructure changes (Dockerfile, K8s manifest, env vars)\n");
        prompt.append("4. Suggest a verification step to confirm the fix works\n");

        return prompt.toString();
    }

    private String extractContext(String text, int matchPos, int contextLines) {
        String[] lines = text.split("\n");
        int charCount = 0;
        int matchLine = 0;

        for (int i = 0; i < lines.length; i++) {
            charCount += lines[i].length() + 1;
            if (charCount > matchPos) {
                matchLine = i;
                break;
            }
        }

        int start = Math.max(0, matchLine - contextLines);
        int end = Math.min(lines.length, matchLine + contextLines + 1);

        StringBuilder ctx = new StringBuilder();
        for (int i = start; i < end; i++) {
            ctx.append(lines[i]).append("\n");
        }
        return ctx.toString().trim();
    }

    private List<String> extractStackTraces(String logText) {
        List<String> traces = new ArrayList<>();
        Pattern tracePattern = Pattern.compile(
                "((?:Exception|Error)[^\n]*\n(?:\\s+at [^\n]+\n)+(?:\\s+(?:Caused by|\\.\\.\\. \\d+ more)[^\n]*\n)*)",
                Pattern.MULTILINE
        );

        Matcher matcher = tracePattern.matcher(logText);
        while (matcher.find() && traces.size() < 5) {
            traces.add(matcher.group(1).trim());
        }
        return traces;
    }

    private Severity determineSeverity(List<MatchedError> errors) {
        if (errors.isEmpty()) return Severity.UNKNOWN;

        for (MatchedError e : errors) {
            ErrorCategory cat = e.pattern().category();
            if (cat == ErrorCategory.RESOURCE || cat == ErrorCategory.SECURITY) {
                return Severity.CRITICAL;
            }
        }

        for (MatchedError e : errors) {
            ErrorCategory cat = e.pattern().category();
            if (cat == ErrorCategory.CONNECTIVITY || cat == ErrorCategory.RUNTIME || cat == ErrorCategory.DEPLOYMENT) {
                return Severity.HIGH;
            }
        }

        return Severity.MEDIUM;
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "\n... (truncated)";
    }

    // ── Types ──

    public enum ErrorCategory {
        RESOURCE, CODE_BUG, CONNECTIVITY, DATA, CONFIGURATION, DEPLOYMENT, RUNTIME, SECURITY, DEPENDENCY
    }

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, UNKNOWN }

    public record ErrorPattern(
            String id,
            ErrorCategory category,
            Pattern pattern,
            String description,
            List<String> suggestions
    ) {}

    public record MatchedError(ErrorPattern pattern, String matchedText, String context) {}

    public record AnalysisResult(
            List<MatchedError> errors,
            List<String> stackTraces,
            Severity severity,
            String primaryError
    ) {}
}
