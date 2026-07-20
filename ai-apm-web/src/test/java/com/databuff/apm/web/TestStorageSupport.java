package com.databuff.apm.web;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.portal.GlobalTopologyQueryService;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.monitor.AlarmStore;
import com.databuff.apm.web.portal.BusinessPortalService;
import com.databuff.apm.web.portal.ServiceAlarmCounter;
import com.databuff.apm.web.portal.ServicePortalService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class TestStorageSupport {

    private TestStorageSupport() {
    }

    public static ApmStorageProperties storage() {
        return new ApmStorageProperties("databuff", "databuff", "databuff");
    }

    public static GlobalTopologyQueryService globalTopologyQueryService(ApmReadRepository reader) {
        return new GlobalTopologyQueryService(reader, storage());
    }

    public static ServiceAlarmCounter emptyServiceAlarmCounter() {
        AlarmStore alarmStore = mock(AlarmStore.class);
        when(alarmStore.listInTimeRange(any(), any())).thenReturn(List.of());
        return new ServiceAlarmCounter(alarmStore);
    }

    public static ServicePortalService servicePortalService(ApmReadRepository reader) {
        return servicePortalService(reader, emptyServiceAlarmCounter());
    }

    public static ServicePortalService servicePortalService(
            ApmReadRepository reader,
            ServiceAlarmCounter serviceAlarmCounter) {
        return new ServicePortalService(
                reader, storage(), globalTopologyQueryService(reader), serviceAlarmCounter);
    }

    public static BusinessPortalService businessPortalService(ApmReadRepository reader) {
        return new BusinessPortalService(reader, storage(), globalTopologyQueryService(reader));
    }
}
