package com.databuff.apm.ingest.trace.remote;

import com.databuff.apm.common.meta.MetaServiceInfo;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.meta.VirtualComponentNaming;
import com.databuff.apm.common.meta.VirtualServiceResolver;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.common.util.ServiceKeyUtil;
import com.databuff.apm.ingest.trace.VirtualServiceExtractor;
import com.databuff.apm.ingest.meta.MetaServiceRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Remote HTTP/RPC virtual service generation; legacy ingest pipeline compatibility
 * ({@code HttpRpcClientOutProcessor}).
 */
public final class RemoteCallProcessor {

    private final RemoteServiceSettings settings;
    private final PeerServerServiceCache peerServerServiceCache;
    private final RemoteAssociationStore associationStore;
    private final MetaServiceRegistry metaServiceRegistry;
    private final VirtualServiceExtractor virtualServiceExtractor;

    public RemoteCallProcessor(
            RemoteServiceSettings settings,
            PeerServerServiceCache peerServerServiceCache,
            RemoteAssociationStore associationStore,
            MetaServiceRegistry metaServiceRegistry,
            VirtualServiceExtractor virtualServiceExtractor) {
        this.settings = settings == null ? RemoteServiceSettings.defaults() : settings;
        this.peerServerServiceCache = peerServerServiceCache == null
                ? new PeerServerServiceCache()
                : peerServerServiceCache;
        this.associationStore = Objects.requireNonNull(associationStore);
        this.metaServiceRegistry = metaServiceRegistry;
        this.virtualServiceExtractor = virtualServiceExtractor;
    }

    public static RemoteCallProcessor createDefault(
            MetaServiceRegistry metaServiceRegistry,
            VirtualServiceExtractor virtualServiceExtractor,
            RemoteAssociationStore associationStore) {
        return new RemoteCallProcessor(
                RemoteServiceSettings.defaults(),
                new PeerServerServiceCache(),
                associationStore,
                metaServiceRegistry,
                virtualServiceExtractor);
    }

    /** Per-span enrich hook: cache/backfill {@code server.service}. */
    public void enrichSpan(DcSpan span) {
        peerServerServiceCache.cacheFillServerService(span);
    }

    /**
     * Trace-level remote processing after {@code fillRelations}.
     */
    public void processAfterFill(List<DcSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }
        recordTraceRemoteAssociations(spans);
        for (DcSpan span : spans) {
            processClientSpan(span);
        }
    }

    private void processClientSpan(DcSpan span) {
        if (isComponentOutboundSpan(span) || !isHttpOrRpcClient(span) || isLinkedToRealService(span)) {
            return;
        }
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        String serverService = OtelAttributeMaps.firstNonBlank(meta, "server.service");
        if (serverService != null && !serverService.isBlank()) {
            applyServerService(span, serverService.trim(), meta);
            return;
        }
        if (!settings.enabled()) {
            return;
        }
        externalRemoteCalls(span, meta);
    }

    private void applyServerService(DcSpan span, String serverService, Map<String, String> meta) {
        String serverIp = OtelAttributeMaps.firstNonBlank(meta, "server.ip", "server.address", "net.peer.name");
        String serviceId = resolveServiceId(serverService);
        span.dstService = serverService;
        span.dstServiceId = serviceId;
        span.dstServiceInstance = serverIp == null ? "" : serverIp.trim();
    }

    private boolean externalRemoteCalls(DcSpan span, Map<String, String> meta) {
        RemotePeerAddressUtil.RemotePeer peer = RemotePeerAddressUtil.resolve(span, meta);
        if (peer == null) {
            return false;
        }
        String dataSource = TraceDataSources.resolve(meta);
        String remoteService = peer.remoteServiceKey();
        String peerKey = dataSource + ":" + remoteService;
        String apiKey = RemoteAssociationStore.defaultApiKey();

        for (String mergePattern : settings.mergeServices()) {
            String mergeKey = peer.type() + "-" + peer.hostname();
            if (mergePattern.equals(mergeKey)) {
                if (!errorSpanRemoteServiceAllowed(span, peer)) {
                    return false;
                }
                return initRemoteSpan(span, meta, peer.type(), mergePattern, peer.hostname());
            }
        }

        if (!errorSpanRemoteServiceAllowed(span, peer)) {
            return false;
        }

        if (TraceDataSources.isDatabuffSource(dataSource)) {
            return initRemoteSpan(span, meta, peer.type(), remoteService, peer.hostname());
        }

        String associationCacheKey = peerKey;
        if (associationStore.isConfirmedLinked(apiKey, associationCacheKey)) {
            return false;
        }
        if (associationStore.enterProtectPeriodIfNeeded(peerKey, settings.protectTimeMs())) {
            return false;
        }

        String association = associationStore.readAssociation(apiKey, associationCacheKey);
        if (association == null) {
            return false;
        }
        if (!association.isBlank()) {
            return false;
        }
        return initRemoteSpan(span, meta, peer.type(), remoteService, peer.hostname());
    }

    private boolean initRemoteSpan(
            DcSpan span,
            Map<String, String> meta,
            String type,
            String remoteService,
            String peerHostname) {
        VirtualComponentNaming.NamedInstance named = VirtualComponentNaming.resolve(
                "remote",
                null,
                peerHostname,
                extractPort(meta),
                span.service);
        if (named == null || named.service().isBlank()) {
            return false;
        }
        String serviceId = ServiceKeyUtil.of(named.service());
        VirtualServiceResolver.ResolvedVirtualService resolved = new VirtualServiceResolver.ResolvedVirtualService(
                serviceId,
                named.service(),
                named.serviceInstance(),
                "custom",
                type.toLowerCase(),
                peerHostname,
                extractPort(meta));

        span.isIn = 1;
        OtelAttributeMaps.put(span, "remote", "true");

        if (virtualServiceExtractor != null) {
            virtualServiceExtractor.applyResolved(span, resolved, true);
        } else {
            applyResolved(span, resolved);
        }
        return true;
    }

    private void applyResolved(DcSpan span, VirtualServiceResolver.ResolvedVirtualService resolved) {
        span.service = resolved.service();
        span.serviceId = resolved.serviceId();
        span.serviceInstance = resolved.serviceInstance();
        span.dstService = resolved.service();
        span.dstServiceId = resolved.serviceId();
        span.dstServiceInstance = resolved.serviceInstance();
        span.isIn = 1;
        if (span.isOut == 0) {
            span.isOut = 1;
        }
    }

    private boolean errorSpanRemoteServiceAllowed(DcSpan span, RemotePeerAddressUtil.RemotePeer peer) {
        if (span.error != 1 || metaServiceRegistry == null || peer == null) {
            return true;
        }
        VirtualComponentNaming.NamedInstance named = VirtualComponentNaming.resolve(
                "remote", null, peer.hostname(), peer.port(), span.service);
        if (named == null || named.service().isBlank()) {
            return false;
        }
        return metaServiceRegistry.getByName(named.service()).isPresent();
    }

    private void recordTraceRemoteAssociations(List<DcSpan> spans) {
        String apiKey = RemoteAssociationStore.defaultApiKey();
        for (DcSpan span : spans) {
            if (!isHttpOrRpcClient(span)) {
                continue;
            }
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            String dataSource = TraceDataSources.resolve(meta);
            if (TraceDataSources.isDatabuffSource(dataSource)) {
                continue;
            }
            RemotePeerAddressUtil.RemotePeer peer = RemotePeerAddressUtil.resolve(span, meta);
            if (peer == null) {
                continue;
            }
            String peerKey = dataSource + ":" + peer.remoteServiceKey();
            String linkedValue = "";
            if (isLinkedToRealService(span)) {
                String instance = span.dstServiceInstance == null ? "" : span.dstServiceInstance;
                linkedValue = span.dstServiceId + "_" + instance;
            }
            associationStore.recordAssociation(apiKey, peerKey, linkedValue);
        }
    }

    private String resolveServiceId(String serverService) {
        if (metaServiceRegistry != null) {
            Optional<MetaServiceInfo> known = metaServiceRegistry.getByName(serverService);
            if (known.isPresent()) {
                return known.get().id();
            }
        }
        return ServiceKeyUtil.of(serverService);
    }

    private static boolean isHttpOrRpcClient(DcSpan span) {
        return span != null
                && "SPAN_KIND_CLIENT".equals(span.type)
                && span.isOut == 1
                && (DcSpanUtil.isHttpSpan(span) || DcSpanUtil.isRpcSpan(span));
    }

    /** DB/MQ/Redis/ES outbound spans are handled by {@link VirtualServiceExtractor}, not remote. */
    private static boolean isComponentOutboundSpan(DcSpan span) {
        return DcSpanUtil.isDbSpan(span)
                || DcSpanUtil.isEsSpan(span)
                || DcSpanUtil.isRedisSpan(span)
                || DcSpanUtil.isMqSpan(span);
    }

    static boolean isLinkedToRealService(DcSpan span) {
        if (span.dstServiceId == null || span.dstServiceId.isBlank()) {
            return false;
        }
        if (span.dstService == null || span.dstService.isBlank() || span.dstService.startsWith("[")) {
            return false;
        }
        if (span.service != null && span.service.equals(span.dstService)) {
            return false;
        }
        if (span.serviceId != null && span.serviceId.equals(span.dstServiceId)) {
            return false;
        }
        return true;
    }

    private static String extractPort(Map<String, String> meta) {
        return OtelAttributeMaps.firstNonBlank(
                meta, "server.port", "net.peer.port", "peer.port", "db.connection_string.port");
    }

}
