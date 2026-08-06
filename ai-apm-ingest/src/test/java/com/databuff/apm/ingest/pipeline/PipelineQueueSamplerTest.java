package com.databuff.apm.ingest.pipeline;

import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.ingest.component.MetricComponent;
import com.databuff.apm.ingest.component.TraceComponent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineQueueSamplerTest {

    @Test
    void sampleReportsAllPipelineAndAssemblyGauges() {
        TraceComponent trace = mock(TraceComponent.class);
        MetricComponent metric = mock(MetricComponent.class);
        AggregateComponent aggregate = mock(AggregateComponent.class);
        when(trace.pendingAssemblyTraces()).thenReturn(7);

        PipelineQueueSampler sampler = new PipelineQueueSampler(trace, metric, aggregate);
        sampler.sample();

        verify(trace).samplePipelineQueueGauges();
        verify(metric).samplePipelineQueueGauges();
        verify(aggregate).samplePipelineQueueGauges();
        verify(trace).pendingAssemblyTraces();
    }
}
