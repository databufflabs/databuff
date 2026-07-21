# Comparison Overview

> This page summarizes capability comparisons between DataBuff and mainstream APM/observability systems. [Switch to Chinese](./总览.md)

## Capability Matrix

| Dimension | DataBuff | SkyWalking | Jaeger | Pinpoint | SigNoz | OpenObserve |
|-----------|----------|------------|--------|----------|--------|-------------|
| Protocols | OTLP + SW gRPC | SW gRPC + OTLP | OTLP + Jaeger | Java Agent | OTLP | OTLP |
| AI | ✅ Native | ❌ | ❌ | ❌ | ❌ | ❌ |
| Topology | ✅ Built-in | ✅ Built-in | ❌ | ✅ Built-in | ❌ | ❌ |
| Built-in Storage | ✅ Doris | ✅ H2/ES | ❌ External | ✅ H2/MySQL | ✅ ClickHouse | ✅ Zinc |
| Self-ops | ✅ Built-in | ❌ | ❌ | ❌ | ❌ | ❌ |

## Comparison Pages

- [DataBuff vs SkyWalking](./vs-skywalking.md)
- [DataBuff vs Jaeger](./vs-jaeger.md)
- [DataBuff vs Pinpoint](./vs-pinpoint.md)
- [DataBuff vs SigNoz](./vs-signoz.md)
- [DataBuff vs OpenObserve](./vs-openobserve.md)
