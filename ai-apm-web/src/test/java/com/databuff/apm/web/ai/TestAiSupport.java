package com.databuff.apm.web.ai;

import com.databuff.apm.web.monitor.service.AlarmService;
import com.databuff.apm.web.persistence.AiSessionPersistence;
import com.databuff.apm.web.ai.agent.AgentBrainService;
import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import com.databuff.apm.web.ai.agent.AiChatOrchestrator;
import com.databuff.apm.web.ai.agent.AiRuntimeForwarder;
import com.databuff.apm.web.ai.agent.AiRuntimeRouter;
import com.databuff.apm.web.support.WebTestClusterSupport;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.agent.AgentController;
import com.databuff.apm.web.ai.agent.ChatRequestContextResolver;
import com.databuff.apm.web.auth.JwtTokenService;
import com.databuff.apm.web.config.JwtProperties;
import com.databuff.apm.web.ai.platform.expert.BrainRoutingCatalog;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.runtime.AgentScopeSessionHook;
import com.databuff.apm.web.ai.platform.tool.ExpertToolResolver;
import com.databuff.apm.web.ai.platform.runtime.AgentScopeToolFactory;
import com.databuff.apm.web.ai.platform.runtime.AgentScopeRuntimeAdapter;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionWorkspaceService;
import com.databuff.apm.web.ai.platform.runtime.SessionWorkspaceTools;
import com.databuff.apm.web.ai.platform.runtime.TaskGeneratedFileRegistry;
import com.databuff.apm.web.ai.platform.runtime.SkillFileSyncService;
import com.databuff.apm.web.ai.platform.skill.SkillManagementService;
import com.databuff.apm.web.ai.platform.task.ExpertDispatchTool;
import com.databuff.apm.web.ai.platform.task.ExpertTaskService;
import com.databuff.apm.web.ai.platform.task.BrainContinuationService;
import com.databuff.apm.web.ai.platform.task.BrainRoundContinuer;
import com.databuff.apm.web.ai.platform.task.ExpertTaskPendingRegistry;
import com.databuff.apm.web.ai.platform.task.ExpertTaskTextGuard;
import com.databuff.apm.web.ai.platform.tool.ToolManagementService;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import com.databuff.apm.web.persistence.LlmProviderPersistence;
import com.databuff.apm.web.portal.ServicePortalService;
import com.databuff.apm.web.portal.TracePortalService;
import com.databuff.apm.web.tools.local.CommonTools;
import com.databuff.apm.web.tools.local.DataTools;
import com.databuff.apm.web.tools.local.TimeTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class TestAiSupport {

    private TestAiSupport() {
    }

    public static AiSessionPersistence noopSessionPersistence(AiSessionStore sessionStore) {
        AiSessionPersistence persistence = mock(AiSessionPersistence.class);
        when(persistence.pollMergedMessages(anyString(), any()))
                .thenAnswer(invocation -> sessionStore.pollMessages(
                        invocation.getArgument(0),
                        invocation.getArgument(1)));
        when(persistence.countSessions())
                .thenAnswer(invocation -> (long) sessionStore.listSessions().size());
        when(persistence.listSessions(anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int offset = invocation.getArgument(0);
                    int limit = invocation.getArgument(1);
                    List<AiSessionStore.SessionSummary> all = sessionStore.listSessions();
                    int from = Math.min(Math.max(0, offset), all.size());
                    int to = Math.min(from + Math.max(1, limit), all.size());
                    return all.subList(from, to);
                });
        return persistence;
    }

    public static LlmProviderPersistence noopPersistence() {
        LlmProviderPersistence sync = mock(LlmProviderPersistence.class);
        doNothing().when(sync).persistUpdate(any(), any(), any());
        return sync;
    }

    public static AiFixture aiFixture() {
        return new AiFixture();
    }

    public static AiConfigService configService() {
        return aiFixture().aiConfigService();
    }

    public static final class AiFixture {
        private final InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        private final AiLlmProviderProperties providerProperties = TestBeanSupport.defaultProviderProperties();
        private final AiConfigService aiConfigService =
                new AiConfigService(store, new LlmCatalogService(), noopPersistence(), providerProperties);
        private final AgentRuntimeConfig agentRuntimeConfig = new AgentRuntimeConfig();

        public InMemoryLlmProviderStore store() {
            return store;
        }

        public AiConfigService aiConfigService() {
            return aiConfigService;
        }

        public AgentRuntimeConfig agentRuntimeConfig() {
            return agentRuntimeConfig;
        }

        public AgentBrainService agentBrain(ApmToolkit toolkit, AiSessionStore sessionStore) {
            PlatformRuntimeFixture fixture = buildPlatformRuntime(toolkit);
            AiRuntimeRouter runtimeRouter = WebTestClusterSupport.standaloneAiRouter("web-1");
            AiRuntimeForwarder runtimeForwarder = new AiRuntimeForwarder(runtimeRouter, 120L);
            AiChatOrchestrator orchestrator = TestBeanSupport.chatOrchestrator(
                    fixture.expertManagementService(),
                    fixture.expertRuntimeRegistry(),
                    fixture.sessionExpertRuntimeRegistry(),
                    sessionStore,
                    aiConfigService,
                    agentRuntimeConfig,
                    toolkit,
                    new OpenAiCompatibleChatClient(agentRuntimeConfig),
                    store,
                    runtimeRouter,
                    runtimeForwarder,
                    fixture.expertTaskService(),
                    fixture.expertTaskPendingRegistry(),
                    fixture.expertTaskTextGuard(),
                    fixture.sessionWorkspaceService(),
                    15);
            fixture.wireBrainContinuer(orchestrator);
            return new AgentBrainService(
                    sessionStore,
                    orchestrator,
                    fixture.expertTaskService(),
                    noopSessionPersistence(sessionStore));
        }

        public AgentController agentController(AgentBrainService brain) {
            JwtTokenService jwtTokenService = new JwtTokenService(new JwtProperties("test-secret", 3600));
            return new AgentController(brain, new ChatRequestContextResolver(jwtTokenService));
        }

        public PlatformRuntimeFixture buildPlatformRuntime(ApmToolkit toolkit) {
            ToolManagementService toolManagementService = TestBeanSupport.toolManagementService();
            SkillManagementService skillManagementService = TestBeanSupport.skillManagementService();
            ExpertManagementService expertManagementService =
                    TestBeanSupport.expertManagementService(toolManagementService, skillManagementService);
            DataTools dataTools = TestBeanSupport.dataTools(
                    mock(ServicePortalService.class),
                    mock(TracePortalService.class),
                    Mockito.mock(AlarmService.class),
                    new ObjectMapper());
            ObjectMapper objectMapper = new ObjectMapper();
            ExpertRuntimeRegistry[] registryHolder = new ExpertRuntimeRegistry[1];
            SessionExpertRuntimeRegistry[] sessionRegistryHolder = new SessionExpertRuntimeRegistry[1];
            AiSessionStore sessionStore = new AiSessionStore();
            ExpertTaskPendingRegistry pendingRegistry = new ExpertTaskPendingRegistry();
            ExpertTaskTextGuard taskTextGuard = new ExpertTaskTextGuard();
            AtomicReference<BrainRoundContinuer> brainContinuerRef = new AtomicReference<>();
            BrainContinuationService brainContinuationService = new BrainContinuationService(
                    continuerRefProvider(brainContinuerRef),
                    pendingRegistry);
            SessionWorkspaceService sessionWorkspaceService = new SessionWorkspaceService(agentRuntimeConfig);
            TaskGeneratedFileRegistry generatedFileRegistry = new TaskGeneratedFileRegistry();
            ExpertTaskService expertTaskService = new ExpertTaskService(
                    expertManagementService,
                    providerOf(registryHolder),
                    providerOf(sessionRegistryHolder),
                    null,
                    sessionStore,
                    pendingRegistry,
                    taskTextGuard,
                    brainContinuationService,
                    sessionWorkspaceService,
                    generatedFileRegistry);
            ExpertDispatchTool dispatchTool = new ExpertDispatchTool(
                    expertTaskServiceProvider(expertTaskService),
                    sessionStoreProvider(sessionStore));
            AgentScopeToolFactory toolFactory = TestBeanSupport.agentScopeToolFactory(
                    dataTools,
                    new TimeTool(),
                    new CommonTools(objectMapper),
                    TestBeanSupport.inspectTools(mock(ServicePortalService.class), objectMapper),
                    dispatchTool);
            ExpertToolResolver expertToolResolver = new ExpertToolResolver(toolManagementService);
            AgentScopeSessionHook sessionHook = new AgentScopeSessionHook(sessionStore);
            SkillFileSyncService skillFileSyncService = new SkillFileSyncService(
                    agentRuntimeConfig, skillManagementService, new DefaultResourceLoader());

            SessionWorkspaceTools sessionWorkspaceTools = new SessionWorkspaceTools(
                    sessionWorkspaceService, agentRuntimeConfig, generatedFileRegistry);
            BrainRoutingCatalog brainRoutingCatalog = new BrainRoutingCatalog(expertManagementService);
            AgentScopeRuntimeAdapter adapter = new AgentScopeRuntimeAdapter(
                    agentRuntimeConfig,
                    expertManagementService,
                    toolManagementService,
                    skillManagementService,
                    store,
                    toolFactory,
                    expertToolResolver,
                    sessionHook,
                    skillFileSyncService,
                    sessionWorkspaceTools,
                    sessionWorkspaceService,
                    brainRoutingCatalog);
            SessionExpertRuntimeRegistry sessionExpertRuntimeRegistry = new SessionExpertRuntimeRegistry(
                    skillManagementService,
                    store,
                    expertToolResolver,
                    adapter,
                    brainRoutingCatalog);
            ExpertRuntimeRegistry registry = new ExpertRuntimeRegistry(
                    expertManagementService,
                    toolManagementService,
                    skillManagementService,
                    store,
                    expertToolResolver,
                    adapter,
                    brainRoutingCatalog,
                    providerOf(sessionExpertRuntimeRegistry));
            registryHolder[0] = registry;
            sessionRegistryHolder[0] = sessionExpertRuntimeRegistry;
            return new PlatformRuntimeFixture(
                    toolManagementService,
                    skillManagementService,
                    expertManagementService,
                    registry,
                    expertTaskService,
                    dispatchTool,
                    adapter,
                    sessionWorkspaceService,
                    pendingRegistry,
                    taskTextGuard,
                    brainContinuationService,
                    brainContinuerRef,
                    sessionExpertRuntimeRegistry);
        }

        private static ObjectProvider<SessionExpertRuntimeRegistry> providerOf(
                SessionExpertRuntimeRegistry[] holder) {
            return new ObjectProvider<>() {
                @Override
                public SessionExpertRuntimeRegistry getObject() {
                    return holder[0];
                }

                @Override
                public SessionExpertRuntimeRegistry getObject(Object... args) {
                    return holder[0];
                }

                @Override
                public SessionExpertRuntimeRegistry getIfAvailable() {
                    return holder[0];
                }

                @Override
                public SessionExpertRuntimeRegistry getIfUnique() {
                    return holder[0];
                }

                @Override
                public void ifAvailable(Consumer<SessionExpertRuntimeRegistry> consumer) {
                    if (holder[0] != null) {
                        consumer.accept(holder[0]);
                    }
                }
            };
        }

        private static ObjectProvider<SessionExpertRuntimeRegistry> providerOf(
                SessionExpertRuntimeRegistry registry) {
            return new ObjectProvider<>() {
                @Override
                public SessionExpertRuntimeRegistry getObject() {
                    return registry;
                }

                @Override
                public SessionExpertRuntimeRegistry getObject(Object... args) {
                    return registry;
                }

                @Override
                public SessionExpertRuntimeRegistry getIfAvailable() {
                    return registry;
                }

                @Override
                public SessionExpertRuntimeRegistry getIfUnique() {
                    return registry;
                }

                @Override
                public void ifAvailable(Consumer<SessionExpertRuntimeRegistry> consumer) {
                    consumer.accept(registry);
                }
            };
        }

        private static ObjectProvider<BrainRoundContinuer> continuerRefProvider(
                AtomicReference<BrainRoundContinuer> holder) {
            return new ObjectProvider<>() {
                @Override
                public BrainRoundContinuer getObject() {
                    return holder.get();
                }

                @Override
                public BrainRoundContinuer getObject(Object... args) {
                    return holder.get();
                }

                @Override
                public BrainRoundContinuer getIfAvailable() {
                    return holder.get();
                }

                @Override
                public BrainRoundContinuer getIfUnique() {
                    return holder.get();
                }

                @Override
                public void ifAvailable(Consumer<BrainRoundContinuer> consumer) {
                    BrainRoundContinuer continuer = holder.get();
                    if (continuer != null) {
                        consumer.accept(continuer);
                    }
                }
            };
        }

        private static ObjectProvider<ExpertRuntimeRegistry> providerOf(ExpertRuntimeRegistry[] holder) {
            return new ObjectProvider<>() {
                @Override
                public ExpertRuntimeRegistry getObject() {
                    return holder[0];
                }

                @Override
                public ExpertRuntimeRegistry getObject(Object... args) {
                    return holder[0];
                }

                @Override
                public ExpertRuntimeRegistry getIfAvailable() {
                    return holder[0];
                }

                @Override
                public ExpertRuntimeRegistry getIfUnique() {
                    return holder[0];
                }

                @Override
                public void ifAvailable(Consumer<ExpertRuntimeRegistry> consumer) {
                    if (holder[0] != null) {
                        consumer.accept(holder[0]);
                    }
                }
            };
        }

        private static ObjectProvider<ExpertTaskService> expertTaskServiceProvider(ExpertTaskService service) {
            return new ObjectProvider<>() {
                @Override
                public ExpertTaskService getObject() {
                    return service;
                }

                @Override
                public ExpertTaskService getObject(Object... args) {
                    return service;
                }

                @Override
                public ExpertTaskService getIfAvailable() {
                    return service;
                }

                @Override
                public ExpertTaskService getIfUnique() {
                    return service;
                }

                @Override
                public void ifAvailable(Consumer<ExpertTaskService> consumer) {
                    consumer.accept(service);
                }
            };
        }

        private static ObjectProvider<AiSessionStore> sessionStoreProvider(AiSessionStore store) {
            return new ObjectProvider<>() {
                @Override
                public AiSessionStore getObject() {
                    return store;
                }

                @Override
                public AiSessionStore getObject(Object... args) {
                    return store;
                }

                @Override
                public AiSessionStore getIfAvailable() {
                    return store;
                }

                @Override
                public AiSessionStore getIfUnique() {
                    return store;
                }

                @Override
                public void ifAvailable(Consumer<AiSessionStore> consumer) {
                    consumer.accept(store);
                }
            };
        }
    }

    public record PlatformRuntimeFixture(
            ToolManagementService toolManagementService,
            SkillManagementService skillManagementService,
            ExpertManagementService expertManagementService,
            ExpertRuntimeRegistry expertRuntimeRegistry,
            ExpertTaskService expertTaskService,
            ExpertDispatchTool expertDispatchTool,
            AgentScopeRuntimeAdapter runtimeAdapter,
            SessionWorkspaceService sessionWorkspaceService,
            ExpertTaskPendingRegistry expertTaskPendingRegistry,
            ExpertTaskTextGuard expertTaskTextGuard,
            BrainContinuationService brainContinuationService,
            AtomicReference<BrainRoundContinuer> brainContinuerRef,
            SessionExpertRuntimeRegistry sessionExpertRuntimeRegistry) {

        public void wireBrainContinuer(BrainRoundContinuer continuer) {
            brainContinuerRef.set(continuer);
        }
    }
}
