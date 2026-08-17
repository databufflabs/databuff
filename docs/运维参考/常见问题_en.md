<p align="center">
  <a href="常见问题.md">中文</a>
  &nbsp;|&nbsp;
  <a href="常见问题_en.md">English</a>
</p>

# FAQ

Known issues seen during install or startup. Day-to-day Docker / Kubernetes operations: [Docker Operations](Docker运维_en.md), [Kubernetes Operations](K8s运维_en.md).

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
        grep -q 'UseContainerSupport' /opt/apache-doris/fe/conf/fe.conf \
          || sed -i 's/^JAVA_OPTS_FOR_JDK_17="/JAVA_OPTS_FOR_JDK_17="-XX:-UseContainerSupport /' /opt/apache-doris/fe/conf/fe.conf
        exec bash init_fe.sh
```

The same two `grep` / `sed` lines apply to `deploy/local/docker-compose.yml`; leave the 512m heap patch as-is.

**Already running: edit the container config and restart:**

```bash
docker exec ai-apm-doris-fe sed -i \
  's/^JAVA_OPTS_FOR_JDK_17="/JAVA_OPTS_FOR_JDK_17="-XX:-UseContainerSupport /' \
  /opt/apache-doris/fe/conf/fe.conf
docker restart ai-apm-doris-fe
```

Do not append the flag again if `fe.conf` already contains `UseContainerSupport`.

**Kubernetes:** in the FE container command in `deploy/k8s/manifests/doris.yaml`, add the same two `grep` / `sed` lines after the `-Xmx` patch and before `exec bash init_fe.sh`.

### Verify

```bash
docker logs ai-apm-doris-fe 2>&1 | tail -50
# should not show CgroupInfo.getMountPoint / anyController is null

docker exec ai-apm-doris-fe grep JAVA_OPTS_FOR_JDK_17 /opt/apache-doris/fe/conf/fe.conf
# should include -XX:-UseContainerSupport
```

Ingest and web stay down until FE answers on `8030` / `9030`.

## See also

- [Docker Operations](Docker运维_en.md)
- [Kubernetes Operations](K8s运维_en.md)
- [Parameter Configuration](参数配置_en.md)
