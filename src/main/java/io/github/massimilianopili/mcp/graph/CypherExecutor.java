package io.github.massimilianopili.mcp.graph;

import java.util.List;
import java.util.Map;

public interface CypherExecutor {

    List<Map<String, Object>> execute(String cypher, Map<String, Object> params);

    String getType();
}
