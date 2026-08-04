package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.JavaBeanToolAllowlist;
import com.databuff.apm.web.ai.platform.tool.ToolType;
import com.databuff.apm.web.ai.platform.task.ExpertDispatchTool;
import com.databuff.apm.web.tools.local.BashTools;
import com.databuff.apm.web.tools.local.CommonTools;
import com.databuff.apm.web.tools.local.DataTools;
import com.databuff.apm.web.tools.local.InspectTools;
import com.databuff.apm.web.tools.local.LogTools;
import com.databuff.apm.web.tools.local.PlatformTools;
import com.databuff.apm.web.tools.local.TimeTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AgentScopeToolFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeToolFactory.class);

    @Autowired
    private DataTools dataTools;
    @Autowired
    private TimeTool timeTool;
    @Autowired
    private CommonTools commonTools;
    @Autowired
    private InspectTools inspectTools;
    @Autowired
    private LogTools logTools;
    @Autowired
    private ExpertDispatchTool expertDispatchTool;
    @Autowired
    private BashTools bashTools;
    @Autowired
    private PlatformTools platformTools;
    @Autowired
    private RemoteMcpToolRegistrar remoteMcpToolRegistrar;

    public List<McpClientWrapper> registerTools(Toolkit toolkit, List<AiToolDefinition> tools) {
        List<McpClientWrapper> mcpClients = new ArrayList<>();
        if (toolkit == null || tools == null || tools.isEmpty()) {
            return mcpClients;
        }
        Set<String> registered = new HashSet<>();
        for (AiToolDefinition tool : tools) {
            if (tool == null || !tool.enabled()) {
                continue;
            }
            if (tool.type() == ToolType.JAVA_BEAN) {
                registerJavaBeanTool(toolkit, tool, registered);
            } else if (tool.type() == ToolType.MCP) {
                McpClientWrapper client = remoteMcpToolRegistrar.register(toolkit, tool);
                if (client != null) {
                    mcpClients.add(client);
                }
            } else {
                log.warn("Unsupported tool type {} for tool {}", tool.type(), tool.toolId());
            }
        }
        return mcpClients;
    }

    private void registerJavaBeanTool(Toolkit toolkit, AiToolDefinition tool, Set<String> registered) {
        String implementation = tool.implementation();
        if (implementation == null || implementation.isBlank()) {
            log.warn("Tool {} has empty implementation", tool.toolId());
            return;
        }
        if (!JavaBeanToolAllowlist.isAllowed(implementation)) {
            log.warn("Tool {} implementation {} is not allowlisted", tool.toolId(), implementation);
            return;
        }
        String beanName = implementation.substring(0, implementation.indexOf('.'));
        if (!registered.add(beanName)) {
            return;
        }
        if ("commonTools".equals(beanName) && commonTools != null) {
            toolkit.registerTool(commonTools);
        } else if ("dataTools".equals(beanName) && dataTools != null) {
            toolkit.registerTool(dataTools);
        } else if ("inspectTools".equals(beanName) && inspectTools != null) {
            toolkit.registerTool(inspectTools);
        } else if ("logTools".equals(beanName) && logTools != null) {
            toolkit.registerTool(logTools);
        } else if ("timeTool".equals(beanName) && timeTool != null) {
            toolkit.registerTool(timeTool);
        } else if ("expertDispatchTool".equals(beanName) && expertDispatchTool != null) {
            toolkit.registerTool(expertDispatchTool);
        } else if ("bashTools".equals(beanName) && bashTools != null) {
            toolkit.registerTool(bashTools);
        } else if ("platformTools".equals(beanName) && platformTools != null) {
            toolkit.registerTool(platformTools);
        } else {
            log.warn("Tool {} implementation {} has no registered local bean", tool.toolId(), implementation);
        }
    }
}
