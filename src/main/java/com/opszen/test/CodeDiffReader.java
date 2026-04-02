package com.opszen.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads code diffs (git-based or file-based) to extract changed source files
 * and their content for test generation.
 */
@Component
public class CodeDiffReader {

    private static final Logger log = LoggerFactory.getLogger(CodeDiffReader.class);

    /**
     * Extract changed Java files from a git working directory.
     * Returns a map of file path -> file content.
     */
    public Map<String, String> readChangedFiles(String projectPath) throws IOException {
        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) {
            throw new IOException("Project path does not exist: " + projectPath);
        }

        // Try git diff first
        List<String> changedPaths = getGitChangedFiles(root);

        // If no git changes, fall back to scanning all Java source files
        if (changedPaths.isEmpty()) {
            log.info("No git changes detected, scanning all source files");
            changedPaths = scanAllJavaFiles(root);
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (String relPath : changedPaths) {
            Path filePath = root.resolve(relPath);
            if (Files.exists(filePath) && filePath.toString().endsWith(".java")) {
                // Skip test files — we don't generate tests for tests
                if (relPath.contains("/test/") || relPath.contains("\\test\\")) {
                    continue;
                }
                result.put(relPath, Files.readString(filePath));
            }
        }
        return result;
    }

    /**
     * Read a single file's content for targeted test generation.
     */
    public String readFile(String filePath) throws IOException {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + filePath);
        }
        return Files.readString(path);
    }

    private List<String> getGitChangedFiles(Path root) {
        try {
            // Verify this directory is actually a git root (has .git)
            // to avoid inheriting a parent repo's state
            ProcessBuilder checkGit = new ProcessBuilder(
                    "git", "rev-parse", "--show-toplevel"
            );
            checkGit.directory(root.toFile());
            checkGit.redirectErrorStream(true);
            Process checkProcess = checkGit.start();
            String topLevel = new String(checkProcess.getInputStream().readAllBytes()).trim();
            int checkExit = checkProcess.waitFor();

            if (checkExit != 0) {
                return List.of();
            }

            // Ensure the git root matches this project root
            Path gitRoot = Path.of(topLevel).toRealPath();
            Path projectRoot = root.toRealPath();
            if (!gitRoot.equals(projectRoot)) {
                log.info("Project dir is not a git root (git root: {}), skipping git diff", gitRoot);
                return List.of();
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "git", "diff", "--name-only", "HEAD"
            );
            pb.directory(root.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                pb = new ProcessBuilder("git", "diff", "--name-only", "--cached");
                pb.directory(root.toFile());
                pb.redirectErrorStream(true);
                process = pb.start();
                output = new String(process.getInputStream().readAllBytes());
                exitCode = process.waitFor();
            }

            if (exitCode != 0) {
                return List.of();
            }

            return output.lines()
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Git diff failed, falling back to file scan: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> scanAllJavaFiles(Path root) throws IOException {
        Path srcMain = root.resolve("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        Files.walk(srcMain)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> files.add(root.relativize(p).toString().replace('\\', '/')));
        return files;
    }
}
