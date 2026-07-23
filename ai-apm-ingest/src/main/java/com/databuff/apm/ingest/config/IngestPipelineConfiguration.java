package com.databuff.apm.ingest.config;

import com.databuff.apm.common.cluster.aggregate.ClusterAggregator;
import com.databuff.apm.common.cluster.aggregate.ClusterPartialForwarder;
import com.databuff.apm.common.cluster.coordination.ClusterInstanceCoordinator;
import com.databuff.apm.common.cluster.cache.CacheRegionPolicy;
import com.databuff.apm.common.cluster.cache.ClusterCacheRegistry;
import com.databuff.apm.ingest.gateway.PipelineGateway;
import com.databuff.apm.ingest.metric.MetricTableWriterRegistry;
import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.ingest.component.MetricComponent;
import com.databuff.apm.ingest.component.TraceComponent;
import com.databuff.apm.ingest.doris.DorisFlushScheduler;
import com.databuff.apm.ingest.meta.IngestMetaCache;
import com.databuff.apm.ingest.meta.MetaServiceCollector;
import com.databuff.apm.ingest.meta.MetaServiceRegistry;
import com.databuff.apm.ingest.meta.ServiceInstanceRegistry;
import com.databuff.apm.ingest.meta.VirtualServiceInstanceRegistry;
import com.databuff.apm.ingest.trace.SpanResourceIgnoreFilter;
import com.databuff.apm.ingest.trace.VirtualServiceExtractor;
import com.databuff.apm.ingest.trace.remote.RemoteAssociationStore;
import com.databuff.apm.ingest.trace.remote.RemoteCallProcessor;
import com.databuff.apm.ingest.trace.remote.RemoteServiceSettings;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisConnectionConfig;
import com.databuff.apm.common.storage.DorisStreamLoadSink;
import com.databuff.apm.common.storage.DorisStreamLoader;
import com.databuff.apm.common.storage.DorisTableNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
public class IngestPipelineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IngestPipelineConfiguration.class);
    @Bean
    ClusterCacheRegistry clusterCacheRegistry() {
        ClusterCacheRegistry registry = new ClusterCacheRegistry();
        registry.region("ingest.meta", CacheRegionPolicy.LEADER, Duration.ofHours(1));
        registry.region("ingest.remote", CacheRegionPolicy.REPLICATED, Duration.ofHours(72));
        return registry;
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    MetaServiceRegistry metaServiceRegistry(
            ApmReadRepository ingestApmReadRepository,
            @Value("${ingest.doris.metric-database:databuff}") String database,
            @Value("${ingest.meta-service.sync-interval-ms:60000}") long syncIntervalMs) {
        return new MetaServiceRegistry(ingestApmReadRepository, database, syncIntervalMs);
    }

    @Bean
    IngestMetaCache ingestMetaCache(ClusterCacheRegistry clusterCacheRegistry, MetaServiceRegistry metaServiceRegistry) {
        return new IngestMetaCache(clusterCacheRegistry, metaServiceRegistry);
    }

    @Bean
    ClusterAggregator clusterAggregator(ClusterInstanceCoordinator coordinator) {
        return new ClusterAggregator(coordinator.localNodeId());
    }

    @Bean
    DorisConnectionConfig dorisConnectionConfig(
            @Value("${ingest.doris.fe-host:127.0.0.1}") String feHost,
            @Value("${ingest.doris.fe-query-port:9030}") int queryPort,
            @Value("${ingest.doris.fe-http-port:8030}") int httpPort,
            @Value("${ingest.doris.be-http-host:}") String beHttpHost,
            @Value("${ingest.doris.be-http-port:8040}") int beHttpPort) {
        String beHost = beHttpHost == null || beHttpHost.isBlank() ? feHost.trim() : beHttpHost.trim();
        return new DorisConnectionConfig(feHost, queryPort, httpPort, beHost, beHttpPort);
    }

    @Bean
    DorisStreamLoader dorisStreamLoader(
            DorisConnectionConfig config,
            @Value("${ingest.doris.username:root}") String username,
            @Value("${ingest.doris.password:}") String password) {
        return new DorisStreamLoader(config, username, password);
    }

    @Bean
    DorisBatchWriter traceBatchWriter() {
        return new DorisBatchWriter(2048);
    }

    @Bean
    DorisBatchWriter logBatchWriter() {
        return new DorisBatchWriter(1024);
    }

    @Bean
    DorisBatchWriter metaServiceBatchWriter() {
        return new DorisBatchWriter(256);
    }

    @Bean
    MetaServiceCollector metaServiceCollector(
            MetaServiceRegistry metaServiceRegistry,
            DorisBatchWriter metaServiceBatchWriter) {
        return new MetaServiceCollector(metaServiceRegistry, metaServiceBatchWriter);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    ServiceInstanceRegistry serviceInstanceRegistry(
            MetricWriteRouter metricWriteRouter,
            @Value("${ingest.service-instance.flush-interval-ms:60000}") long flushIntervalMs) {
        return new ServiceInstanceRegistry(metricWriteRouter, flushIntervalMs);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    VirtualServiceInstanceRegistry virtualServiceInstanceRegistry(
            MetricWriteRouter metricWriteRouter,
            @Value("${ingest.service-instance.flush-interval-ms:60000}") long flushIntervalMs) {
        return new VirtualServiceInstanceRegistry(metricWriteRouter, flushIntervalMs);
    }

    @Bean
    RemoteAssociationStore remoteAssociationStore(ClusterCacheRegistry clusterCacheRegistry) {
        return new RemoteAssociationStore(clusterCacheRegistry.get("ingest.remote"));
    }

    @Bean
    RemoteServiceSettings remoteServiceSettings(
            @Value("${ingest.remote.enable:true}") boolean enabled,
            @Value("${ingest.remote.first-protect-time-ms:3600000}") long protectTimeMs,
            @Value("${ingest.remote.merge-services:}") List<String> mergeServices) {
        return new RemoteServiceSettings(enabled, protectTimeMs, mergeServices);
    }

    @Bean
    VirtualServiceExtractor virtualServiceExtractor(
            VirtualServiceInstanceRegistry virtualServiceInstanceRegistry,
            MetaServiceCollector metaServiceCollector) {
        return new VirtualServiceExtractor(virtualServiceInstanceRegistry, metaServiceCollector);
    }

    @Bean
    RemoteCallProcessor remoteCallProcessor(
            RemoteServiceSettings remoteServiceSettings,
            RemoteAssociationStore remoteAssociationStore,
            MetaServiceRegistry metaServiceRegistry,
            VirtualServiceExtractor virtualServiceExtractor) {
        return new RemoteCallProcessor(
                remoteServiceSettings,
                null,
                remoteAssociationStore,
                metaServiceRegistry,
                virtualServiceExtractor);
    }

    @Bean
    DorisStreamLoadSink metaServiceStreamLoadSink(
            DorisBatchWriter metaServiceBatchWriter,
            DorisStreamLoader loader,
            @Value("${ingest.doris.metric-database:databuff}") String database,
            @Value("${ingest.doris.stream-load-max-failures:3}") int streamLoadMaxFailures) {
        return new DorisStreamLoadSink(
                metaServiceBatchWriter, loader, database, DorisTableNames.META_SERVICE, streamLoadMaxFailures);
    }

    @Bean
    DorisStreamLoadSink traceStreamLoadSink(
            DorisBatchWriter traceBatchWriter,
            DorisStreamLoader loader,
            @Value("${ingest.doris.trace-database:databuff}") String database,
            @Value("${ingest.doris.trace-table:trace_dc_span}") String table,
            @Value("${ingest.doris.stream-load-max-failures:3}") int streamLoadMaxFailures) {
        return new DorisStreamLoadSink(traceBatchWriter, loader, database, table, streamLoadMaxFailures);
    }

    @Bean
    DorisStreamLoadSink logStreamLoadSink(
            DorisBatchWriter logBatchWriter,
            DorisStreamLoader loader,
            @Value("${ingest.doris.trace-database:databuff}") String database,
            @Value("${ingest.doris.log-table:log_dc_record}") String table,
            @Value("${ingest.doris.stream-load-max-failures:3}") int streamLoadMaxFailures) {
        return new DorisStreamLoadSink(logBatchWriter, loader, database, table, streamLoadMaxFailures);
    }

    @Bean
    MetricTableWriterRegistry metricTableWriterRegistry(
            DorisStreamLoader loader,
            @Value("${ingest.doris.metric-database:databuff}") String database,
            @Value("${ingest.doris.stream-load-max-failures:3}") int streamLoadMaxFailures) {
        return MetricTableWriterRegistry.create(loader, database, streamLoadMaxFailures);
    }

    @Bean
    MetricWriteRouter metricWriteRouter(MetricTableWriterRegistry metricTableWriterRegistry) {
        return new MetricWriteRouter(metricTableWriterRegistry.writersByTable());
    }

    @Bean("dorisStreamLoadSinks")
    List<DorisStreamLoadSink> dorisStreamLoadSinks(
            MetricTableWriterRegistry metricTableWriterRegistry,
            DorisStreamLoadSink metaServiceStreamLoadSink,
            DorisStreamLoadSink traceStreamLoadSink,
            DorisStreamLoadSink logStreamLoadSink) {
        List<DorisStreamLoadSink> sinks = new java.util.ArrayList<>(metricTableWriterRegistry.sinks());
        sinks.add(metaServiceStreamLoadSink);
        sinks.add(traceStreamLoadSink);
        sinks.add(logStreamLoadSink);
        return sinks;
    }

    @Bean
    AggregateComponent aggregateComponent(
            ClusterInstanceCoordinator coordinator,
            ClusterAggregator clusterAggregator,
            MetricWriteRouter metricWriteRouter,
            ClusterPartialForwarder partialForwarder,
            MetaServiceCollector metaServiceCollector,
            @Value("${ingest.metric.trace-minute-late-flush-grace-ms:20000}") long traceMinuteLateFlushGraceMs,
            @Value("${ingest.pipeline.aggregate-tasks:4}") int aggregateTasks,
            @Value("${ingest.pipeline.aggregate-buffer-size:1024}") int aggregateBufferSize) {
        AggregateComponent component = new AggregateComponent(
                clusterAggregator,
                coordinator,
                metricWriteRouter,
                partialForwarder,
                metaServiceCollector,
                traceMinuteLateFlushGraceMs,
                aggregateBufferSize);
        component.start(Math.max(1, aggregateTasks));
        return component;
    }

    @Bean
    MetricComponent metricComponent(
            AggregateComponent aggregateComponent,
            @Value("${ingest.pipeline.metric-tasks:4}") int metricTasks,
            @Value("${ingest.pipeline.metric-buffer-size:1024}") int metricBufferSize) {
        MetricComponent component = new MetricComponent(aggregateComponent, metricBufferSize);
        component.start(Math.max(1, metricTasks));
        return component;
    }

    @Bean
    SpanResourceIgnoreFilter spanResourceIgnoreFilter(
            @Value("${ingest.trace.ignore-resources:}") List<String> ignoreResources,
            @Value("${ingest.trace.ignore-resource-regex:}") List<String> ignoreResourceRegex) {
        SpanResourceIgnoreFilter filter = new SpanResourceIgnoreFilter(ignoreResources, ignoreResourceRegex);
        if (!filter.isEmpty()) {
            log.info("Span resource ignore filter enabled: {}", filter);
        }
        return filter;
    }

    @Bean
    TraceComponent traceComponent(
            AggregateComponent aggregateComponent,
            DorisBatchWriter traceBatchWriter,
            IngestMetaCache ingestMetaCache,
            MetaServiceCollector metaServiceCollector,
            ServiceInstanceRegistry serviceInstanceRegistry,
            VirtualServiceExtractor virtualServiceExtractor,
            RemoteCallProcessor remoteCallProcessor,
            ClusterInstanceCoordinator coordinator,
            ClusterPartialForwarder partialForwarder,
            DorisFlushScheduler dorisFlushScheduler,
            SpanResourceIgnoreFilter spanResourceIgnoreFilter,
            @Value("${ingest.trace.assembly-check-interval-ms:2000}") long assemblyCheckIntervalMs,
            @Value("${ingest.pipeline.trace-tasks:4}") int traceTasks,
            @Value("${ingest.pipeline.trace-buffer-size:1024}") int traceBufferSize) {
        TraceComponent component = new TraceComponent(
                aggregateComponent,
                traceBatchWriter,
                ingestMetaCache,
                metaServiceCollector,
                serviceInstanceRegistry,
                virtualServiceExtractor,
                remoteCallProcessor,
                coordinator,
                partialForwarder,
                dorisFlushScheduler,
                spanResourceIgnoreFilter,
                assemblyCheckIntervalMs,
                traceBufferSize);
        component.start(Math.max(1, traceTasks));
        return component;
    }

    @Bean
    PipelineGateway pipelineGateway(TraceComponent traceComponent, MetricComponent metricComponent) {
        return new PipelineGateway(traceComponent, metricComponent);
    }
}
