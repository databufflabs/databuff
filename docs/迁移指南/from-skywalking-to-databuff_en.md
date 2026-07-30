# From SkyWalking to DataBuff

Keep your **SkyWalking Agent**—no probe swap, no application code changes. Capability differences: [DataBuff vs SkyWalking](../业界对比/vs-skywalking_en.md).

Two migration paths:

| | **Option A · Direct cutover** | **Option B · Dual-write (databuff-proxy)** |
|--|------------------------------|--------------------------------------------|
| Approach | Point Agent at DataBuff Ingest | Point Agent at proxy; fan-out to OAP + DataBuff |
| Extra component | None | Deploy [databuff-proxy](https://github.com/databufflabs/databuff-proxy) |
| Best when | One-shot cutover is acceptable | You need parallel validation and smooth cutover |
| Rollback | Point Agent back to OAP | Disable the DataBuff backend on proxy, or point Agent back to OAP |

Protocol details: [SkyWalking Ingestion](../使用手册/SkyWalking接入_en.md).

---

## Option A · Direct cutover

```
Before:  Agent  ──gRPC:11800──▶  SkyWalking OAP
After:   Agent  ──gRPC:11800──▶  DataBuff Ingest
```

**Core action: set `agent.backend_service` to the DataBuff Ingest host; keep port 11800.**

Roll out **canary → verify → expand**; small fleets can update all backends in one window.

### Prerequisites

- [DataBuff deployed](../快速入门/docker安装部署_en.md)
- Ingest SkyWalking gRPC reachable (Docker **11800**, K8s often **31180**)
- Note the current OAP address for rollback

### Steps

#### 1. Ingest endpoint

| Deployment | Point Agent to |
|------------|----------------|
| Docker | `<ingest-host>:11800` |
| Kubernetes | `<node-ip>:31180` or Ingress `11800` |

#### 2. Update backend

`agent.config`:

```properties
agent.backend_service=<databuff-ingest-host>:11800
agent.service_name=my-service
```

Or JVM flag:

```bash
-Dskywalking.collector.backend_service=<databuff-ingest-host>:11800
```

K8s / containers: replace `oap:11800` with DataBuff Ingest and roll pods.

#### 3. Rollout

1. Change one or two non-critical services and restart  
2. Verify in DataBuff Web (see below)  
3. Expand by domain  
4. Decommission OAP/UI when stable  

### Rollback (Option A)

Set `backend_service` back to OAP and restart. Keep before/after values in the change ticket.

---

## Option B · Dual-write (databuff-proxy)

```
Agent  ──gRPC:11800──▶  databuff-proxy ──▶ SkyWalking OAP
                                   └──▶ DataBuff Ingest
```

Change the Agent once to the proxy. Both backends receive traffic. After DataBuff looks good, disable the OAP path (or point the Agent straight at DataBuff).

Stability evidence (under 1 CPU core, abnormal scenarios OK): [databuff-proxy Dual-Write Stability](./proxy-dual-write-stability_en.md).

### Prerequisites

- DataBuff and SkyWalking OAP are up; proxy can reach both `:11800` endpoints
- A host (or container) for the proxy

### Steps

#### 1. Install and configure proxy

Download from [databuff-proxy Releases](https://github.com/databufflabs/databuff-proxy/releases), edit `config.yaml`:

```yaml
backends:
  - name: skywalking
    addr: "<oap-host>:11800"
    enabled: true
  - name: databuff
    addr: "<databuff-ingest-host>:11800"
    enabled: true
```

```bash
./start.sh
```

Admin UI defaults are in the repo README (`admin.addr`). Details: [CONFIG.md](https://github.com/databufflabs/databuff-proxy/blob/main/CONFIG.md).

#### 2. Point Agent at proxy

```properties
agent.backend_service=<proxy-host>:11800
```

Restart Agents / roll pods. Traffic now dual-writes through the proxy.

#### 3. Validate in parallel

| Check | Expected |
|-------|----------|
| SkyWalking | Existing services and traces still work |
| DataBuff | Same services appear; new traces queryable |
| Proxy | Both paths succeed; no sustained drops; circuit state healthy |

#### 4. Leave SkyWalking

When DataBuff is accepted, either:

- **Disable the skywalking backend** in the proxy admin UI (DataBuff-only immediately), or  
- Point `agent.backend_service` at DataBuff Ingest and retire proxy / OAP  

### Rollback (Option B)

- During dual-write: disable `databuff` on the proxy, or point Agent back to OAP  
- After cutover: point Agent back to OAP (or re-enable the skywalking backend on the proxy)

---

## Acceptance (both options)

| Check | Expected |
|-------|----------|
| Services | Listed under Application Performance |
| Traces | New data; `data.source` = `SkyWalking` |
| JVM / logs | Visible if Agent reports them |
| Alerting | **Re-create** rules in DataBuff (OAP rules do not migrate) |

**Not auto-migrated:** historical OAP traces/metrics and alarm YAML—rebuild in DataBuff as needed.

## See also

- [databuff-proxy Dual-Write Stability](./proxy-dual-write-stability_en.md)
- [databuff-proxy repository](https://github.com/databufflabs/databuff-proxy)
- [DataBuff vs SkyWalking](../业界对比/vs-skywalking_en.md)
- [SkyWalking Ingestion](../使用手册/SkyWalking接入_en.md)
- [Docker Installation](../快速入门/docker安装部署_en.md)
