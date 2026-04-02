package com.opszen.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "opszen.auth")
public class AuthConfig {
    private boolean enabled = false;
    private String type = "api-key"; // api-key | jwt
    private String apiKey;
    private String jwtSecret;
    private long jwtExpiration = 86400000;
    private List<String> ipWhitelist = new ArrayList<>();
}
