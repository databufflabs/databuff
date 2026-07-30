# Migration Overview

> [Switch to Chinese](./总览.md)

This guide covers migrating from existing APM/observability systems to DataBuff.

| Source System | Migration Doc | Status |
|---------------|---------------|--------|
| SkyWalking | [From SkyWalking to DataBuff](./from-skywalking-to-databuff.md) | ✅ Published (Option A direct cutover / Option B proxy dual-write) |
| Jaeger | [From Jaeger to DataBuff](./from-jaeger-to-databuff.md) | ✅ Published (retarget OTLP endpoint) |
| Pinpoint | [From Pinpoint to DataBuff](./from-pinpoint-to-databuff.md) | ✅ Published (swap probe + point to DataBuff) |
| SigNoz | [From SigNoz to DataBuff](./from-signoz-to-databuff.md) | ✅ Published (retarget OTLP endpoint) |
| OpenObserve | [From OpenObserve to DataBuff](./from-openobserve-to-databuff.md) | ✅ Published (retarget OTLP endpoint) |

## See Also

- [DataBuff vs SkyWalking Write Performance](/blog/en/databuff-vs-skywalking-write-perf/)
- [DataBuff vs SkyWalking](/docs/en/comparison/databuff-vs-skywalking)
