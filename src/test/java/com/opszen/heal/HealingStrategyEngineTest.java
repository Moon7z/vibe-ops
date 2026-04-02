package com.opszen.heal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HealingStrategyEngine Tests")
class HealingStrategyEngineTest {

    private final ErrorAnalyzer analyzer = new ErrorAnalyzer();
    private final HealingStrategyEngine engine = new HealingStrategyEngine();

    @Test
    @DisplayName("should generate repair plan for OOM error")
    void should_generatePlanForOOM() {
        var analysis = analyzer.analyze("java.lang.OutOfMemoryError: Java heap space");
        var plan = engine.createRepairPlan(analysis);

        assertFalse(plan.actions().isEmpty());
        assertTrue(plan.hasAutomatableActions());
        assertEquals(ErrorAnalyzer.Severity.CRITICAL, plan.severity());

        assertTrue(plan.actions().stream()
                .anyMatch(a -> a.id().equals("SCALE_MEMORY")));
    }

    @Test
    @DisplayName("should generate code fix plan for NPE")
    void should_generateCodeFixForNPE() {
        var analysis = analyzer.analyze("""
                java.lang.NullPointerException
                    at com.example.Service.process(Service.java:55)
                """);
        var plan = engine.createRepairPlan(analysis);

        assertTrue(plan.requiresCodeChange());
        assertTrue(plan.actions().stream()
                .anyMatch(a -> a.type() == HealingStrategyEngine.ActionType.CODE_FIX));
    }

    @Test
    @DisplayName("should generate config fix for missing placeholder")
    void should_generateConfigFix() {
        var analysis = analyzer.analyze("Could not resolve placeholder 'DB_URL'");
        var plan = engine.createRepairPlan(analysis);

        assertTrue(plan.actions().stream()
                .anyMatch(a -> a.type() == HealingStrategyEngine.ActionType.CONFIG_FIX));
    }

    @Test
    @DisplayName("should generate infra fix for image pull failure")
    void should_generateInfraFix() {
        var analysis = analyzer.analyze("ImagePullBackOff for image myapp:latest");
        var plan = engine.createRepairPlan(analysis);

        assertTrue(plan.actions().stream()
                .anyMatch(a -> a.type() == HealingStrategyEngine.ActionType.INFRA_FIX));
    }

    @Test
    @DisplayName("should sort actions by priority")
    void should_sortByPriority() {
        var analysis = analyzer.analyze("""
                java.lang.OutOfMemoryError: heap space
                Communications link failure
                """);
        var plan = engine.createRepairPlan(analysis);

        for (int i = 1; i < plan.actions().size(); i++) {
            assertTrue(plan.actions().get(i).priority() >= plan.actions().get(i - 1).priority(),
                    "Actions should be sorted by priority");
        }
    }

    @Test
    @DisplayName("should deduplicate actions")
    void should_deduplicateActions() {
        var analysis = analyzer.analyze("""
                java.lang.OutOfMemoryError: heap space
                java.lang.OutOfMemoryError: GC overhead limit exceeded
                """);
        var plan = engine.createRepairPlan(analysis);

        long uniqueIds = plan.actions().stream()
                .map(HealingStrategyEngine.RepairAction::id)
                .distinct()
                .count();
        assertEquals(plan.actions().size(), uniqueIds, "No duplicate action IDs");
    }

    @Test
    @DisplayName("should handle empty analysis")
    void should_handleEmptyAnalysis() {
        var analysis = new ErrorAnalyzer.AnalysisResult(
                List.of(), List.of(), ErrorAnalyzer.Severity.UNKNOWN, null);
        var plan = engine.createRepairPlan(analysis);

        assertTrue(plan.actions().isEmpty());
        assertFalse(plan.hasAutomatableActions());
        assertFalse(plan.requiresCodeChange());
    }
}
