package com.vibeops.test;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "vibeops.test-gate")
public class TestGateConfig {
    private String activeProfile = "development";
    private Map<String, ProfileConfig> profiles = new HashMap<>();

    @Data
    public static class ProfileConfig {
        private double coverageThreshold = 50.0;
        private boolean blockOnFailure = false;
        private boolean allowSkipTests = true;
        private List<String> requiredChecks = new ArrayList<>();
    }

    public ProfileConfig getProfile(String env) {
        if (env != null && profiles.containsKey(env)) {
            return profiles.get(env);
        }
        return profiles.getOrDefault(activeProfile, defaultProfile());
    }

    public String resolveEnvironment(String requestEnv) {
        // Priority: request param > env var > config > default
        if (requestEnv != null && !requestEnv.isBlank()) return requestEnv;
        String envVar = System.getenv("VIBEOPS_ENV");
        if (envVar != null && !envVar.isBlank()) return envVar.toLowerCase();
        return activeProfile;
    }

    private static ProfileConfig defaultProfile() {
        ProfileConfig p = new ProfileConfig();
        p.setCoverageThreshold(50.0);
        p.setBlockOnFailure(false);
        p.setAllowSkipTests(true);
        return p;
    }
}
