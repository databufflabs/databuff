# DataBuff vs Jaeger

> Comparison · [中文](./vs-jaeger.md)

Same-host lab on `192.168.50.140`: **DataBuff v0.1.4** vs **Jaeger v1.76.0**, same Demo (`service-a` / `service-b`). DataBuff uses OTLP `:4318`; Jaeger all-in-one UI is `:16686`. Marks: ✅ verified in this lab · △ present but limited · ❌ no equivalent.

Full HTML article with screenshots: [DataBuff vs Jaeger (lab compare)](https://databuff.ai/blog/databuff-vs-jaeger)

## 1. Capability matrix

**Seven AI capabilities** (v0.1.4: See → Squad → Inspect → Diagnose → Repair → Predict → Answer)

| Capability | Jaeger v1.76.0 | DataBuff v0.1.4 |
|------------|---------------|-----------------|
| ① See · natural-language questions | ❌ | ✅ Ask about services / topology / trends; AI reads telemetry |
| ② Squad · multi-agent collaboration | ❌ | ✅ Parallel evidence gathering; reusable task orchestration |
| ③ Inspect · service inspection + report | ❌ | ✅ One-shot inspection with evidence and actions |
| ④ Diagnose · bottleneck / RCA evidence | ❌ | ✅ Trace / metrics / topology evidence (not a black-box “root cause”) |
| ⑤ Repair · Ops Expert actions | ❌ | ✅ Repair under policy + human approval; dangerous-command denylist |
| ⑥ Predict · capacity / trends | ❌ | ✅ Capacity and trend analysis — from after-the-fact to ahead-of-time |
| ⑦ Answer · product Q&A | ❌ | ✅ Answers deploy / ingest / config from docs and code |
| Extend · MCP / Skill / custom experts | ❌ | ✅ External MCP / Skill and custom digital experts |

Largest gap: Jaeger is a distributed tracing backend with no equivalent AI platform; DataBuff exposes the seven capabilities as configurable home entries with APM as AI context.

**APM**

| Capability | Jaeger v1.76.0 | DataBuff v0.1.4 |
|------------|---------------|-----------------|
| 1. Global topology | △ Dependencies (service DAG; this lab shows service-a → service-b) | ✅ Topology + health colors + drill-down (incl. middleware) |
| 2. Service list & golden metrics | ❌ Search dropdown only; no dedicated service list / golden-metric charts | ✅ Service list + charts; same demo shows service-a / b |
| 3. Service-level topology | △ Via Dependencies only | ✅ Dedicated service topology |
| 4. Service call analysis (up/downstream + Trace) | ❌ | ✅ Upstream/downstream structure, latency/contribution; drill to Trace |
| 5. Instance golden metrics | ❌ | ✅ Instance golden-metric charts / list |
| 6. Instance topology | ❌ | ✅ Dedicated instance topology |
| 7. Instance call analysis (up/downstream + Trace) | ❌ | ✅ Per-instance up/downstream + Trace |
| 8. Endpoint topology | ❌ | ✅ Dedicated endpoint topology |
| 9. Endpoint call analysis (up/downstream + Trace) | ❌ Mostly Trace search filters | ✅ Per-endpoint caller/callee + Trace |
| 10. Service flow (service / endpoint Trace contribution) | ❌ Dependencies answers “who connects” only | ✅ Response contribution from entry; service / endpoint Trace view |
| 11. Middleware / external pages (DB / cache / MQ / external) | ❌ | ✅ Dedicated pages: DB / cache / MQ / external |
| 12. Error analysis (stats + endpoint) | ❌ Mostly Trace status filters | ✅ Error stats + endpoint drill-down |
| 13. Trace list / search | ✅ Service / operation / Tags / time — mature search UX | ✅ Charts + list, multi-dimension filters |
| 14. Trace detail | ✅ Classic Waterfall + Tags + Span Logs | ✅ Call-order waterfall + Span attributes |
| 15. Trace Span → logs | △ Span Logs (instrumentation events) only; no OTLP app-log link | ✅ Top “Log analysis” + Span Logs / Logs tab |
| 16. Log list / search | ❌ | ✅ Log analysis list / search |
| 17. Log detail | ❌ | ✅ |
| 18. Log → Trace | ❌ | ✅ Log → Trace, down to Span |

Jaeger is strong on **pure Trace search and waterfall**. Most other APM surfaces (golden metrics, multi-level topology / call analysis, service flow, middleware pages, logs) are absent. DataBuff leads there and on **Span↔log** linkage.

**Alerting**

| Capability | Jaeger v1.76.0 | DataBuff v0.1.4 |
|------------|---------------|-----------------|
| How rules are configured | ❌ No built-in alerting product | ✅ Alert center in product |
| Threshold alerts | ❌ Needs Prometheus / Alertmanager, etc. | ✅ Managed in platform |
| Smart alerts | ❌ | ✅ Linked with APM metrics |
| Alert event list | ❌ | ✅ Non-empty in this lab |
| Alerts linked to service / middleware | ❌ | ✅ List links back into APM |

Jaeger itself does not alert; threshold / notify stacks are external. DataBuff keeps rule config, event list, and service context in one alert center.

**When to pick which**

| Scenario | Better fit | Note |
|----------|------------|------|
| Already on OTLP, want AI / APM depth first | DataBuff (side-by-side) | Point ingest at DataBuff |
| Need the seven AI capabilities | DataBuff | No Jaeger AI platform |
| MCP / Skill / custom experts | DataBuff | Jaeger has no such layer |
| See who slows the entry response | DataBuff | Service flow + contribution |
| Call analysis → Trace (service / instance / endpoint) | DataBuff | No Jaeger path |
| Slow SQL / cache / MQ pages | DataBuff | Jaeger has no middleware pages |
| Log + Trace correlation | DataBuff | Jaeger has no log product surface |
| Built-in / smart alerts | DataBuff | Jaeger needs external stack |
| Lightweight Trace storage + waterfall only | Jaeger / either | No need to migrate for brand |
| Already on ES / Cassandra and Trace-only | Jaeger | Reuse storage; DataBuff can still OTLP side-by-side |

**Boundary:** Deep Jaeger search workflow lock-in, or Trace-only needs → stay on Jaeger. DataBuff fits same OTLP data + AI + APM depth + alerts, side-by-side or gradual switch.

## 2. Screenshot evidence

Screenshots from the same lab. Captions map to the matrix; focus on DataBuff’s AI / call analysis / dedicated pages / alerts. Jaeger’s strength is pure Trace search and waterfall.

**Seven AI capabilities** (no Jaeger equivalent UI)

![DataBuff AI home](../images/vs-jg-databuff-ai-home.png)

![DataBuff AI chat](../images/vs-jg-databuff-ai-chat.png)

![DataBuff digital experts](../images/vs-jg-databuff-ai-experts.png)

**Service & topology**

![Jaeger Dependencies](../images/vs-jg-jaeger-dependencies.png)

![DataBuff topology](../images/vs-jg-databuff-topology.png)

![DataBuff services](../images/vs-jg-databuff-services.png)

**Call analysis + service flow** (matrix rows 4 / 9 / 10)

![DataBuff service call analysis](../images/vs-jg-databuff-service-call-analysis.png)

![DataBuff endpoint call analysis](../images/vs-jg-databuff-api-call-analysis.png)

![DataBuff service flow](../images/vs-jg-databuff-service-flow.png)

**Trace** (Jaeger mature surface)

![Jaeger Search](../images/vs-jg-jaeger-search-page.png)

![Jaeger Trace list](../images/vs-jg-jaeger-trace-list.png)

![DataBuff Trace list](../images/vs-jg-databuff-trace-list.png)

![Jaeger Trace detail](../images/vs-jg-jaeger-trace-detail.png)

![DataBuff Trace detail](../images/vs-jg-databuff-trace-detail.png)

**Logs** (matrix rows 16–18; no Jaeger equivalent)

![DataBuff logs](../images/vs-jg-databuff-logs.png)

**DataBuff dedicated pages** (matrix rows 11 / 12)

![Database](../images/vs-jg-databuff-database.png)

![Cache](../images/vs-jg-databuff-cache.png)

![MQ](../images/vs-jg-databuff-mq.png)

![External](../images/vs-jg-databuff-external.png)

![API analysis](../images/vs-jg-databuff-api.png)

![Error analysis](../images/vs-jg-databuff-errors.png)

**Alerting** (no Jaeger built-in alerts)

![DataBuff alerts](../images/vs-jg-databuff-alerts.png)

---

If this was useful, a Star (and Issues / PRs) are welcome:  
https://github.com/databufflabs/databuff
