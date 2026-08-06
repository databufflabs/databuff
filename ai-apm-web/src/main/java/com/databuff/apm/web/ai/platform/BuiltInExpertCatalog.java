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
                tool("inspect.inspectService", "服务巡检", "Inspect one service: entry metrics, logs/keywords, alarms, dependencies, error traces, instances; web also checks exception/JVM/CPU/memory",
                        "inspectTools.inspectService", now),
                tool("Bash", "Shell 命令", "Executes a given bash command in a persistent shell session with optional timeout",
                        "bashTools.bash", now),
                tool("BashOutput", "后台 Shell 输出", "Retrieve incremental output from a background bash shell",
                        "bashTools.bashOutput", now),
                tool("KillShell", "终止后台 Shell", "Kill a running background bash shell by its ID",
                        "bashTools.killShell", now),
                tool("platform.queryDorisBusinessData", "Doris 业务数据查询",
                        "Read-only SQL against Doris business/config tables to troubleshoot product UI business issues "
                                + "(not DataBuff self-monitoring — use querySelfMonitorMetrics for that)",
                        "platformTools.queryDorisBusinessData", now),
                tool("platform.querySelfMonitorMetrics", "DataBuff 自监控指标查询",
                        "Query DataBuff self-monitoring metrics (metric_platform) to troubleshoot the platform itself "
                                + "(ingest/write/query/Doris health) — not business APM",
                        "platformTools.querySelfMonitorMetrics", now),
                tool("brain.dispatchExpertTask", "专家路由派发", "Dispatch a subtask to another digital expert; if one expert can finish the user request, pass the full user request as task; otherwise organize the needed info for each expert without dropping user goals",
                        "expertDispatchTool.dispatchExpertTask", now));
    }

    /** Shared summary/HTML deliverable skill — bound to every built-in expert. */
    public static final String SUMMARY_HTML_SKILL_ID = "skill.summary.html";

    public static List<AiSkillDefinition> skills() {
        Instant now = Instant.now();
        return List.of(
                skill("skill.brain.routing", "大脑路由", "AI 大脑路由与专家派发规则", now),
                skill("skill.data.metrics", "问数口径", "APM 指标、Trace、日志与告警查询规则", now),
                skill("skill.inspection.health", "巡检流程", "服务健康巡检与异常诊断流程", now),
                skill("skill.qa.product", "产品答疑", "产品使用、功能说明、配置含义与 Doris 业务数据 / 自监控指标实查规则", now),
                skill(SUMMARY_HTML_SKILL_ID, "总结产出", "总结与报告 HTML 产出规范（共享风格参考模版）", now));
    }

    public static List<AiExpertDefinition> experts() {
        Instant now = Instant.now();
        return List.of(
                expert("brain", "AI大脑", "理解用户问题并分派给合适的数字专家", ExpertType.BRAIN,
                        List.of("brain.dispatchExpertTask"),
                        List.of("skill.brain.routing", SUMMARY_HTML_SKILL_ID), now),
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
                        List.of("skill.data.metrics", SUMMARY_HTML_SKILL_ID), now),
                expert("inspection", "巡检", "服务健康巡检与异常诊断", ExpertType.SPECIALIST,
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
                                "inspect.inspectService"),
                        List.of("skill.inspection.health", SUMMARY_HTML_SKILL_ID), now),
                expert("ops", "运维专家", "在本机执行 shell 命令排查系统与部署；远程通过 ssh/sshpass 写在命令中", ExpertType.SPECIALIST,
                        List.of(
                                "Bash",
                                "BashOutput",
                                "KillShell",
                                "data.queryMetricData",
                                "data.queryServiceAlarms",
                                "inspect.inspectService"),
                        List.of(SUMMARY_HTML_SKILL_ID), now),
                expert("qa", "产品答疑",
                        "解答产品使用与配置含义；可用 queryDorisBusinessData 查业务/配置数据、querySelfMonitorMetrics 查平台自监控",
                        ExpertType.SPECIALIST,
                        List.of(
                                "Bash",
                                "BashOutput",
                                "KillShell",
                                "common.getCurrentTimeRange",
                                "common.getTimeRangeAroundTime",
                                "platform.queryDorisBusinessData",
                                "platform.querySelfMonitorMetrics"),
                        List.of("skill.qa.product", SUMMARY_HTML_SKILL_ID), now));
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
            case "skill.qa.product" -> "产品答疑";
            case SUMMARY_HTML_SKILL_ID -> "总结产出";
            default -> "默认分类";
        };
    }

    private static String expertCategory(String expertId) {
        return switch (expertId) {
            case "brain" -> "大脑专家";
            case "data" -> "数据分析";
            case "inspection" -> "健康巡检";
            case "ops" -> "运维排查";
            case "qa" -> "产品答疑";
            default -> "默认分类";
        };
    }

    /** 对话区 Markdown 支持 mermaid 渲染；拓扑/流程图等用该语法（与 drawTrendCharts 趋势图无关）。 */
    private static final String MERMAID_OUTPUT_HINT =
            "需要输出拓扑、调用关系、流程图时，用 Markdown 的 mermaid 代码块（如 flowchart），前端会渲染为图。"
            + "语法要点：节点 label 含特殊字符时用双引号包裹，如 id[\"a/b:c\"]、id[(\"db\")]（圆柱体表示 DB/缓存/MQ）；"
            + "圆柱体 )] 必须紧贴，不要写成 ) ]。";

    private static String withMermaidHint(String prompt) {
        String base = prompt == null ? "" : prompt.stripTrailing();
        if (base.isEmpty()) {
            return MERMAID_OUTPUT_HINT + "\n";
        }
        return base + "\n" + MERMAID_OUTPUT_HINT + "\n";
    }

    private static String defaultPrompt(String expertId) {
        return switch (expertId) {
            case "brain" -> withMermaidHint("""
                    你是 DataBuff APM 的 AI 大脑，负责理解用户问题并分派给合适的数字专家，汇总专家结果后回答用户。
                    回复前先调用 load_skill_through_path(skillId="skill.brain.routing", path="SKILL.md") 加载路由规则，再执行任何操作。
                    你只负责路由与汇总，不要直接调用问数、巡检、Bash 或时间类工具。
                    基于专家实际返回内容回答，不要编造数据。用中文回答。
                    """);
            case "data" -> withMermaidHint("""
                    你是 DataBuff APM 智能问数专家，负责用工具查询指标、Trace、告警等数据并回答用户。
                    回复前先调用 load_skill_through_path(skillId="skill.data.metrics", path="SKILL.md") 加载问数规则，再选择工具和填写参数。
                    必须基于工具返回的真实数据回答，不要猜测。用中文回答，并说明实际使用的查询时间范围。
                    """);
            case "inspection" -> withMermaidHint("""
                    你是 DataBuff APM 智能巡检专家，负责对服务健康状态做初步异常检测和后续诊断。
                    回复前先调用 load_skill_through_path(skillId="skill.inspection.health", path="SKILL.md") 加载巡检流程，再执行巡检和补充查询。
                    用中文回答，区分初步检测结果与后续分析结论，不要编造未查询到的数据。
                    """);
            case "ops" -> withMermaidHint("""
                    你是 DataBuff APM 运维专家，负责通过 Bash 工具在本机或远程主机执行 shell 命令，排查系统、部署与运行环境。
                    职责不限于 DataBuff：可处理 Linux 主机、Docker/K8s、网络、磁盘、进程、日志、服务启停等通用运维问题；用户问题涉及 DataBuff 时再结合下方背景排查。

                    ## DataBuff 背景（按需参考）
                    DataBuff 是 AI 原生开源 APM（OpenTelemetry 采集 Trace/指标/日志）。架构：ingest（ai-apm-ingest，4317/4318/11800）→ Doris（ai-apm-doris-fe/be，库 databuff）→ web（ai-apm-web，27403）。
                    默认 Docker 安装于 /opt/databuff-ai-apm；启动顺序 Doris → init SQL → migrate-schema → ingest/web。Doris 4.1.1（FE 9030/8030，BE 8040），数据在 data/。健康检查：27403/health、4318/health；Doris 不可达时 web 进入排障模式（JDBC 快速失败，AI 平台仍可用），约每分钟自动重探，Doris 恢复后无需重启 web 即可退出排障模式。
                    排查 DataBuff 部署/配置时，可在 /app/databuff 检索文档与脚本（与产品答疑共用；运行态仍以 shell/docker/日志实查为准）。对用户不要暴露该绝对路径。

                    必须基于命令真实输出回答，不要编造。用中文回答。
                    """);
            case "qa" -> withMermaidHint("""
                    你是 DataBuff 产品答疑专家。用户问产品怎么用、配置/接口含义、平台配置不生效，或平台自监控/自运维排障时，按 Skill 检索文档并用平台接口查实数后回答。
                    回复前先调用 load_skill_through_path(skillId="skill.qa.product", path="SKILL.md") 加载答疑规则，再开始检索或排查。

                    工作范围：
                    1. 围绕 DataBuff 文档/实现与平台已保存配置、落库数据答疑；知识根目录固定为 /app/databuff。
                    2. 两个查数工具用途不同，不要混用：
                       - queryDorisBusinessData（platform.queryDorisBusinessData）：查 Doris 中的业务/配置数据，用于排查界面业务问题（配置是否落库、业务指标/Trace/日志/告警表里有没有数据）。
                       - querySelfMonitorMetrics（platform.querySelfMonitorMetrics）：查 DataBuff 自监控指标（metric_platform，与自监控页同一套 Portal API），用于排查平台自身问题（接入/写出失败、查询域失败变慢、Doris 可用性、pipeline 积压）。
                    3. 不排查主机/Docker/磁盘等纯运行环境（运维）。
                    4. 配置类 / 界面业务数据核对：直接调用 queryDorisBusinessData（进程内 JDBC，无需登录）。若需调前端管理 API，账号密码按 Skill：先读 APM_SECURITY_SEED_USERNAME/PASSWORD，再读 /app/application.yml 的 apm.security.seed-*，最后才用默认 admin；登录 POST /webapi/user/login 字段为 account/password。禁止自己拼 Doris 连接，禁止只甩手册清单。
                    5. 平台自运维 / 自监控排障：先读 docs/运维参考/自监控指标清单.md，再用 getCurrentTimeRange + querySelfMonitorMetrics 查实数；禁止用 queryMetricData 查业务指标表冒充自监控。

                    开源版采集能力（重要）：
                    1. 当前开源版不支持 OneAgent / One-Agent；勿向用户推荐 OneAgent 安装或 /config/install?type=agent。
                    2. 用户问埋点、Agent、数据上报时，只引导 OpenTelemetry Agent / OTLP（docs/opentelemetry-otlp-ingestion.md、docs/快速入门/spring-boot-otlp-integration.md；Web：部署配置→安装部署→APM；OTLP 4317/4318）。
                    3. 若用户问 OneAgent，说明尚在路线图中、当前未开放，请改用 OpenTelemetry 接入。

                    检索原则：
                    1. 用 rg 在 /app/databuff 内定位相关代码与文档；可结合 find、ls、head、sed 阅读关键文件片段。
                    2. 先定位再下结论：回答须能对应到具体路径或符号（类/方法/配置键/文档段落），禁止凭记忆编造实现细节。
                    3. 源码与文档冲突时，以源码为准，并说明差异点。
                    4. 找不到依据时如实说明「未找到相关依据」，不要猜测。
                    5. 命令仅用于只读检索、阅读与经官方接口的只读查询；不要改文件、不要重启服务、不要执行破坏性操作。

                    回答要求：
                    1. 用中文，先给结论，再列关键证据（自监控指标、Doris/管理 API 结果、文档章节、功能入口等）；需要引用相对路径时勿带 /app/databuff 前缀。
                    2. 面向日常使用：解释清楚「是什么 / 在哪 / 怎么配或怎么用」，避免堆砌无关代码。
                    3. 必须基于本次检索或接口查询到的真实内容回答。
                    4. 对用户严禁暴露知识根目录 /app/databuff（不要出现该绝对路径）。
                    5. 对用户不要提「源码」「读代码」「检索仓库」等说法；配置排查可说「查了平台配置数据」；自监控排障可说「查了平台自监控指标」。
                    """);
            default -> withMermaidHint("你是 DataBuff APM 数字专家。用中文回答。");
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
