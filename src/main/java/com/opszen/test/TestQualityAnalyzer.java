package com.opszen.test;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TestQualityAnalyzer {

    private static final Pattern ASSERT_PATTERN = Pattern.compile(
            "assert(Equals|True|False|NotNull|Null|Throws|DoesNotThrow|That|ArrayEquals|Same|NotSame)\\s*\\(");
    private static final Pattern WHEN_THEN_PATTERN = Pattern.compile(
            "(should_|when_|given_|test_)\\w+");
    private static final Pattern NULL_CHECK_PATTERN = Pattern.compile(
            "(null|Null|NULL|isNull|notNull|NullPointer)");
    private static final Pattern EMPTY_CHECK_PATTERN = Pattern.compile(
            "(empty|Empty|isEmpty|isBlank|emptyList|emptyMap|size\\(\\)\\s*==\\s*0)");
    private static final Pattern BOUNDARY_PATTERN = Pattern.compile(
            "(MAX_VALUE|MIN_VALUE|Integer\\.MAX|Long\\.MAX|boundary|overflow|underflow|zero|negative)");
    private static final Pattern EXCEPTION_PATTERN = Pattern.compile(
            "(assertThrows|expectedException|@Test\\s*\\(\\s*expected|catch\\s*\\(\\w+Exception)");
    private static final Pattern TEST_METHOD_PATTERN = Pattern.compile(
            "@Test\\s+.*?void\\s+(\\w+)\\s*\\(", Pattern.DOTALL);

    public QualityReport analyze(String testCode, String sourceCode) {
        int assertionCount = countMatches(ASSERT_PATTERN, testCode);
        List<String> edgeCases = detectEdgeCases(testCode);
        List<String> exceptionPaths = detectExceptionPaths(testCode, sourceCode);
        int methodCount = countMatches(TEST_METHOD_PATTERN, testCode);
        int whenThenCount = countWhenThenMethods(testCode);

        // Scoring
        int score = 30; // base score for generating tests

        // Assertion coverage: +25
        int expectedMinAssertions = Math.max(methodCount * 2, 4);
        double assertRatio = Math.min((double) assertionCount / expectedMinAssertions, 1.0);
        score += (int) (25 * assertRatio);

        // Edge case coverage: +20
        int maxEdgeCases = 4; // null, empty, boundary, exception
        score += (int) (20 * Math.min((double) edgeCases.size() / maxEdgeCases, 1.0));

        // Exception path coverage: +15
        int expectedExceptions = countExpectedExceptions(sourceCode);
        if (expectedExceptions > 0) {
            double exRatio = Math.min((double) exceptionPaths.size() / expectedExceptions, 1.0);
            score += (int) (15 * exRatio);
        } else if (!exceptionPaths.isEmpty()) {
            score += 15;
        }

        // Naming convention: +10
        if (methodCount > 0) {
            double namingRatio = (double) whenThenCount / methodCount;
            score += (int) (10 * namingRatio);
        }

        score = Math.min(score, 100);
        String qualityLevel = score >= 80 ? "good" : score >= 60 ? "acceptable" : "needs_improvement";

        List<String> suggestions = generateSuggestions(score, assertionCount, edgeCases,
                exceptionPaths, whenThenCount, methodCount, sourceCode);

        return new QualityReport(assertionCount, edgeCases, exceptionPaths,
                score, qualityLevel, suggestions);
    }

    private List<String> detectEdgeCases(String testCode) {
        List<String> cases = new ArrayList<>();
        if (NULL_CHECK_PATTERN.matcher(testCode).find()) cases.add("null_input");
        if (EMPTY_CHECK_PATTERN.matcher(testCode).find()) cases.add("empty_list");
        if (BOUNDARY_PATTERN.matcher(testCode).find()) cases.add("max_value");
        if (EXCEPTION_PATTERN.matcher(testCode).find()) cases.add("exception_handling");
        return cases;
    }

    private List<String> detectExceptionPaths(String testCode, String sourceCode) {
        List<String> paths = new ArrayList<>();
        Pattern throwsPattern = Pattern.compile("throws\\s+([\\w,\\s]+)");
        Matcher m = throwsPattern.matcher(sourceCode);
        while (m.find()) {
            for (String ex : m.group(1).split(",")) {
                String trimmed = ex.trim();
                if (!trimmed.isEmpty() && testCode.contains(trimmed)) {
                    paths.add(trimmed);
                }
            }
        }
        // Check for common unchecked exceptions tested
        for (String ue : List.of("NullPointerException", "IllegalArgumentException",
                "IllegalStateException", "IndexOutOfBoundsException")) {
            if (testCode.contains(ue) && !paths.contains(ue)) paths.add(ue);
        }
        return paths;
    }

    private int countExpectedExceptions(String sourceCode) {
        int count = 0;
        if (sourceCode.contains("throw new")) count += countMatches(Pattern.compile("throw new \\w+"), sourceCode);
        if (sourceCode.contains("throws ")) count++;
        return Math.max(count, 1);
    }

    private int countWhenThenMethods(String testCode) {
        Matcher m = TEST_METHOD_PATTERN.matcher(testCode);
        int count = 0;
        while (m.find()) {
            if (WHEN_THEN_PATTERN.matcher(m.group(1)).matches()) count++;
        }
        return count;
    }

    private int countMatches(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private List<String> generateSuggestions(int score, int assertions, List<String> edgeCases,
            List<String> exPaths, int whenThen, int methods, String sourceCode) {
        List<String> suggestions = new ArrayList<>();
        if (assertions < methods * 2) suggestions.add("增加断言数量，每个测试方法至少 2 个断言");
        if (!edgeCases.contains("null_input")) suggestions.add("建议增加 null 输入测试");
        if (!edgeCases.contains("empty_list")) suggestions.add("建议增加空值/空集合测试");
        if (exPaths.isEmpty() && sourceCode.contains("throw")) suggestions.add("建议补充异常路径测试");
        if (methods > 0 && whenThen < methods) suggestions.add("建议使用 should_/when_/given_ 命名规范");
        if (sourceCode.contains("synchronized") || sourceCode.contains("Thread"))
            suggestions.add("建议增加并发场景测试");
        if (sourceCode.contains("DataSource") || sourceCode.contains("Repository"))
            suggestions.add("建议补充数据库连接异常路径");
        return suggestions;
    }

    public record QualityReport(
            int assertionCount,
            List<String> edgeCasesCovered,
            List<String> exceptionPathsTested,
            int effectivenessScore,
            String qualityLevel,
            List<String> suggestions
    ) {}
}
