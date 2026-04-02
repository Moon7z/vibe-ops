package com.opszen.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Map;

@Configuration
public class McpAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(McpAuthFilter.class);

    private final AuthConfig authConfig;
    private final ObjectMapper objectMapper;
    private final OpsZenMetrics metrics;

    public McpAuthFilter(AuthConfig authConfig, ObjectMapper objectMapper, OpsZenMetrics metrics) {
        this.authConfig = authConfig;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Bean
    public FilterRegistrationBean<Filter> mcpAuthFilterRegistration() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletRequest request = (HttpServletRequest) req;
                HttpServletResponse response = (HttpServletResponse) res;
                long start = System.currentTimeMillis();
                String clientIp = getClientIp(request);

                if (!authConfig.isEnabled()) {
                    log.debug("Auth disabled, allowing request from {}", clientIp);
                    metrics.recordAuthRequest("skipped");
                    chain.doFilter(req, res);
                    return;
                }

                // IP whitelist takes priority — if in whitelist, skip token check
                if (!authConfig.getIpWhitelist().isEmpty()
                        && authConfig.getIpWhitelist().contains(clientIp)) {
                    log.info("Auth OK (ip-whitelist) | ip={} | {}ms", clientIp, System.currentTimeMillis() - start);
                    metrics.recordAuthRequest("allowed");
                    chain.doFilter(req, res);
                    return;
                }

                // Token authentication
                String authHeader = request.getHeader("Authorization");
                boolean authenticated = false;
                String authMethod = authConfig.getType();

                if ("api-key".equals(authMethod)) {
                    authenticated = validateApiKey(authHeader);
                } else if ("jwt".equals(authMethod)) {
                    authenticated = validateJwt(authHeader);
                }

                if (!authenticated) {
                    log.warn("Auth REJECTED ({}) | ip={} | {}ms", authMethod, clientIp, System.currentTimeMillis() - start);
                    metrics.recordAuthRequest("rejected");
                    sendError(response, 401, "Unauthorized: invalid or missing " + authMethod + " credentials");
                    return;
                }

                log.info("Auth OK ({}) | ip={} | {}ms", authMethod, clientIp, System.currentTimeMillis() - start);
                metrics.recordAuthRequest("allowed");
                chain.doFilter(req, res);
            }
        });
        reg.addUrlPatterns("/mcp");
        reg.setOrder(1);
        return reg;
    }

    private boolean validateApiKey(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authHeader.substring(7).trim();
        // Constant-time comparison to prevent timing attacks
        return java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                authConfig.getApiKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean validateJwt(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authHeader.substring(7).trim();
        // Basic HMAC-SHA256 JWT validation
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return false;

            java.util.Base64.Decoder decoder = java.util.Base64.getUrlDecoder();

            // Validate alg field to prevent algorithm confusion attacks
            String header = new String(decoder.decode(parts[0]));
            Map<?, ?> headerMap = objectMapper.readValue(header, Map.class);
            String alg = (String) headerMap.get("alg");
            if (!"HS256".equals(alg)) {
                log.warn("JWT rejected: unsupported algorithm '{}', only HS256 allowed", alg);
                return false;
            }

            String payload = new String(decoder.decode(parts[1]));
            Map<?, ?> claims = objectMapper.readValue(payload, Map.class);

            // Check expiration
            if (claims.containsKey("exp")) {
                long exp = ((Number) claims.get("exp")).longValue();
                if (System.currentTimeMillis() / 1000 > exp) {
                    log.warn("JWT token expired");
                    return false;
                }
            }

            // Verify signature with HMAC-SHA256
            String headerPayload = parts[0] + "." + parts[1];
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    authConfig.getJwtSecret().getBytes(), "HmacSHA256"));
            String expectedSig = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(headerPayload.getBytes()));
            return expectedSig.equals(parts[2]);
        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void sendError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "error", "unauthorized",
                "message", message
        )));
    }
}
