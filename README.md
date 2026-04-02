# Vibe-Ops

**Agentic Vibe-Ops Platform** — AI 生成代码的自主生产就绪守门员。

Vibe-Ops 是一个 MCP (Model Context Protocol) Server，为 AI 编码场景提供全链路质量保障。它通过 JSON-RPC 协议暴露 10 个工具，覆盖从需求分析、代码扫描、测试生成到基础设施部署和故障自愈的完整流程。

## 核心功能

### 1. Spec Verifier — 需求分析
- **analyze-vibe**: 分析开发需求/Prompt 的生产就绪度，从错误处理、幂等性、安全性、可观测性、数据完整性、可扩展性、测试、部署 8 个维度评分，输出结构化报告和改进建议。

### 2. Static Scanner — 静态扫描
- **run-vibe-check**: 对 Java 源码进行静态扫描，检测 11 类反模式：硬编码密钥、SQL 注入风险、空 catch 块、TODO 标记、System.out 调用、线程安全问题等，按 CRITICAL/HIGH/MEDIUM/LOW 分级报告。

### 3. Test Autopilot — 自动化测试
- **generate-tests**: 基于 Git diff 或指定文件自动生成 JUnit 5 测试代码，支持 AI Prompt 模式和 Stub 直写模式，内置质量评分系统。
- **run-tests**: 执行 Maven 测试，返回结构化的通过/失败报告。
- **test-gate**: 综合质量门禁，支持多环境配置（development/staging/production），分析变更文件覆盖率 + 执行全量测试 + 输出 PASS/BLOCKED 部署决策。

### 4. Ghost Deployer — 基础设施生成
- **generate-infra**: 分析项目结构，一键生成生产级 Dockerfile（多阶段构建）、docker-compose.yml（可选 PostgreSQL/Redis）、GitHub Actions CI/CD 流水线。

### 5. Self-Healing Agent — 故障自愈
- **diagnose-failure**: 分析错误日志和 Stack Trace，识别 13 种错误模式（OOM、数据库连接、配置缺失、CrashLoop 等），输出根因诊断和修复方案。
- **auto-heal**: 三级执行模式（propose-only / semi-auto / full-auto），内置频率限制、审计日志、备份回滚机制。
- **audit-history**: 查询自愈操作的审计日志历史。
- **rollback**: 管理和恢复文件备份快照。

## 技术栈

- Java 17 + Spring Boot 3.4
- MCP Protocol (2024-11-05)
- Micrometer + Prometheus（可观测性）
- Guava（频率限制）
- JaCoCo + Checkstyle（代码质量）
- Maven 构建
- 纯 HTML/CSS/JS Dashboard（无外部依赖）

## 快速开始

```bash
# 编译运行
mvn spring-boot:run

# 访问 Dashboard
open http://localhost:3100

# MCP 端点
POST http://localhost:3100/mcp

# Prometheus 指标
GET http://localhost:3100/actuator/prometheus

# 健康检查
GET http://localhost:3100/actuator/health
```

## MCP 客户端配置

```json
{
  "mcpServers": {
    "vibe-ops": {
      "url": "http://localhost:3100/mcp"
    }
  }
}
```

启用鉴权后，客户端需在请求头中携带 token：
```
Authorization: Bearer <your-api-key>
```

## 配置参考

完整的 `application.yml` 配置示例：

```yaml
vibeops:
  auth:
    enabled: false
    type: api-key
    api-key: "your-secret"
    jwt-secret: "your-jwt-secret"
    jwt-expiration: 86400000
    ip-whitelist: []

  auto-heal:
    mode: propose-only
    require-approval: true
    max-auto-fixes-per-hour: 3
    rollback-retention: 24h
    backup-dir: .vibeops/backup

  test-gate:
    active-profile: development
    profiles:
      development:
        coverage-threshold: 50.0
        block-on-failure: false
        allow-skip-tests: true
      staging:
        coverage-threshold: 70.0
        block-on-failure: true
        allow-skip-tests: false
        required-checks: [unit_tests, integration_tests]
      production:
        coverage-threshold: 85.0
        block-on-failure: true
        allow-skip-tests: false
        required-checks: [unit_tests, integration_tests, security_scan]

management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
```

## 安全指南

### 鉴权配置
- 生产环境务必设置 `vibeops.auth.enabled=true`
- API Key 模式：设置强随机密钥，通过 `Authorization: Bearer <key>` 传递
- JWT 模式：配置 `jwt-secret`，支持 HMAC-SHA256 签名验证和过期检查
- IP 白名单优先级高于 token 认证，白名单内的 IP 无需 token

### Auto-Heal 风险提示
- 默认 `propose-only` 模式，仅输出诊断和建议，不修改任何文件
- `semi-auto` 模式会创建 Git 分支并提交修复代码，但不自动合并
- `full-auto` 模式在 `VIBEOPS_ENV=production` 时自动拒绝执行
- 所有修复操作记录审计日志（`.vibeops/audit/`），支持备份回滚
- 频率限制：默认每小时最多 3 次自动修复

### 生产部署建议
- 使用反向代理（Nginx）限制 `/mcp` 端点的外部访问
- 配置 HTTPS 加密传输
- 定期轮换 API Key / JWT Secret
- 监控 `vibeops_auth_requests_total{status="rejected"}` 指标

## 可观测性

访问 `GET /actuator/prometheus` 获取所有指标。

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `vibeops_scans_total` | Counter | severity | 扫描总数 |
| `vibeops_tests_generated_total` | Counter | status, quality_level | 测试生成数 |
| `vibeops_tests_run_total` | Counter | result | 测试执行结果 |
| `vibeops_auto_heals_total` | Counter | mode, status | 自动修复次数 |
| `vibeops_auth_requests_total` | Counter | status | 认证请求统计 |
| `vibeops_mcp_requests_total` | Counter | method, status | MCP 请求统计 |
| `vibeops_mcp_request_duration_seconds` | Timer | method | MCP 请求耗时 |
| `vibeops_test_gate_results_total` | Counter | environment, result | 质量门禁结果 |
| `vibeops_current_active_heals` | Gauge | - | 当前活跃修复数 |

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `VIBEOPS_AUTH_ENABLED` | 启用鉴权 | false |
| `VIBEOPS_AUTH_TYPE` | 认证类型 | api-key |
| `VIBEOPS_AUTH_API_KEY` | API Key | - |
| `VIBEOPS_AUTH_JWT_SECRET` | JWT 密钥 | - |
| `VIBEOPS_AUTH_IP_WHITELIST` | IP 白名单（逗号分隔） | - |
| `VIBEOPS_ENV` | 运行环境 | development |
| `VIBEOPS_AUTO_HEAL_MODE` | 自愈模式 | propose-only |

## 项目结构

```
src/main/java/com/vibeops/
├── mcp/          # MCP Server 核心（协议、路由、鉴权、指标）
├── spec/         # Spec Verifier 模块
├── scan/         # Static Scanner 模块
├── test/         # Test Autopilot 模块（含质量分析、多环境门禁）
├── deploy/       # Ghost Deployer 模块
└── heal/         # Self-Healing Agent 模块（含审计、备份、回滚）
```

## License

MIT
