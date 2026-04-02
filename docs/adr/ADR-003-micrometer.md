# ADR-003: 为什么选择 Micrometer 而非自定义指标

## 状态
已采纳

## 背景
OpsZen 需要暴露运行时指标用于生产监控，包括 MCP 请求量、认证统计、工具执行次数、测试通过率等。需要选择指标收集和暴露方案。

## 决策
采用 Micrometer + Prometheus Registry 作为指标方案。

### 选择理由

1. **Spring Boot 原生集成**：Micrometer 是 Spring Boot Actuator 的默认指标门面，零配置即可启用，与 Spring 生态无缝衔接。

2. **供应商中立**：Micrometer 支持 Prometheus、Datadog、InfluxDB、CloudWatch 等 15+ 后端，切换监控系统只需更换 Registry 依赖，业务代码无需修改。

3. **标准化 API**：Counter、Timer、Gauge、DistributionSummary 等原语覆盖所有常见场景，API 设计成熟稳定。

4. **维度化标签**：原生支持多维标签（tag），便于按 method、status、environment 等维度聚合和过滤。

5. **社区生态**：Grafana 社区有大量现成的 Spring Boot / Micrometer Dashboard 模板可直接复用。

## 考虑的替代方案

1. **自定义指标系统**
   - 优点：完全可控，无外部依赖
   - 缺点：需要自行实现线程安全的计数器、直方图、暴露端点；维护成本高；无法复用社区 Dashboard
   - 结论：重复造轮子，不值得

2. **OpenTelemetry**
   - 优点：CNCF 标准，支持 Traces + Metrics + Logs 统一
   - 缺点：Spring Boot 3.4 对 OTel Metrics 的自动配置尚不如 Micrometer 成熟；引入 OTel Collector 增加部署复杂度
   - 结论：未来可考虑迁移，当前 Micrometer 更务实

3. **Dropwizard Metrics**
   - 优点：成熟稳定
   - 缺点：非 Spring Boot 默认选择，需要额外适配；标签支持不如 Micrometer
   - 结论：已被 Micrometer 取代

## 结论
Micrometer 是 Spring Boot 项目的最佳指标方案，开箱即用、供应商中立、社区活跃。选择 Prometheus Registry 是因为 Prometheus + Grafana 是最流行的开源监控栈，与 Kubernetes 生态天然契合。
