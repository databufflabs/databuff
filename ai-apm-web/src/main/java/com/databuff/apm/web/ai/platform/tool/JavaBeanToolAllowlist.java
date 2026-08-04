package com.databuff.apm.web.ai.platform.tool;

import java.util.Set;

public final class JavaBeanToolAllowlist {

    private static final Set<String> IMPLEMENTATIONS = Set.of(
            "commonTools.getCurrentTimeRange",
            "commonTools.getTimeRangeAroundTime",
            "commonTools.drawTrendCharts",
            "timeTool.getCurrentTimeRange",
            "timeTool.getTimeRangeAroundTime",
            "dataTools.queryServicesAll",
            "dataTools.queryServicesByServiceType",
            "dataTools.queryServiceTopology",
            "dataTools.queryTraceListByCondition",
            "dataTools.queryTraceDetail",
            "dataTools.queryServiceAlarms",
            "dataTools.queryMetricData",
            "logTools.queryLogTrend",
            "logTools.queryLogDetail",
            "logTools.queryLogsByTraceId",
            "logTools.queryLogsBySpanId",
            "inspectTools.inspectService",
            "bashTools.bash",
            "bashTools.bashOutput",
            "bashTools.killShell",
            "platformTools.queryDoris",
            "expertDispatchTool.dispatchExpertTask");

    private JavaBeanToolAllowlist() {
    }

    public static boolean isAllowed(String implementation) {
        return implementation != null && IMPLEMENTATIONS.contains(implementation);
    }

    public static Set<String> implementations() {
        return IMPLEMENTATIONS;
    }
}
