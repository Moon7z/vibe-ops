# Vibe-Ops Engineering Standards

> The production-readiness contract for all AI-generated code.

## 1. Quality Dimensions

Every feature, endpoint, or module **MUST** address all 8 dimensions before deployment:

| # | Dimension | Gate Level | Description |
|---|-----------|------------|-------------|
| 1 | Error Handling & Resilience | CRITICAL | Timeouts, retries, circuit breakers, fallback paths |
| 2 | Idempotency & Safety | CRITICAL | Operations safe to retry without side effects |
| 3 | Authentication & Security | CRITICAL | AuthN/AuthZ, input sanitization, secret management |
| 4 | Observability & Monitoring | HIGH | Structured logging, metrics, distributed tracing |
| 5 | Data Integrity & Validation | HIGH | Input validation, schema enforcement, migrations |
| 6 | Scalability & Performance | MEDIUM | Caching, concurrency, rate limiting, pagination |
| 7 | Testing Strategy | HIGH | Unit (>80% coverage), integration, e2e tests |
| 8 | Deployment & Rollback | HIGH | CI/CD pipeline, canary/blue-green, rollback plan |

## 2. Severity Classification

- **CRITICAL**: Must fix before any deployment. Blocks the pipeline.
- **HIGH**: Must fix before production deployment. Can deploy to staging.
- **MEDIUM**: Should fix in the current sprint. Does not block deployment.
- **LOW**: Improvement opportunity. Track in backlog.

## 3. Scan Rules

### Security (SEC-xxx)
- `SEC-001`: No hardcoded passwords, API keys, tokens, or secrets in source code.
- `SEC-002`: All SQL queries must use parameterized statements. No string concatenation.
- `SEC-003`: CSRF protection must not be globally disabled.

### Reliability (REL-xxx)
- `REL-001`: No empty catch blocks. All exceptions must be logged or re-thrown.
- `REL-002`: Avoid catching generic `Exception`. Catch specific exception types.
- `REL-003`: Use structured logging (SLF4J/Logback), not `System.out/err`.
- `REL-004`: All TODO/FIXME/HACK comments must be resolved before release.

### Performance (PERF-xxx)
- `PERF-001`: No `Thread.sleep()` in production code. Use async/event-driven patterns.
- `PERF-002`: Avoid `synchronized` blocks in Virtual Thread contexts. Use `ReentrantLock`.

### Quality (QUAL-xxx)
- `QUAL-001`: No magic numbers. Extract to named constants.
- `QUAL-002`: No wildcard imports. Use explicit imports.

## 4. MCP Tool Contract

All tools exposed via the Vibe-Ops MCP server must:

1. Accept input as a JSON object with a documented schema.
2. Return results as structured Markdown in a `ToolResult`.
3. Never throw unchecked exceptions — wrap all errors in `ToolResult.error()`.
4. Complete within the configured timeout (default: 120s).
5. Be stateless and thread-safe.

## 5. Module Architecture

```
com.vibeops
├── mcp/           # MCP protocol layer (transport + registry)
├── spec/          # Spec-Verifier module (analyze-vibe)
├── scan/          # Static scanner module (run-vibe-check)
├── test/          # Test-Autopilot module (generate-tests, run-tests, test-gate)
├── deploy/        # Ghost-Deployer module (generate-infra)
└── heal/          # Self-Healing Agent module (diagnose-failure, auto-heal)
```

Each module is a standalone package. Cross-module communication happens only through the MCP tool interface or Spring events.

## 6. Deployment Gate

The overall deployment gate is determined by the highest severity finding:

| Highest Finding | Gate Decision |
|----------------|---------------|
| CRITICAL       | **BLOCKED** — cannot deploy |
| HIGH           | **WARNING** — requires manual approval |
| MEDIUM or LOW  | **PASS** — deploy with notes |
| None           | **ALL CLEAR** — auto-deploy eligible |

---
*Vibe-Ops v0.1.0 — Making AI-generated code production-ready.*

## 7. Self-Healing Error Classification

The Self-Healing Agent classifies runtime errors into the following categories:

| Category | Severity | Example | Repair Type |
|----------|----------|---------|-------------|
| RESOURCE | CRITICAL | OutOfMemoryError, OOMKilled | Infra (scale memory) |
| SECURITY | CRITICAL | 401/403, AuthenticationException | Config (rotate credentials) |
| CONNECTIVITY | HIGH | Connection refused, JDBC failure | Config/Diagnostic |
| RUNTIME | HIGH | CrashLoopBackOff, probe failure | Config (adjust probes) |
| DEPLOYMENT | HIGH | ImagePullBackOff | Infra (fix image/registry) |
| CONFIGURATION | MEDIUM | Missing placeholder, BeanCreationException | Config/Code fix |
| CODE_BUG | MEDIUM | NullPointerException, StackOverflow | Code fix (AI repair) |
| DATA | MEDIUM | Migration failure | Code fix |
| DEPENDENCY | MEDIUM | ClassNotFoundException | Code fix (deps) |

## 8. MCP Tool Reference

| # | Tool | Module | Purpose |
|---|------|--------|---------|
| 1 | `analyze-vibe` | Spec-Verifier | 8-dimension production-readiness analysis |
| 2 | `run-vibe-check` | Static Scanner | 11-rule static code scan |
| 3 | `generate-tests` | Test-Autopilot | AI test prompt / stub generation |
| 4 | `run-tests` | Test-Autopilot | Maven test execution with report |
| 5 | `test-gate` | Test Gate | Unified quality gate (test + coverage) |
| 6 | `generate-infra` | Ghost-Deployer | Dockerfile + Compose + CI/CD generation |
| 7 | `diagnose-failure` | Self-Healing | Error log analysis + repair suggestions |
| 8 | `auto-heal` | Self-Healing | Full pipeline: monitor → diagnose → repair |
