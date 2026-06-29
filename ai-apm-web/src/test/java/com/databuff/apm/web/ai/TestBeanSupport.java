package com.databuff.apm.web.ai;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.monitor.AlarmStore;
import com.databuff.apm.web.monitor.NotifyChannelService;
import com.databuff.apm.web.monitor.pipeline.EventAlarmOpener;
import com.databuff.apm.web.monitor.service.AlarmService;
import com.databuff.apm.web.persistence.AiPlatformPersistence;
import com.databuff.apm.web.persistence.EventPersistence;
import com.databuff.apm.web.ai.platform.runtime.SkillFileSyncService;
import com.databuff.apm.web.ai.agent.AiChatOrchestrator;
import com.databuff.apm.web.ai.agent.AiRuntimeForwarder;
import com.databuff.apm.web.ai.agent.AiRuntimeRouter;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import com.databuff.apm.web.ai.mcp.standard.JavaBeanToolExecutor;
import com.databuff.apm.web.ai.platform.api.AiToolController;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.runtime.AgentScopeToolFactory;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.RemoteMcpToolRegistrar;
import com.databuff.apm.web.ai.platform.runtime.SessionWorkspaceService;
import com.databuff.apm.web.ai.platform.skill.SkillManagementService;
import com.databuff.apm.web.ai.platform.task.ExpertDispatchTool;
import com.databuff.apm.web.ai.platform.task.ExpertTaskService;
import com.databuff.apm.web.ai.platform.task.ExpertTaskPendingRegistry;
import com.databuff.apm.web.ai.platform.task.ExpertTaskTextGuard;
import com.databuff.apm.web.ai.platform.tool.ToolManagementService;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import com.databuff.apm.web.portal.ServicePortalService;
import com.databuff.apm.web.portal.TracePortalService;
import com.databuff.apm.web.tools.local.CommonTools;
import com.databuff.apm.web.tools.local.DataTools;
import com.databuff.apm.web.tools.local.InspectTools;
import com.databuff.apm.web.tools.local.TimeTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;

/**
 * Helpers for unit tests that construct Spring beans without a context.
 */
public final class TestBeanSupport {

    private TestBeanSupport() {
    }

    public static ToolManagementService toolManagementService() {
        ToolManagementService service = new ToolManagementService();
        invokeInit(service, "initDefaults");
        return service;
    }

    public static ToolManagementService toolManagementService(
            ObjectProvider<AiPlatformPersistence> persistence,
            ObjectProvider<ExpertRuntimeRegistry> runtimeRegistry) {
        ToolManagementService service = new ToolManagementService();
        setField(service, "persistence", persistence);
        setField(service, "runtimeRegistry", runtimeRegistry);
        invokeInit(service, "initDefaults");
        return service;
    }

    public static SkillManagementService skillManagementService() {
        SkillManagementService service = new SkillManagementService();
        invokeInit(service, "initDefaults");
        return service;
    }

    public static SkillManagementService skillManagementService(
            ObjectProvider<AiPlatformPersistence> persistence,
            ObjectProvider<ExpertRuntimeRegistry> runtimeRegistry,
            ObjectProvider<SkillFileSyncService> skillFileSyncService) {
        SkillManagementService service = new SkillManagementService();
        setField(service, "persistence", persistence);
        setField(service, "runtimeRegistry", runtimeRegistry);
        setField(service, "skillFileSyncService", skillFileSyncService);
        invokeInit(service, "initDefaults");
        return service;
    }

    public static ExpertManagementService expertManagementService(
            ToolManagementService toolManagementService,
            SkillManagementService skillManagementService) {
        ExpertManagementService service = new ExpertManagementService();
        setField(service, "toolManagementService", toolManagementService);
        setField(service, "skillManagementService", skillManagementService);
        invokeInit(service, "initDefaults");
        return service;
    }

    public static ExpertManagementService expertManagementService(
            ToolManagementService toolManagementService,
            SkillManagementService skillManagementService,
            ObjectProvider<AiPlatformPersistence> persistence,
            ObjectProvider<ExpertRuntimeRegistry> runtimeRegistry) {
        ExpertManagementService service = expertManagementService(toolManagementService, skillManagementService);
        setField(service, "persistence", persistence);
        setField(service, "runtimeRegistry", runtimeRegistry);
        return service;
    }

    public static InMemoryLlmProviderStore llmProviderStore() {
        return llmProviderStore(defaultProviderProperties());
    }

    public static InMemoryLlmProviderStore llmProviderStore(ObjectProvider<ExpertRuntimeRegistry> runtimeRegistry) {
        return llmProviderStore(defaultProviderProperties(), runtimeRegistry);
    }

    public static InMemoryLlmProviderStore llmProviderStore(AiLlmProviderProperties providerProperties) {
        InMemoryLlmProviderStore store = new InMemoryLlmProviderStore();
        setField(store, "providerProperties", providerProperties);
        invokeInit(store, "initDefaults");
        return store;
    }

    public static InMemoryLlmProviderStore llmProviderStore(
            AiLlmProviderProperties providerProperties,
            ObjectProvider<ExpertRuntimeRegistry> runtimeRegistry) {
        InMemoryLlmProviderStore store = llmProviderStore(providerProperties);
        setField(store, "runtimeRegistry", runtimeRegistry);
        return store;
    }

    public static AiLlmProviderProperties defaultProviderProperties() {
        AiLlmProviderProperties properties = new AiLlmProviderProperties();
        properties.setMaskApiKey(true);
        return properties;
    }

    public static AiLlmProviderProperties unmaskedProviderProperties() {
        AiLlmProviderProperties properties = new AiLlmProviderProperties();
        properties.setMaskApiKey(false);
        return properties;
    }

    public static DataTools dataTools(
            ServicePortalService servicePortalService,
            TracePortalService tracePortalService,
            AlarmService alarmService,
            ObjectMapper objectMapper) {
        DataTools tools = new DataTools();
        setField(tools, "servicePortalService", servicePortalService);
        setField(tools, "tracePortalService", tracePortalService);
        setField(tools, "alarmService", alarmService);
        setField(tools, "objectMapper", objectMapper);
        setField(tools, "metricDatabase", "databuff");
        return tools;
    }

    public static DataTools dataTools(
            ServicePortalService servicePortalService,
            TracePortalService tracePortalService,
            AlarmService alarmService,
            ApmReadRepository readRepository,
            ObjectMapper objectMapper) {
        DataTools tools = dataTools(servicePortalService, tracePortalService, alarmService, objectMapper);
        setField(tools, "readRepository", readRepository);
        return tools;
    }

    public static InspectTools inspectTools(ServicePortalService servicePortalService, ObjectMapper objectMapper) {
        InspectTools tools = new InspectTools();
        setField(tools, "servicePortalService", servicePortalService);
        setField(tools, "objectMapper", objectMapper);
        setField(tools, "metricDatabase", "databuff");
        return tools;
    }

    public static AgentScopeToolFactory agentScopeToolFactory(
            DataTools dataTools,
            TimeTool timeTool,
            CommonTools commonTools,
            InspectTools inspectTools,
            ExpertDispatchTool expertDispatchTool) {
        AgentScopeToolFactory factory = new AgentScopeToolFactory();
        setField(factory, "dataTools", dataTools);
        setField(factory, "timeTool", timeTool);
        setField(factory, "commonTools", commonTools);
        setField(factory, "inspectTools", inspectTools);
        setField(factory, "expertDispatchTool", expertDispatchTool);
        setField(factory, "remoteMcpToolRegistrar", new RemoteMcpToolRegistrar(new ObjectMapper()));
        return factory;
    }

    public static AiChatOrchestrator chatOrchestrator(
            ExpertManagementService expertManagementService,
            ExpertRuntimeRegistry expertRuntimeRegistry,
            SessionExpertRuntimeRegistry sessionExpertRuntimeRegistry,
            AiSessionStore sessionStore,
            AiConfigService aiConfigService,
            AgentRuntimeConfig agentRuntimeConfig,
            ApmToolkit apmToolkit,
            OpenAiCompatibleChatClient chatClient,
            InMemoryLlmProviderStore llmProviderStore,
            AiRuntimeRouter runtimeRouter,
            AiRuntimeForwarder runtimeForwarder,
            ExpertTaskService expertTaskService,
            ExpertTaskPendingRegistry expertTaskPendingRegistry,
            ExpertTaskTextGuard expertTaskTextGuard,
            SessionWorkspaceService sessionWorkspaceService,
            long lookbackMinutes) {
        AiChatOrchestrator orchestrator = new AiChatOrchestrator();
        setField(orchestrator, "expertManagementService", expertManagementService);
        setField(orchestrator, "expertRuntimeRegistry", expertRuntimeRegistry);
        setField(orchestrator, "sessionExpertRuntimeRegistry", sessionExpertRuntimeRegistry);
        setField(orchestrator, "sessionStore", sessionStore);
        setField(orchestrator, "aiConfigService", aiConfigService);
        setField(orchestrator, "agentRuntimeConfig", agentRuntimeConfig);
        setField(orchestrator, "apmToolkit", apmToolkit);
        setField(orchestrator, "chatClient", chatClient);
        setField(orchestrator, "llmProviderStore", llmProviderStore);
        setField(orchestrator, "runtimeRouter", runtimeRouter);
        setField(orchestrator, "runtimeForwarder", runtimeForwarder);
        setField(orchestrator, "expertTaskService", expertTaskService);
        setField(orchestrator, "expertTaskPendingRegistry", expertTaskPendingRegistry);
        setField(orchestrator, "expertTaskTextGuard", expertTaskTextGuard);
        setField(orchestrator, "sessionWorkspaceService", sessionWorkspaceService);
        setField(orchestrator, "lookbackMinutes", lookbackMinutes);
        invokeInit(orchestrator, "initLookback");
        return orchestrator;
    }

    public static AiToolController aiToolController(
            ToolManagementService toolManagementService,
            ExpertManagementService expertManagementService,
            DataTools dataTools,
            TimeTool timeTool,
            ObjectMapper objectMapper) {
        AiToolController controller = new AiToolController();
        setField(controller, "toolManagementService", toolManagementService);
        setField(controller, "expertManagementService", expertManagementService);
        setField(controller, "javaBeanToolExecutor", javaBeanToolExecutor(
                dataTools, timeTool, objectMapper));
        return controller;
    }

    public static JavaBeanToolExecutor javaBeanToolExecutor(
            DataTools dataTools,
            TimeTool timeTool,
            ObjectMapper objectMapper) {
        return new JavaBeanToolExecutor(
                new CommonTools(objectMapper),
                dataTools,
                inspectTools(mock(ServicePortalService.class), objectMapper),
                timeTool,
                objectMapper);
    }

    public static NotifyChannelService notifyChannelService() {
        NotifyChannelService service =
                new NotifyChannelService();
        invokeInit(service, "initDefaults");
        return service;
    }

    public static EventAlarmOpener eventAlarmOpener(
            AlarmStore alarmStore,
            EventPersistence eventPersistence) {
        EventAlarmOpener opener = new EventAlarmOpener();
        setField(opener, "alarmStore", alarmStore);
        setField(opener, "eventPersistence", eventPersistence);
        return opener;
    }

    public static void setField(Object target, String fieldName, Object value) {
        ReflectionTestUtils.setField(target, fieldName, value);
    }

    public static void invokeInit(Object target, String methodName) {
        ReflectionTestUtils.invokeMethod(target, methodName);
    }
}
