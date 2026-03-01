package io.github.massimilianopili.mcp.graph;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(name = "mcp.graph.enabled", havingValue = "true")
@Import({GraphConfig.class, GraphTools.class})
public class GraphToolsAutoConfiguration {

    @Bean("graphToolCallbackProvider")
    public ToolCallbackProvider graphToolCallbackProvider(GraphTools graphTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(graphTools)
                .build();
    }
}
