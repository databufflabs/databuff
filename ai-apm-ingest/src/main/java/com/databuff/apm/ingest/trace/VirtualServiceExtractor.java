package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.meta.MetaServiceInfo;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.meta.VirtualServiceResolver;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.ingest.meta.MetaServiceCollector;
import com.databuff.apm.ingest.meta.VirtualServiceInstanceRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * fill 完成后从出站组件 span 提取虚拟服务实例，与历史 ingest 流水线 {@code OutProcessor} +
 * {@code ComponentInstanceService} 行为一致。
 */
public final class VirtualServiceExtractor {

    private final VirtualServiceInstanceRegistry virtualServiceInstanceRegistry;
    private final MetaServiceCollector metaServiceCollector;

    public VirtualServiceExtractor(VirtualServiceInstanceRegistry virtualServiceInstanceRegistry) {
        this(virtualServiceInstanceRegistry, null);
    }

    public VirtualServiceExtractor(
            VirtualServiceInstanceRegistry virtualServiceInstanceRegistry,
            MetaServiceCollector metaServiceCollector) {
        this.virtualServiceInstanceRegistry = Objects.requireNonNull(virtualServiceInstanceRegistry);
        this.metaServiceCollector = metaServiceCollector;
    }

    public void extractFromTrace(List<DcSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }
        Set<String> tracedServices = collectTracedServices(spans);
        for (DcSpan span : spans) {
            extractFromSpan(span, tracedServices);
        }
    }

    public void extractFromSpan(DcSpan span) {
        extractFromSpan(span, Set.of());
    }

    public void extractFromSpan(DcSpan span, Set<String> tracedServices) {
        VirtualServiceResolver.ResolvedVirtualService resolved = VirtualServiceResolver.resolve(span, tracedServices);
        if (resolved == null) {
            return;
        }
        applyResolved(span, resolved, false);
    }

    /** Apply an already-resolved virtual service (remote path uses {@code remote=true}). */
    public void applyResolved(
            DcSpan span,
            VirtualServiceResolver.ResolvedVirtualService resolved,
            boolean remote) {
        if (resolved == null) {
            return;
        }
        applyVirtualDestination(span, resolved);
        virtualServiceInstanceRegistry.remember(resolved);
        if (metaServiceCollector != null) {
            metaServiceCollector.remember(MetaServiceInfo.fromVirtualService(
                    resolved.serviceId(),
                    resolved.service(),
                    resolved.serviceType(),
                    resolved.typeIcon()));
        }
    }

    private static Set<String> collectTracedServices(List<DcSpan> spans) {
        Set<String> services = new HashSet<>();
        for (DcSpan span : spans) {
            if (span.service != null && !span.service.isBlank()) {
                services.add(VirtualServiceResolver.normalizePeerHost(span.service));
            }
        }
        return services;
    }

    private static void applyVirtualDestination(DcSpan span, VirtualServiceResolver.ResolvedVirtualService resolved) {
        // Legacy portal OutProcessor#initComponentService: reassign span to virtual service dimension.
        span.service = resolved.service();
        span.serviceId = resolved.serviceId();
        span.serviceInstance = resolved.serviceInstance();
        span.dstService = resolved.service();
        span.dstServiceId = resolved.serviceId();
        span.dstServiceInstance = resolved.serviceInstance();
        // Legacy portal: virtual service entry spans are marked isIn=1.
        span.isIn = 1;
        if (span.isOut == 0) {
            span.isOut = 1;
        }
        if (isRemoteVirtual(resolved, span)) {
            markRemote(span);
        }
        clearRedundantPeerHostname(span);
    }

    /**
     * MQ/ES virtual services already encode the destination in {@code service}; peer hostname edges
     * such as {@code [kafka]topic -> [peer] kafka} are redundant and must not appear in topology.
     */
    private static void clearRedundantPeerHostname(DcSpan span) {
        if (DcSpanUtil.isMqSpan(span) || DcSpanUtil.isEsSpan(span)) {
            span.metaPeerHostname = null;
        }
    }

    private static boolean isRemoteVirtual(
            VirtualServiceResolver.ResolvedVirtualService resolved, DcSpan span) {
        return "custom".equals(resolved.serviceType())
                && (DcSpanUtil.isHttpSpan(span) || DcSpanUtil.isRpcSpan(span));
    }

    private static void markRemote(DcSpan span) {
        OtelAttributeMaps.put(span, "remote", "true");
    }
}
