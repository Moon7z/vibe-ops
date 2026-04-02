package com.opszen.heal;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "opszen.auto-heal")
public class HealConfig {
    private String mode = "propose-only"; // propose-only | semi-auto | full-auto
    private boolean requireApproval = true;
    private int maxAutoFixesPerHour = 3;
    private String rollbackRetention = "24h";
    private String backupDir = ".opszen/backup";
}
