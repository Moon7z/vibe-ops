package com.opszen.heal;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Maps error categories to concrete healing actions.
 * Produces actionable repair plans that can be executed automatically
 * or fed to Claude Code for intelligent code fixes.
 */
@Component
public class HealingStrategyEngine {

    /**
     * Generate a repair plan based on error analysis.
     */
    public RepairPlan createRepairPlan(ErrorAnalyzer.AnalysisResult analysis) {
        List<RepairAction> actions = new ArrayList<>();
        Set<String> addedActions = new HashSet<>();

        for (ErrorAnalyzer.MatchedError error : analysis.errors()) {
            List<RepairAction> errorActions = mapToActions(error);
            for (RepairAction action : errorActions) {
                if (addedActions.add(action.id())) {
                    actions.add(action);
                }
            }
        }

        // Sort by priority (lower = more urgent)
        actions.sort(Comparator.comparingInt(RepairAction::priority));

        boolean automatable = actions.stream().anyMatch(RepairAction::automatable);
        boolean requiresCodeChange = actions.stream().anyMatch(a -> a.type() == ActionType.CODE_FIX);

        return new RepairPlan(actions, analysis.severity(), automatable, requiresCodeChange);
    }

    private List<RepairAction> mapToActions(ErrorAnalyzer.MatchedError error) {
        return switch (error.pattern().category()) {
            case RESOURCE -> List.of(
                    new RepairAction("SCALE_MEMORY", ActionType.INFRA_FIX, 1,
                            "Increase memory limits",
                            "Increase container memory limits in K8s manifest or docker-compose",
                            List.of(
                                    "kubectl set resources deployment/<name> --limits=memory=1Gi",
                                    "Update Dockerfile: ENV JAVA_OPTS=\"-Xmx768m\"",
                                    "Update K8s manifest: resources.limits.memory: 1Gi"
                            ), true),
                    new RepairAction("PROFILE_MEMORY", ActionType.DIAGNOSTIC, 2,
                            "Profile memory usage",
                            "Capture heap dump and analyze for memory leaks",
                            List.of(
                                    "kubectl exec <pod> -- jcmd 1 GC.heap_dump /tmp/heapdump.hprof",
                                    "Add JVM flag: -XX:+HeapDumpOnOutOfMemoryError"
                            ), false)
            );

            case CONNECTIVITY -> List.of(
                    new RepairAction("CHECK_CONNECTIVITY", ActionType.DIAGNOSTIC, 1,
                            "Verify network connectivity",
                            "Test connectivity to dependent services",
                            List.of(
                                    "kubectl exec <pod> -- curl -v <service-host>:<port>",
                                    "Check Service/Endpoint objects: kubectl get endpoints",
                                    "Verify DNS resolution: kubectl exec <pod> -- nslookup <host>"
                            ), false),
                    new RepairAction("FIX_CONNECTION_CONFIG", ActionType.CONFIG_FIX, 2,
                            "Fix connection configuration",
                            "Update connection strings, credentials, or timeouts",
                            List.of(
                                    "Verify SPRING_DATASOURCE_URL environment variable",
                                    "Check ConfigMap/Secret values",
                                    "Increase connection pool timeout settings"
                            ), true)
            );

            case CONFIGURATION -> List.of(
                    new RepairAction("FIX_CONFIG", ActionType.CONFIG_FIX, 1,
                            "Fix missing/invalid configuration",
                            "Set required environment variables or config properties",
                            List.of(
                                    "Check application.properties for missing values",
                                    "Verify all required env vars are set in deployment manifest",
                                    "Review ConfigMap/Secret for completeness"
                            ), true),
                    new RepairAction("FIX_BEANS", ActionType.CODE_FIX, 2,
                            "Fix Spring bean wiring",
                            "Resolve dependency injection failures",
                            List.of(
                                    "Add missing @Component/@Service annotations",
                                    "Check @Autowired/@Inject targets",
                                    "Review @Configuration class imports"
                            ), false)
            );

            case CODE_BUG -> List.of(
                    new RepairAction("FIX_CODE_BUG", ActionType.CODE_FIX, 1,
                            "Fix code bug",
                            "Apply code fix for the detected error pattern",
                            List.of(
                                    "Review stack trace for root cause",
                                    "Apply null checks or input validation",
                                    "Fix recursive/infinite loop patterns"
                            ), false)
            );

            case DEPLOYMENT -> List.of(
                    new RepairAction("FIX_IMAGE", ActionType.INFRA_FIX, 1,
                            "Fix container image",
                            "Resolve image pull or build failures",
                            List.of(
                                    "Verify image tag exists in registry",
                                    "Check registry authentication (imagePullSecrets)",
                                    "Rebuild and push image: docker build -t <tag> . && docker push <tag>"
                            ), true)
            );

            case RUNTIME -> List.of(
                    new RepairAction("FIX_HEALTH_CHECK", ActionType.CONFIG_FIX, 1,
                            "Fix health check / startup",
                            "Adjust probe configuration or fix startup issues",
                            List.of(
                                    "Increase initialDelaySeconds in readiness/liveness probes",
                                    "Verify /actuator/health endpoint is accessible",
                                    "Check application startup time and resource constraints"
                            ), true),
                    new RepairAction("RESTART_POD", ActionType.RESTART, 2,
                            "Rolling restart",
                            "Perform a rolling restart after configuration changes",
                            List.of(
                                    "kubectl rollout restart deployment/<name>",
                                    "docker compose restart <service>"
                            ), true)
            );

            case SECURITY -> List.of(
                    new RepairAction("FIX_AUTH", ActionType.CONFIG_FIX, 1,
                            "Fix authentication/authorization",
                            "Update credentials or security configuration",
                            List.of(
                                    "Rotate API keys/tokens",
                                    "Update Secret objects with valid credentials",
                                    "Review RBAC role bindings"
                            ), true)
            );

            case DATA -> List.of(
                    new RepairAction("FIX_MIGRATION", ActionType.CODE_FIX, 1,
                            "Fix database migration",
                            "Resolve migration script failures",
                            List.of(
                                    "Review failed migration script",
                                    "Check Flyway/Liquibase migration state",
                                    "Consider manual repair: flyway repair"
                            ), false)
            );

            case DEPENDENCY -> List.of(
                    new RepairAction("FIX_DEPS", ActionType.CODE_FIX, 1,
                            "Fix dependency issue",
                            "Resolve missing or incompatible dependencies",
                            List.of(
                                    "Run mvn dependency:tree to inspect dependency graph",
                                    "Check for version conflicts",
                                    "Verify all required JARs are in the classpath"
                            ), false)
            );
        };
    }

    // ── Types ──

    public enum ActionType {
        CODE_FIX,      // Requires source code change → delegate to Claude Code
        CONFIG_FIX,    // Configuration/env var change → can be automated
        INFRA_FIX,     // Infrastructure change (K8s manifest, Dockerfile)
        RESTART,       // Service restart
        DIAGNOSTIC     // Needs investigation before fix
    }

    public record RepairAction(
            String id,
            ActionType type,
            int priority,
            String title,
            String description,
            List<String> commands,
            boolean automatable
    ) {}

    public record RepairPlan(
            List<RepairAction> actions,
            ErrorAnalyzer.Severity severity,
            boolean hasAutomatableActions,
            boolean requiresCodeChange
    ) {}
}
