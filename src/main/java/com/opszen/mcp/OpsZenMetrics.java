package com.opszen.mcp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OpsZenMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeHeals = new AtomicInteger(0);

    public OpsZenMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("opszen_current_active_heals", activeHeals);
    }

    public void recordScan(String severity) {
        Counter.builder("opszen_scans_total")
                .tag("severity", severity)
                .register(registry).increment();
    }

    public void recordTestGenerated(String status, String qualityLevel) {
        Counter.builder("opszen_tests_generated_total")
                .tag("status", status)
                .tag("quality_level", qualityLevel)
                .register(registry).increment();
    }

    public void recordTestRun(String result) {
        Counter.builder("opszen_tests_run_total")
                .tag("result", result)
                .register(registry).increment();
    }

    public void recordAutoHeal(String mode, String status) {
        Counter.builder("opszen_auto_heals_total")
                .tag("mode", mode)
                .tag("status", status)
                .register(registry).increment();
    }

    public void recordAuthRequest(String status) {
        Counter.builder("opszen_auth_requests_total")
                .tag("status", status)
                .register(registry).increment();
    }

    public void recordMcpRequest(String method, String status) {
        Counter.builder("opszen_mcp_requests_total")
                .tag("method", method)
                .tag("status", status)
                .register(registry).increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample, String method) {
        sample.stop(Timer.builder("opszen_mcp_request_duration_seconds")
                .tag("method", method)
                .register(registry));
    }

    public void recordTestGateResult(String environment, String result) {
        Counter.builder("opszen_test_gate_results_total")
                .tag("environment", environment)
                .tag("result", result)
                .register(registry).increment();
    }

    public void incrementActiveHeals() { activeHeals.incrementAndGet(); }
    public void decrementActiveHeals() { activeHeals.decrementAndGet(); }
}
