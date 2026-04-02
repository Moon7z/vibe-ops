package com.opszen.heal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Monitors Kubernetes pod status and Docker container health.
 * Detects CrashLoopBackOff, OOMKilled, Error, and other failure states.
 */
@Component
public class LogMonitor {

    private static final Logger log = LoggerFactory.getLogger(LogMonitor.class);

    @Value("${opszen.execution.timeout-seconds:120}")
    private int timeoutSeconds;

    /**
     * Check pod status in a Kubernetes namespace.
     */
    public PodStatusReport checkKubernetesPods(String namespace, String labelSelector) {
        List<PodInfo> pods = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Get pod status via kubectl
        String statusOutput = runCommand(List.of(
                "kubectl", "get", "pods",
                "-n", namespace,
                "-l", labelSelector,
                "-o", "wide",
                "--no-headers"
        ));

        if (statusOutput == null) {
            return new PodStatusReport(pods, List.of("kubectl command failed or not available"), false);
        }

        for (String line : statusOutput.split("\n")) {
            if (line.isBlank()) continue;
            PodInfo pod = parsePodLine(line);
            if (pod != null) {
                pods.add(pod);
            }
        }

        // Check for unhealthy pods and fetch their logs
        for (PodInfo pod : pods) {
            if (pod.isUnhealthy()) {
                String logs = fetchPodLogs(namespace, pod.name(), 100);
                pod.setRecentLogs(logs);

                String events = fetchPodEvents(namespace, pod.name());
                pod.setEvents(events);
            }
        }

        boolean healthy = pods.stream().allMatch(p -> !p.isUnhealthy());
        return new PodStatusReport(pods, errors, healthy);
    }

    /**
     * Check Docker container health via docker ps / docker compose.
     */
    public ContainerStatusReport checkDockerContainers(String projectPath) {
        List<ContainerInfo> containers = new ArrayList<>();

        String output = runCommand(List.of(
                "docker", "compose", "-f", projectPath + "/docker-compose.yml",
                "ps", "--format", "{{.Name}}\t{{.Status}}\t{{.State}}"
        ));

        if (output == null) {
            // Fallback: try docker ps
            output = runCommand(List.of("docker", "ps", "-a", "--format",
                    "{{.Names}}\t{{.Status}}\t{{.State}}"));
        }

        if (output == null) {
            return new ContainerStatusReport(containers, false, "Docker is not available");
        }

        for (String line : output.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t");
            if (parts.length >= 3) {
                String name = parts[0].trim();
                String status = parts[1].trim();
                String state = parts[2].trim();
                boolean unhealthy = !state.equalsIgnoreCase("running") ||
                                    status.toLowerCase().contains("unhealthy");

                ContainerInfo ci = new ContainerInfo(name, status, state, unhealthy);

                if (unhealthy) {
                    String logs = runCommand(List.of("docker", "logs", "--tail", "100", name));
                    ci.setRecentLogs(logs);
                }

                containers.add(ci);
            }
        }

        boolean healthy = containers.stream().allMatch(c -> !c.unhealthy());
        return new ContainerStatusReport(containers, healthy, null);
    }

    /**
     * Fetch raw logs from a log file path.
     */
    public String readLogFile(String logPath, int tailLines) {
        String output = runCommand(List.of("tail", "-n", String.valueOf(tailLines), logPath));
        return output != null ? output : "Failed to read log file: " + logPath;
    }

    private String fetchPodLogs(String namespace, String podName, int tailLines) {
        return runCommand(List.of(
                "kubectl", "logs", podName,
                "-n", namespace,
                "--tail", String.valueOf(tailLines)
        ));
    }

    private String fetchPodEvents(String namespace, String podName) {
        return runCommand(List.of(
                "kubectl", "get", "events",
                "-n", namespace,
                "--field-selector", "involvedObject.name=" + podName,
                "--sort-by=.lastTimestamp"
        ));
    }

    private PodInfo parsePodLine(String line) {
        // kubectl get pods --no-headers format:
        // NAME  READY  STATUS  RESTARTS  AGE  IP  NODE
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5) return null;

        String name = parts[0];
        String ready = parts[1];
        String status = parts[2];
        String restarts = parts[3];

        int restartCount = 0;
        Matcher m = Pattern.compile("(\\d+)").matcher(restarts);
        if (m.find()) {
            restartCount = Integer.parseInt(m.group(1));
        }

        return new PodInfo(name, ready, status, restartCount);
    }

    private String runCommand(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return null;
            }

            return process.exitValue() == 0 ? output : null;
        } catch (Exception e) {
            log.debug("Command failed: {} — {}", String.join(" ", command), e.getMessage());
            return null;
        }
    }

    // ── Data Classes ──

    public record PodStatusReport(List<PodInfo> pods, List<String> errors, boolean healthy) {}

    public static class PodInfo {
        private final String name;
        private final String ready;
        private final String status;
        private final int restartCount;
        private String recentLogs;
        private String events;

        public PodInfo(String name, String ready, String status, int restartCount) {
            this.name = name;
            this.ready = ready;
            this.status = status;
            this.restartCount = restartCount;
        }

        public boolean isUnhealthy() {
            return status.equalsIgnoreCase("CrashLoopBackOff") ||
                   status.equalsIgnoreCase("Error") ||
                   status.equalsIgnoreCase("OOMKilled") ||
                   status.equalsIgnoreCase("ImagePullBackOff") ||
                   status.equalsIgnoreCase("ErrImagePull") ||
                   restartCount > 3;
        }

        public String name() { return name; }
        public String ready() { return ready; }
        public String status() { return status; }
        public int restartCount() { return restartCount; }
        public String recentLogs() { return recentLogs; }
        public String events() { return events; }
        public void setRecentLogs(String logs) { this.recentLogs = logs; }
        public void setEvents(String events) { this.events = events; }
    }

    public record ContainerStatusReport(List<ContainerInfo> containers, boolean healthy, String error) {}

    public static class ContainerInfo {
        private final String name;
        private final String status;
        private final String state;
        private final boolean unhealthy;
        private String recentLogs;

        public ContainerInfo(String name, String status, String state, boolean unhealthy) {
            this.name = name;
            this.status = status;
            this.state = state;
            this.unhealthy = unhealthy;
        }

        public String name() { return name; }
        public String status() { return status; }
        public String state() { return state; }
        public boolean unhealthy() { return unhealthy; }
        public String recentLogs() { return recentLogs; }
        public void setRecentLogs(String logs) { this.recentLogs = logs; }
    }
}
