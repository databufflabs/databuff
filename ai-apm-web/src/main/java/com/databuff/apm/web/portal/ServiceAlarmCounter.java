package com.databuff.apm.web.portal;

import com.databuff.apm.common.util.PortalServiceIdResolver;
import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.AlarmStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static com.databuff.apm.common.util.PortalServiceIdResolver.normalize;

/** Counts alarms in a time range and resolves them onto portal service id/name keys. */
@Component
public class ServiceAlarmCounter {

    private final AlarmStore alarmStore;

    public ServiceAlarmCounter(AlarmStore alarmStore) {
        this.alarmStore = alarmStore;
    }

    public Map<String, Long> countByService(long from, long to) {
        Map<String, Long> counts = new HashMap<>();
        Instant fromInstant = Instant.ofEpochMilli(from);
        Instant toInstant = Instant.ofEpochMilli(to);
        for (Alarm alarm : alarmStore.listInTimeRange(fromInstant, toInstant)) {
            String service = alarm.service();
            if (service == null || service.isBlank()) {
                continue;
            }
            String serviceKey = normalize(service);
            if (serviceKey.isBlank()) {
                continue;
            }
            counts.merge(serviceKey, 1L, Long::sum);
        }
        return counts;
    }

    public long resolve(String serviceId, String serviceName, Map<String, Long> alarmCountsByService) {
        if (alarmCountsByService == null || alarmCountsByService.isEmpty()) {
            return 0L;
        }
        if (serviceId != null && !serviceId.isBlank()) {
            long direct = alarmCountsByService.getOrDefault(serviceId, 0L);
            if (direct > 0) {
                return direct;
            }
        }
        if (serviceName != null && !serviceName.isBlank()) {
            String normalizedName = normalize(serviceName);
            long byName = alarmCountsByService.getOrDefault(normalizedName, 0L);
            if (byName > 0) {
                return byName;
            }
        }
        for (Map.Entry<String, Long> entry : alarmCountsByService.entrySet()) {
            if (PortalServiceIdResolver.matches(serviceId, entry.getKey())
                    || PortalServiceIdResolver.matches(serviceName, entry.getKey())) {
                return entry.getValue();
            }
        }
        return 0L;
    }

    public long countFor(String serviceId, String serviceName, long from, long to) {
        return resolve(serviceId, serviceName, countByService(from, to));
    }
}
