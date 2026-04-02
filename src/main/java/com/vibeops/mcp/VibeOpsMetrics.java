package com.vibeops.mcp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class VibeOpsMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeHeals = new AtomicInteger(0);

    public VibeOpsMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("vibeops_current_active_heals", activeHeals);
    }

    public void recordScan(String severity) {
        Counter.builder("vibeops_scans_total")
                .tag("severity", severity)
                .register(registry).increment();
    }

    public void recordTestGenerated(String status, String qualityLevel) {
        Counter.builder("vibeops_tests_generated_total")
                .tag("status", status)
                .tag("quality_level", qualityLevel)
                .register(registry).increment();
    }

    public void recordTestRun(String result) {
        Counter.builder("vibeops_tests_run_total")
                .tag("result", result)
                .register(registry).increment();
    }

    public void recordAutoHeal(String mode, String status) {
        Counter.builder("vibeops_auto_heals_total")
                .tag("mode", mode)
                .tag("status", status)
                .register(registry).increment();
    }

    public void recordAuthRequest(String status) {
        Counter.builder("vibeops_auth_requests_total")
                .tag("status", status)
                .register(registry).increment();
    }

    public void recordMcpRequest(String method, String status) {
        Counter.builder("vibeops_mcp_requests_total")
                .tag("method", method)
                .tag("status", status)
                .register(registry).increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample, String method) {
        sample.stop(Timer.builder("vibeops_mcp_request_duration_seconds")
                .tag("method", method)
                .register(registry));
    }

    public void recordTestGateResult(String environment, String result) {
        Counter.builder("vibeops_test_gate_results_total")
                .tag("environment", environment)
                .tag("result", result)
                .register(registry).increment();
    }

    public void incrementActiveHeals() { activeHeals.incrementAndGet(); }
    public void decrementActiveHeals() { activeHeals.decrementAndGet(); }
}
