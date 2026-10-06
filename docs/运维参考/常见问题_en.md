<p align="center">
  <a href="常见问题.md">中文</a>
  &nbsp;|&nbsp;
  <a href="常见问题_en.md">English</a>
</p>

# FAQ

Known issues seen during install, startup, or operation. Day-to-day Docker / Kubernetes operations: [Docker Operations](Docker运维_en.md), [Kubernetes Operations](K8s运维_en.md).

## Doris FE fails to start: `CgroupInfo.getMountPoint()` NPE

### Symptom

`ai-apm-doris-fe` never becomes healthy. Logs contain:

```text
Caused by: java.lang.NullPointerException: Cannot invoke "jdk.internal.platform.CgroupInfo.getMountPoint()" because "anyController" is null
    at java.base/jdk.internal.platform.cgroupv2.CgroupV2Subsystem.getInstance(CgroupV2Subsystem.java:81)
```

Seen on Ubuntu 24.04, newer kernels (e.g. 6.12+), Docker 27/28, and cgroup v2. BDB JE reads JVM OS/container metrics during init; the NPE kills the FE process.

### Cause

This is a **JDK cgroup v2 probe failure**, not DataBuff application code. Official `apache/doris:fe-4.1.1` (and the current latest `fe-4.1.3`) ships BiSheng OpenJDK **17.0.11**. `JAVA_OPTS_FOR_JDK_17` in `fe.conf` does not disable container detection. Moving to `fe-4.1.3` **does not fix it** — Apache Doris issues [#56784](https://github.com/apache/doris/issues/56784) and [#60536](https://github.com/apache/doris/issues/60536) were closed as stale, not shipped as a release fix.

### Fix

Prepend `-XX:-UseContainerSupport` to `JAVA_OPTS_FOR_JDK_17`. After that, the JVM no longer sizes the heap from cgroup limits, so **keep** the existing `-Xmx` patch (1200m in the Docker install compose; 512m in the local-dev compose).

**Docker: patch the FE `command` in `docker-compose.yml`** (after the heap `sed` lines, before `exec bash init_fe.sh`):

```yaml
    command:
      - |
        sed -i 's/-Xmx8192m/-Xmx1200m/g' /opt/apache-doris/fe/conf/fe.conf
        sed -i 's/-Xms8192m/-Xms1200m/g' /opt/apache-doris/fe/conf/fe.conf
        grep -q -- '-XX:-UseContainerSupport' /opt/apache-doris/fe/conf/fe.conf \
          || sed -i 's/^JAVA_OPTS_FOR_JDK_17="/JAVA_OPTS_FOR_JDK_17="-XX:-UseContainerSupport /' /opt/apache-doris/fe/conf/fe.conf
        exec bash init_fe.sh
```

The same two `grep` / `sed` lines apply to `deploy/local/docker-compose.yml`; leave the 512m heap patch as-is.

**Already running: edit the container config and restart:**

```bash
docker exec ai-apm-doris-fe grep -q -- '-XX:-UseContainerSupport' \
  /opt/apache-doris/fe/conf/fe.conf \
  || docker exec ai-apm-doris-fe sed -i \
    's/^JAVA_OPTS_FOR_JDK_17="/JAVA_OPTS_FOR_JDK_17="-XX:-UseContainerSupport /' \
    /opt/apache-doris/fe/conf/fe.conf
docker restart ai-apm-doris-fe
```

The command is safe to repeat; it does not append the option when `fe.conf` already contains it.

**Kubernetes:** in the FE container command in `deploy/k8s/manifests/doris.yaml`, add the same two `grep` / `sed` lines after the `-Xmx` patch and before `exec bash init_fe.sh`.

### Verify

```bash
docker logs ai-apm-doris-fe 2>&1 | tail -50
# should not show CgroupInfo.getMountPoint / anyController is null

docker exec ai-apm-doris-fe grep JAVA_OPTS_FOR_JDK_17 /opt/apache-doris/fe/conf/fe.conf
# should include -XX:-UseContainerSupport
```

Ingest and web stay down until FE answers on `8030` / `9030`.

## Model connectivity: curl works, but the connectivity test fails with `Model request timeout after PT30S`

### Symptom

In the AI platform's model provider settings, with the same Base URL and API key: curl from the host returns 200 from `<Base-URL>/chat/completions` in under a second, while DataBuff's connectivity test reports `Model request timeout after PT30S` after about 30 seconds (real chats time out the same way). Mostly seen in intranet deployments (vLLM, or model services behind a gateway / WAF).

### Cause

The connectivity test is issued by the AgentScope SDK **inside the `ai-apm-web` container**, which differs from host-side curl in two ways:

- **Runtime environment**: the test runs in a container (custom bridge network). If the intranet domain is resolved via the host's `/etc/hosts` or an intranet DNS, the container cannot read the host's `/etc/hosts`; and if the host uses systemd-resolved (`/etc/resolv.conf` pointing to `127.0.0.53`), the container cannot resolve it either.
- **HTTP version**: the AgentScope transport defaults to HTTP/2 (negotiated via TLS ALPN); older curl builds do not support h2 and actually speak HTTP/1.1. When a gateway / WAF accepts the JDK's h2 connection but never sends a response, the request hangs silently.

`PT30S` is the probe's overall timeout covering connect + TLS + response. DNS failures, untrusted certificates, and refused ports all fail within seconds; **a full 30-second hang means the request stalled silently**, which points to one of the two causes above.

### Fix

**Step 1: force HTTP/1.1 (cheapest, try first).** Add one line to the `environment` block of the `ai-apm-web` service in the compose file (Docker install: `deploy/docker/docker-compose.yml`; local dev: `deploy/local/docker-compose.yml`):

```yaml
      APM_AGENT_LLM_FORCE_HTTP11: "true"
```

This maps to the `apm.agent.llm-force-http11` config key (every shipped application.yml already carries the placeholder — no code change needed). After startup, both the connectivity test and real LLM calls go over HTTP/1.1. Then **recreate the container** — `docker restart` does not pick up environment changes:

```bash
docker compose up -d --force-recreate ai-apm-web
docker logs ai-apm-web 2>&1 | grep -i "HTTP/1.1"
# should show: AgentScope LLM HTTP transport forced to HTTP/1.1 (apm.agent.llm-force-http11=true)
```

**Step 2: if it still times out, check the container-to-gateway network path** (substitute the real domain / key / model ID):

```bash
docker exec ai-apm-web getent hosts llm.example.internal   # compare the resolved IP with the host's
docker exec ai-apm-web timeout 5 bash -c 'exec 3<>/dev/tcp/llm.example.internal/443' && echo TCP-OK
docker exec ai-apm-web curl -sS -m 10 \
  -H "Authorization: Bearer <API-Key>" -H 'Content-Type: application/json' \
  -d '{"model":"<model-id>","messages":[{"role":"user","content":"ping"}]}' \
  https://llm.example.internal/v1/chat/completions
```

If curl inside the container also times out → network problem: add an `extra_hosts` entry (intranet domain → IP) to `ai-apm-web` in the compose file, or point the container at a working intranet DNS. If curl inside the container is fast while the platform still times out → go back to step 1 and confirm the HTTP/1.1 switch is active.

### Verify

The connectivity test reports success. Other error messages map directly to causes:

- `UnknownHostException`: container DNS problem — follow step 2;
- `PKIX path building failed`: the gateway certificate is not in the JDK truststore (curl uses the OS certificate store, Java uses its own cacerts) — import the gateway certificate chain into the container JDK's cacerts;
- `404 ... model not found`: model ID mismatch. vLLM is case-sensitive — if the server serves `deepseek-v4`, entering `DeepSeek-V4` fails; use what `/v1/models` returns;
- `401`: wrong API key.

## See also

- [Docker Operations](Docker运维_en.md)
- [Kubernetes Operations](K8s运维_en.md)
- [Parameter Configuration](参数配置_en.md)
