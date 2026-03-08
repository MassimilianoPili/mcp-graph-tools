package io.github.massimilianopili.mcp.graph;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(name = "mcp.graph.enabled", havingValue = "true")
@Import({GraphConfig.class, GraphTools.class, InfraTools.class, AuthTools.class, OpsTools.class, NetTools.class})
public class GraphToolsAutoConfiguration {

    @Bean("graphToolCallbackProvider")
    public ToolCallbackProvider graphToolCallbackProvider(GraphTools graphTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(graphTools)
                .build();
    }

    @Bean("infraToolCallbackProvider")
    public ToolCallbackProvider infraToolCallbackProvider(InfraTools infraTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(infraTools)
                .build();
    }

    @Bean("authToolCallbackProvider")
    public ToolCallbackProvider authToolCallbackProvider(AuthTools authTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(authTools)
                .build();
    }

    @Bean("opsToolCallbackProvider")
    public ToolCallbackProvider opsToolCallbackProvider(OpsTools opsTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(opsTools)
                .build();
    }

    @Bean("netToolCallbackProvider")
    public ToolCallbackProvider netToolCallbackProvider(NetTools netTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(netTools)
                .build();
    }
}
