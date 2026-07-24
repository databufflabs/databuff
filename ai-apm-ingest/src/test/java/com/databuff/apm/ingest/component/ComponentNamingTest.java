package com.databuff.apm.ingest.component;

import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.ingest.support.IngestTestComponents;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentNamingTest {

    @Test
    void componentNames() throws Exception {
        AggregateComponent aggregate = IngestTestComponents.aggregate(new DorisBatchWriter());
        assertThat(componentName(aggregate)).isEqualTo("aggregate");
        assertThat(componentName(new MetricComponent(aggregate))).isEqualTo("metric");
        assertThat(componentName(IngestTestComponents.trace(aggregate, new DorisBatchWriter()))).isEqualTo("trace");
    }

    private static String componentName(Object component) throws Exception {
        Method getName = component.getClass().getSuperclass().getDeclaredMethod("getName");
        getName.setAccessible(true);
        return (String) getName.invoke(component);
    }
}
