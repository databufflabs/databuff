package com.databuff.apm.web.ai.tool;

import com.databuff.apm.common.query.ApmQueryModels.TrafficLightPoint;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.MetricQueryBuilder;
import com.databuff.apm.web.monitor.ThresholdEvaluationService;
import com.databuff.apm.web.config.ApmStorageProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ApmToolkit {

    private final ApmReadRepository readRepository;
    private final ThresholdEvaluationService thresholdEvaluationService;
    private final String metricDatabase;
    private final String traceDatabase;

    public ApmToolkit(
            ApmReadRepository readRepository,
            ThresholdEvaluationService thresholdEvaluationService,
            ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.thresholdEvaluationService = thresholdEvaluationService;
        this.metricDatabase = storageProperties.metricDatabase();
        this.traceDatabase = storageProperties.traceDatabase();
    }

    public List<ServiceHealthSummary> listServiceHealth(long lookbackMillis) {
        long to = System.currentTimeMillis();
        long from = to - lookbackMillis;
        List<ServiceHealthSummary> summaries = new ArrayList<>();
        try {
            String sql = MetricQueryBuilder.trafficLightSql(metricDatabase, from, to);
            for (TrafficLightPoint point : readRepository.queryTrafficLight(sql)) {
                if (point.service() == null || point.service().isBlank()) {
                    continue;
                }
                double rate = point.totalCount() <= 0 ? 0 : (double) point.errorCount() / point.totalCount();
                summaries.add(new ServiceHealthSummary(point.service(), point.totalCount(), point.errorCount(), rate));
            }
        } catch (Exception ignored) {
            // return partial/empty
        }
        return summaries;
    }

    public double serviceErrorRate(String service, long lookbackMillis) {
        return thresholdEvaluationService.currentErrorRate(service, lookbackMillis);
    }

    public int countRecentSpans(long lookbackMillis) {
        long to = System.currentTimeMillis();
        long from = to - lookbackMillis;
        try {
            String sql = MetricQueryBuilder.spanListCountSql(
                    traceDatabase, List.of(), from, to, null, null, null, null);
            long total = readRepository.queryCallSpanCount(sql);
            if (total <= 0L) {
                return 0;
            }
            return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        } catch (Exception e) {
            return 0;
        }
    }

    public record ServiceHealthSummary(String service, long totalCount, long errorCount, double errorRate) {
    }
}
