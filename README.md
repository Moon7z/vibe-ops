# Vibe-Ops

**Agentic Vibe-Ops Platform** — AI 生成代码的自主生产就绪守门员。

Vibe-Ops 是一个 MCP (Model Context Protocol) Server，为 AI 编码场景提供全链路质量保障。它通过 JSON-RPC 协议暴露 8 个工具，覆盖从需求分析、代码扫描、测试生成到基础设施部署和故障自愈的完整流程。

## 核心功能

### 1. Spec Verifier — 需求分析
- **analyze-vibe**: 分析开发需求/Prompt 的生产就绪度，从错误处理、幂等性、安全性、可观测性、数据完整性、可扩展性、测试、部署 8 个维度评分，输出结构化报告和改进建议。

### 2. Static Scanner — 静态扫描
- **run-vibe-check**: 对 Java 源码进行静态扫描，检测 11 类反模式：硬编码密钥、SQL 注入风险、空 catch 块、TODO 标记、System.out 调用、线程安全问题等，按 CRITICAL/HIGH/MEDIUM/LOW 分级报告。

### 3. Test Autopilot — 自动化测试
- **generate-tests**: 基于 Git diff 或指定文件自动生成 JUnit 5 测试代码，支持 AI Prompt 模式和 Stub 直写模式。
- **run-tests**: 执行 Maven 测试，返回结构化的通过/失败报告。
- **test-gate**: 综合质量门禁 — 分析变更文件覆盖率 + 执行全量测试 + 输出 PASS/BLOCKED 部署决策。

### 4. Ghost Deployer — 基础设施生成
- **generate-infra**: 分析项目结构，一键生成生产级 Dockerfile（多阶段构建）、docker-compose.yml（可选 PostgreSQL/Redis）、GitHub Actions CI/CD 流水线。

### 5. Self-Healing Agent — 故障自愈
- **diagnose-failure**: 分析错误日志和 Stack Trace，识别 13 种错误模式（OOM、数据库连接、配置缺失、CrashLoop 等），输出根因诊断和修复方案。
- **auto-heal**: 全自动修复流水线 — 监控 K8s Pod / Docker 容器 → 提取错误日志 → 诊断根因 → 生成修复计划和代码修复 Prompt。

## 技术栈

- Java 17 + Spring Boot 3.4
- MCP Protocol (2024-11-05)
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
```

## MCP 客户端配置

将以下配置添加到你的 MCP 客户端（如 Claude Code、Cursor 等）：

```json
{
  "mcpServers": {
    "vibe-ops": {
      "url": "http://localhost:3100/mcp"
    }
  }
}
```

## 项目结构

```
src/main/java/com/vibeops/
├── mcp/          # MCP Server 核心（协议、路由、工具注册）
├── spec/         # Spec Verifier 模块
├── scan/         # Static Scanner 模块
├── test/         # Test Autopilot 模块
├── deploy/       # Ghost Deployer 模块
└── heal/         # Self-Healing Agent 模块
```

## License

MIT
