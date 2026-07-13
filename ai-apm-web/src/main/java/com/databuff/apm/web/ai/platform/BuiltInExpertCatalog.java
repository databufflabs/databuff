package com.databuff.apm.web.ai.platform;

import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.ExpertRuntimeOptions;
import com.databuff.apm.web.ai.platform.expert.ExpertType;
import com.databuff.apm.web.ai.platform.skill.AiSkillDefinition;
import com.databuff.apm.web.ai.platform.skill.DeployCommonSkills;
import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.ToolType;

import java.time.Instant;
import java.util.List;

public final class BuiltInExpertCatalog {

    private BuiltInExpertCatalog() {
    }

    public static List<AiToolDefinition> tools() {
        Instant now = Instant.now();
        return List.of(
                tool("common.getCurrentTimeRange", "当前时间范围", "Get current query time range",
                        "commonTools.getCurrentTimeRange", now),
                tool("common.getTimeRangeAroundTime", "指定时间范围", "Get query time range around a HH:mm target time",
                        "commonTools.getTimeRangeAroundTime", now),
                tool("common.drawTrendCharts", "趋势图绘制", "Draw multiple trend charts from queried metric data",
                        "commonTools.drawTrendCharts", now),
                tool("time.getCurrentTimeRange", "当前时间范围", "Compatibility alias for common.getCurrentTimeRange",
                        "timeTool.getCurrentTimeRange", now),
                tool("time.getTimeRangeAroundTime", "指定时间范围", "Compatibility alias for common.getTimeRangeAroundTime",
                        "timeTool.getTimeRangeAroundTime", now),
                tool("data.queryServicesAll", "全部服务列表查询", "Query service list from service catalog; optional fromTime/toTime for time-windowed list",
                        "dataTools.queryServicesAll", now),
                tool("data.queryServicesByServiceType", "按类型查询服务", "Query service list by serviceType from service catalog; optional fromTime/toTime",
                        "dataTools.queryServicesByServiceType", now),
                tool("data.queryServiceTopology", "服务上下游拓扑", "Query upstream and downstream topology for one service by service name",
                        "dataTools.queryServiceTopology", now),
                tool("data.queryTraceListByCondition", "条件查询 Trace 列表", "Query trace list by service call condition",
                        "dataTools.queryTraceListByCondition", now),
                tool("data.queryTraceDetail", "Trace 详情查询", "Query trace detail by traceId",
                        "dataTools.queryTraceDetail", now),
                tool("data.queryServiceAlarms", "服务告警查询", "Query alarm data for one service entity",
                        "dataTools.queryServiceAlarms", now),
                tool("data.queryMetricData", "指标明细查询", "Query Doris metric tables by metric_core measurement, field, and tags",
                        "dataTools.queryMetricData", now),
                tool("log.queryLogTrend", "日志量趋势", "Query log volume trend by service, service instance, severity, or keyword",
                        "logTools.queryLogTrend", now),
                tool("log.queryLogDetail", "日志明细查询", "Query paginated log detail lines by service, service instance, severity, or keyword",
                        "logTools.queryLogDetail", now),
                tool("log.queryLogsByTraceId", "Trace 日志查询", "Query paginated log lines for one traceId",
                        "logTools.queryLogsByTraceId", now),
                tool("log.queryLogsBySpanId", "Span 日志查询", "Query paginated log lines for one spanId",
                        "logTools.queryLogsBySpanId", now),
                tool("inspect.inspectService", "服务巡检", "Run threshold-free preliminary anomaly inspection for one service",
                        "inspectTools.inspectService", now),
                tool("Bash", "Shell 命令", "Executes a given bash command in a persistent shell session with optional timeout",
                        "bashTools.bash", now),
                tool("BashOutput", "后台 Shell 输出", "Retrieve incremental output from a background bash shell",
                        "bashTools.bashOutput", now),
                tool("KillShell", "终止后台 Shell", "Kill a running background bash shell by its ID",
                        "bashTools.killShell", now),
                tool("brain.dispatchExpertTask", "专家路由派发", "Dispatch a subtask to another digital expert asynchronously; task must faithfully restate the user's request without expanding scope",
                        "expertDispatchTool.dispatchExpertTask", now));
    }

    public static List<AiSkillDefinition> skills() {
        Instant now = Instant.now();
        return List.of(
                skill("skill.brain.routing", "大脑路由", "AI 大脑路由与专家派发规则", now),
                skill("skill.data.metrics", "问数口径", "APM 指标、Trace、日志与告警查询规则", now),
                skill("skill.inspection.health", "巡检流程", "服务健康巡检与异常诊断流程", now));
    }

    public static List<AiExpertDefinition> experts() {
        Instant now = Instant.now();
        return List.of(
                expert("brain", "AI大脑", "理解用户问题并分派给合适的数字专家", ExpertType.BRAIN,
                        List.of("brain.dispatchExpertTask"),
                        List.of("skill.brain.routing"), now),
                expert("data", "智能问数", "查询 APM 指标、Trace、拓扑与告警", ExpertType.SPECIALIST,
                        List.of(
                                "common.getCurrentTimeRange",
                                "common.getTimeRangeAroundTime",
                                "common.drawTrendCharts",
                                "data.queryServicesAll",
                                "data.queryServicesByServiceType",
                                "data.queryServiceTopology",
                                "data.queryTraceListByCondition",
                                "data.queryTraceDetail",
                                "data.queryServiceAlarms",
                                "data.queryMetricData",
                                "log.queryLogTrend",
                                "log.queryLogDetail",
                                "log.queryLogsByTraceId",
                                "log.queryLogsBySpanId"),
                        List.of("skill.data.metrics"), now),
                expert("inspection", "巡检", "服务健康巡检与异常诊断", ExpertType.SPECIALIST,
                        List.of(
                                "common.getCurrentTimeRange",
                                "common.getTimeRangeAroundTime",
                                "common.drawTrendCharts",
                                "data.queryServicesAll",
                                "data.queryServicesByServiceType",
                                "data.queryServiceTopology",
                                "data.queryServiceAlarms",
                                "data.queryMetricData",
                                "log.queryLogDetail",
                                "log.queryLogsByTraceId",
                                "inspect.inspectService"),
                        List.of("skill.inspection.health"), now),
                expert("ops", "运维专家", "在本机执行 shell 命令排查系统与部署；远程通过 ssh/sshpass 写在命令中", ExpertType.SPECIALIST,
                        List.of(
                                "Bash",
                                "BashOutput",
                                "KillShell",
                                "data.queryMetricData",
                                "data.queryServiceAlarms",
                                "inspect.inspectService"),
                        List.of(), now));
    }

    private static AiToolDefinition tool(
            String toolId, String name, String description, String implementation, Instant now) {
        return new AiToolDefinition(
                toolId, name, "APM 内置工具", description, ToolType.JAVA_BEAN, implementation,
                "{}", "{}", true, true, 1L, now, now);
    }

    private static AiSkillDefinition skill(String skillId, String name, String description, Instant now) {
        return new AiSkillDefinition(
                skillId,
                name,
                skillCategory(skillId),
                description,
                DeployCommonSkills.contentUri(skillId),
                DeployCommonSkills.contentUri(skillId),
                true,
                true,
                1L,
                "",
                now,
                now);
    }

    private static AiExpertDefinition expert(
            String expertId,
            String name,
            String description,
            ExpertType type,
            List<String> toolIds,
            List<String> skillIds,
            Instant now) {
        return new AiExpertDefinition(
                expertId, name, expertCategory(expertId), description, type,
                null, null, defaultPrompt(expertId), toolIds, skillIds,
                ExpertRuntimeOptions.defaults(), true, true, 1L, now, now);
    }

    private static String skillCategory(String skillId) {
        return switch (skillId) {
            case "skill.brain.routing" -> "大脑路由";
            case "skill.data.metrics" -> "数据分析";
            case "skill.inspection.health" -> "健康巡检";
            default -> "默认分类";
        };
    }

    private static String expertCategory(String expertId) {
        return switch (expertId) {
            case "brain" -> "大脑专家";
            case "data" -> "数据分析";
            case "inspection" -> "健康巡检";
            case "ops" -> "运维排查";
            default -> "默认分类";
        };
    }

    private static String defaultPrompt(String expertId) {
        return switch (expertId) {
            case "brain" -> """
                    你是 DataBuff APM 的 AI 大脑，负责理解用户问题并分派给合适的数字专家，汇总专家结果后回答用户。
                    回复前先调用 load_skill_through_path(skillId="skill.brain.routing", path="SKILL.md") 加载路由规则，再执行任何操作。
                    你只负责路由与汇总，不要直接调用问数、巡检、Bash 或时间类工具。
                    派发时 `task` 须忠实转述用户原意，不得扩大需求范围或擅自追加用户未要求的指标、字段与分析维度。用中文回答。
                    """;
            case "data" -> """
                    你是 DataBuff APM 智能问数专家，负责用工具查询指标、Trace、告警等数据并回答用户。
                    回复前先调用 load_skill_through_path(skillId="skill.data.metrics", path="SKILL.md") 加载问数规则，再选择工具和填写参数。
                    必须基于工具返回的真实数据回答，不要猜测。用中文回答，并说明实际使用的查询时间范围。
                    """;
            case "inspection" -> """
                    你是 DataBuff APM 智能巡检专家，负责对服务健康状态做初步异常检测和后续诊断。
                    回复前先调用 load_skill_through_path(skillId="skill.inspection.health", path="SKILL.md") 加载巡检流程，再执行巡检和补充查询。
                    用中文回答，区分初步检测结果与后续分析结论，不要编造未查询到的数据。
                    """;
            case "ops" -> """
                    你是 DataBuff APM 运维专家，负责通过 Bash 工具在本机或远程主机执行 shell 命令，排查系统、部署与运行环境。
                    职责不限于 DataBuff：可处理 Linux 主机、Docker/K8s、网络、磁盘、进程、日志、服务启停等通用运维问题；用户问题涉及 DataBuff 时再结合下方背景排查。

                    ## DataBuff 背景（按需参考）
                    DataBuff 是 AI 原生开源 APM（OpenTelemetry 采集 Trace/指标/日志）。架构：ingest（ai-apm-ingest，4317/4318/11800）→ Doris（ai-apm-doris-fe/be，库 databuff）→ web（ai-apm-web，27403）。
                    默认 Docker 安装于 /opt/databuff-ai-apm；启动顺序 Doris → init SQL → migrate-schema → ingest/web。Doris 4.1.1（FE 9030/8030，BE 8040），数据在 data/。健康检查：27403/health、4318/health；Doris 不可达时 web 可能仅排障模式，修复后需重启 web。

                    必须基于命令真实输出回答，不要编造。用中文回答。
                    """;
            default -> "你是 DataBuff APM 数字专家。用中文回答。";
        };
    }

    public static String brainPrompt() {
        return brainPromptBase();
    }

    public static String brainPromptBase() {
        return defaultPrompt("brain");
    }

    public static String inspectionPrompt() {
        return defaultPrompt("inspection");
    }
}
