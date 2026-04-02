package com.vibeops.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestQualityAnalyzerTest {

    private TestQualityAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new TestQualityAnalyzer();
    }

    @Test
    void should_score_base_30_for_empty_test() {
        String testCode = "";
        String sourceCode = "public class Foo {}";
        var report = analyzer.analyze(testCode, sourceCode);
        assertEquals(30, report.effectivenessScore());
        assertEquals("needs_improvement", report.qualityLevel());
    }

    @Test
    void should_detect_null_edge_case() {
        String testCode = "@Test void test_null() { assertNull(result); }";
        String sourceCode = "public class Foo {}";
        var report = analyzer.analyze(testCode, sourceCode);
        assertTrue(report.edgeCasesCovered().contains("null_input"));
    }

    @Test
    void should_detect_empty_edge_case() {
        String testCode = "@Test void test_empty() { assertTrue(list.isEmpty()); }";
        String sourceCode = "public class Foo {}";
        var report = analyzer.analyze(testCode, sourceCode);
        assertTrue(report.edgeCasesCovered().contains("empty_list"));
    }

    @Test
    void should_detect_boundary_edge_case() {
        String testCode = "@Test void test_max() { calc(Integer.MAX_VALUE); }";
        String sourceCode = "public class Foo {}";
        var report = analyzer.analyze(testCode, sourceCode);
        assertTrue(report.edgeCasesCovered().contains("max_value"));
    }

    @Test
    void should_detect_exception_paths() {
        String testCode = "assertThrows(IllegalArgumentException.class, () -> svc.run(null));";
        String sourceCode = "public void run(String s) { throw new IllegalArgumentException(); }";
        var report = analyzer.analyze(testCode, sourceCode);
        assertTrue(report.exceptionPathsTested().contains("IllegalArgumentException"));
    }

    @Test
    void should_score_good_for_comprehensive_test() {
        String testCode = """
                @Test void should_return_value() { assertEquals(1, calc(1)); assertNotNull(result); }
                @Test void should_handle_null() { assertThrows(NullPointerException.class, () -> calc(null)); }
                @Test void should_handle_empty() { assertTrue(calc("").isEmpty()); }
                @Test void should_handle_boundary() { assertEquals(Integer.MAX_VALUE, calc(Integer.MAX_VALUE)); }
                """;
        String sourceCode = "public int calc(Object o) { if(o==null) throw new NullPointerException(); return 1; }";
        var report = analyzer.analyze(testCode, sourceCode);
        assertTrue(report.effectivenessScore() >= 60);
        assertFalse(report.qualityLevel().equals("needs_improvement"));
    }

    @Test
    void should_suggest_more_assertions_when_few() {
        String testCode = "@Test void test_one() { assertTrue(true); }";
        String sourceCode = "public class Foo {}";
        var report = analyzer.analyze(testCode, sourceCode);
        assertTrue(report.suggestions().stream().anyMatch(s -> s.contains("断言")));
    }

    @Test
    void should_suggest_naming_convention() {
        String testCode = "@Test void testFoo() { assertEquals(1, 1); }";
        String sourceCode = "public class Foo {}";
        var report = analyzer.analyze(testCode, sourceCode);
        assertTrue(report.suggestions().stream().anyMatch(s -> s.contains("should_") || s.contains("when_")));
    }

    @Test
    void should_count_assertions_correctly() {
        String testCode = """
                assertEquals(1, a);
                assertTrue(b);
                assertFalse(c);
                assertNotNull(d);
                assertThrows(Exception.class, () -> {});
                """;
        String sourceCode = "";
        var report = analyzer.analyze(testCode, sourceCode);
        assertEquals(5, report.assertionCount());
    }
}
