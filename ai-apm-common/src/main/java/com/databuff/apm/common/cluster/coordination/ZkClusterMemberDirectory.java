package com.databuff.apm.common.cluster.coordination;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * ZooKeeper-backed member directory. Ephemeral znodes under {@link ZkMemberEntries#MEMBERS_ROOT}
 * hold gRPC endpoints; falls back to the local node only when ZK is unavailable.
 */
public final class ZkClusterMemberDirectory implements ClusterMemberDirectory, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZkClusterMemberDirectory.class);
    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    private final String localNodeId;
    private final CuratorFramework client;
    private final PathChildrenCache cache;
    private volatile Map<String, String> endpointsByNodeId = Map.of();

    private ZkClusterMemberDirectory(
            String localNodeId,
            CuratorFramework client,
            PathChildrenCache cache,
            Map<String, String> initialMembers) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.client = client;
        this.cache = cache;
        this.endpointsByNodeId = initialMembers;
    }

    public static ClusterMemberDirectory create(String localNodeId, String zkConnectString, String localEndpoint) {
        return create(localNodeId, "ingest", zkConnectString, localEndpoint);
    }

    public static ClusterMemberDirectory create(
            String localNodeId, String role, String zkConnectString, String localEndpoint) {
        if (zkConnectString == null || zkConnectString.isBlank()) {
            return StaticClusterMemberDirectory.local(localNodeId, localEndpoint);
        }
        try {
            return connect(localNodeId, role, zkConnectString, localEndpoint);
        } catch (Exception e) {
            log.warn("ZooKeeper membership unavailable ({}), using local node only", e.getMessage());
            return StaticClusterMemberDirectory.local(localNodeId, localEndpoint);
        }
    }

    private static ZkClusterMemberDirectory connect(
            String localNodeId, String role, String zkConnectString, String localEndpoint) throws Exception {
        CuratorFramework client = CuratorFrameworkFactory.newClient(
                zkConnectString.trim(),
                new ExponentialBackoffRetry(500, 3));
        client.start();
        if (!client.blockUntilConnected(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            client.close();
            throw new IllegalStateException("ZK connect timeout");
        }

        String membersRoot = ZkMemberEntries.membersRoot(role);
        client.createContainers(membersRoot);

        if (localEndpoint != null && !localEndpoint.isBlank()) {
            String path = ZkMemberEntries.memberPath(role, localNodeId);
            if (client.checkExists().forPath(path) != null) {
                client.delete().forPath(path);
            }
            client.create()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(path, localEndpoint.trim().getBytes(StandardCharsets.UTF_8));
        } else {
            log.warn("No local endpoint for node {}, skipping ZK registration", localNodeId);
        }

        PathChildrenCache cache = new PathChildrenCache(client, membersRoot, true);
        cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
        Map<String, String> initial = ZkMemberEntries.fromChildren(cache.getCurrentData());
        if (initial.isEmpty()) {
            initial = StaticClusterMemberDirectory.local(localNodeId, localEndpoint).endpointsByNodeId();
        }

        ZkClusterMemberDirectory directory = new ZkClusterMemberDirectory(localNodeId, client, cache, initial);
        cache.getListenable().addListener((client1, event) -> directory.onCacheEvent(event));
        log.info("ZK cluster membership ready at {} ({} members)", zkConnectString, initial.size());
        return directory;
    }

    private void onCacheEvent(PathChildrenCacheEvent event) {
        Map<String, String> latest = ZkMemberEntries.fromChildren(cache.getCurrentData());
        if (!latest.isEmpty()) {
            Map<String, String> previous = endpointsByNodeId;
            endpointsByNodeId = latest;
            if (!latest.equals(previous)) {
                log.info(
                        "ZK cluster membership changed localNodeId={} {}",
                        localNodeId,
                        membershipDelta(previous, latest));
            }
        }
    }

    private static String membershipDelta(Map<String, String> previous, Map<String, String> latest) {
        java.util.List<String> added = latest.keySet().stream()
                .filter(id -> !previous.containsKey(id))
                .sorted()
                .toList();
        java.util.List<String> removed = previous.keySet().stream()
                .filter(id -> !latest.containsKey(id))
                .sorted()
                .toList();
        java.util.List<String> endpointChanged = latest.keySet().stream()
                .filter(previous::containsKey)
                .filter(id -> !previous.get(id).equals(latest.get(id)))
                .sorted()
                .toList();
        return "members=" + latest.size()
                + " added=" + added
                + " removed=" + removed
                + " endpointChanged=" + endpointChanged;
    }

    @Override
    public String localNodeId() {
        return localNodeId;
    }

    @Override
    public Map<String, String> endpointsByNodeId() {
        return endpointsByNodeId;
    }

    @Override
    public void close() {
        try {
            cache.close();
        } catch (Exception ignored) {
            // best effort
        }
        try {
            client.close();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
