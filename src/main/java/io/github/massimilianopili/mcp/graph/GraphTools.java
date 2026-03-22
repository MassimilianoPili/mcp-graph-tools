package io.github.massimilianopili.mcp.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "mcp.graph.enabled", havingValue = "true")
public class GraphTools {

    private static final Logger log = LoggerFactory.getLogger(GraphTools.class);
    private static final int MAX_ROWS = 100;

    private final Map<String, CypherExecutor> executors;

    public GraphTools(@Qualifier("graphExecutors") Map<String, CypherExecutor> executors) {
        this.executors = executors;
    }

    @Tool(name = "graph_query",
          description = "Execute a read-only Cypher query on the graph database. "
                      + "Use for MATCH, RETURN, COUNT, path traversal. "
                      + "Supports neo4j and age (Apache AGE on PostgreSQL) backends. "
                      + "IMPORTANT: pass pure Cypher only (e.g. MATCH (n) RETURN n) — the SQL wrapper is added automatically. "
                      + "Do not use // comments in Cypher. For AGE use RETURN {k: v} map syntax.")
    public List<Map<String, Object>> query(
            @ToolParam(description = "Cypher query to execute (read-only: MATCH, RETURN, etc.)") String cypher,
            @ToolParam(description = "Optional parameters as JSON object (e.g. {\"name\": \"Alice\"})", required = false) Map<String, Object> params,
            @ToolParam(description = "Backend: neo4j or age (default: first available)", required = false) String backend) {
        try {
            CypherExecutor executor = resolveExecutor(backend);
            if (executor == null) {
                return List.of(Map.of("error", "Nessun backend graph disponibile" +
                        (backend != null ? ". Richiesto: " + backend : "")));
            }

            List<Map<String, Object>> results = executor.execute(cypher, params);
            if (results.size() > MAX_ROWS) {
                List<Map<String, Object>> truncated = new ArrayList<>(results.subList(0, MAX_ROWS));
                truncated.add(Map.of("_warning", "Risultati troncati a " + MAX_ROWS +
                        " righe (totale: " + results.size() + ")"));
                return truncated;
            }
            return results;
        } catch (Exception e) {
            log.error("Errore query graph: {}", e.getMessage());
            return List.of(Map.of("error", "Errore query: " + e.getMessage()));
        }
    }

    @Tool(name = "graph_write",
          description = "Execute a mutating Cypher query on the graph database. "
                      + "Use for CREATE, MERGE, SET, DELETE, DETACH DELETE. "
                      + "WARNING: this operation modifies data. "
                      + "IMPORTANT: pass pure Cypher only — the SQL wrapper is added automatically. "
                      + "Do not use // comments. Complex queries with multiple MERGE+MATCH must be split into separate calls.")
    public List<Map<String, Object>> write(
            @ToolParam(description = "Mutating Cypher query (CREATE, MERGE, SET, DELETE, etc.)") String cypher,
            @ToolParam(description = "Optional parameters as JSON object", required = false) Map<String, Object> params,
            @ToolParam(description = "Backend: neo4j or age (default: first available)", required = false) String backend) {
        try {
            CypherExecutor executor = resolveExecutor(backend);
            if (executor == null) {
                return List.of(Map.of("error", "Nessun backend graph disponibile"));
            }

            List<Map<String, Object>> results = executor.execute(cypher, params);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", "ok");
            summary.put("backend", executor.getType());
            summary.put("rows_returned", results.size());

            List<Map<String, Object>> response = new ArrayList<>();
            response.add(summary);
            response.addAll(results);
            return response;
        } catch (Exception e) {
            log.error("Errore write graph: {}", e.getMessage());
            return List.of(Map.of("error", "Errore write: " + e.getMessage()));
        }
    }

    @Tool(name = "graph_schema",
          description = "Show the graph database schema: node labels, relationship types, "
                      + "and properties for each. Useful for understanding the graph structure.")
    public Map<String, Object> schema(
            @ToolParam(description = "Backend: neo4j or age (default: first available)", required = false) String backend) {
        try {
            CypherExecutor executor = resolveExecutor(backend);
            if (executor == null) {
                return Map.of("error", "Nessun backend graph disponibile");
            }

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("backend", executor.getType());

            if ("neo4j".equals(executor.getType())) {
                // Node labels
                List<Map<String, Object>> labels = executor.execute(
                        "CALL db.labels() YIELD label RETURN label ORDER BY label", null);
                schema.put("node_labels", labels.stream().map(r -> r.get("label")).toList());

                // Relationship types
                List<Map<String, Object>> relTypes = executor.execute(
                        "CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType ORDER BY relationshipType", null);
                schema.put("relationship_types", relTypes.stream().map(r -> r.get("relationshipType")).toList());

                // Property keys
                List<Map<String, Object>> propKeys = executor.execute(
                        "CALL db.propertyKeys() YIELD propertyKey RETURN propertyKey ORDER BY propertyKey", null);
                schema.put("property_keys", propKeys.stream().map(r -> r.get("propertyKey")).toList());

            } else if ("age".equals(executor.getType())) {
                // AGE: single-column map syntax (AS (result agtype) requires one column)
                List<Map<String, Object>> labels = executor.execute(
                        "MATCH (n) WITH DISTINCT label(n) AS l RETURN {labels: l}", null);
                schema.put("node_labels", labels);

                List<Map<String, Object>> relTypes = executor.execute(
                        "MATCH ()-[r]->() WITH DISTINCT type(r) AS t RETURN {type: t}", null);
                schema.put("relationship_types", relTypes);
            }

            return schema;
        } catch (Exception e) {
            log.error("Errore schema graph: {}", e.getMessage());
            return Map.of("error", "Errore schema: " + e.getMessage());
        }
    }

    @Tool(name = "graph_stats",
          description = "Graph database statistics: total node and relationship counts, "
                      + "breakdown by label/type.")
    public Map<String, Object> stats(
            @ToolParam(description = "Backend: neo4j or age (default: first available)", required = false) String backend) {
        try {
            CypherExecutor executor = resolveExecutor(backend);
            if (executor == null) {
                return Map.of("error", "Nessun backend graph disponibile");
            }

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("backend", executor.getType());

            // Totale nodi
            List<Map<String, Object>> nodeCount = executor.execute(
                    "MATCH (n) RETURN count(n)", null);
            if (!nodeCount.isEmpty()) {
                stats.put("total_nodes", nodeCount.get(0).get("result"));
            }

            // Totale relazioni
            List<Map<String, Object>> relCount = executor.execute(
                    "MATCH ()-[r]->() RETURN count(r)", null);
            if (!relCount.isEmpty()) {
                stats.put("total_relationships", relCount.get(0).get("result"));
            }

            // Nodi per label
            if ("neo4j".equals(executor.getType())) {
                List<Map<String, Object>> byLabel = executor.execute(
                        "CALL db.labels() YIELD label " +
                        "CALL apoc.cypher.run('MATCH (n:`' + label + '`) RETURN count(n) AS count', {}) YIELD value " +
                        "RETURN label, value.count AS count ORDER BY count DESC", null);
                stats.put("nodes_by_label", byLabel);

                List<Map<String, Object>> byRelType = executor.execute(
                        "CALL db.relationshipTypes() YIELD relationshipType AS type " +
                        "CALL apoc.cypher.run('MATCH ()-[r:`' + type + '`]->() RETURN count(r) AS count', {}) YIELD value " +
                        "RETURN type, value.count AS count ORDER BY count DESC", null);
                stats.put("relationships_by_type", byRelType);
            } else {
                // AGE: single-column map syntax
                List<Map<String, Object>> byLabel = executor.execute(
                        "MATCH (n) WITH label(n) AS label, count(n) AS cnt ORDER BY cnt DESC " +
                        "RETURN {label: label, count: cnt}", null);
                stats.put("nodes_by_label", byLabel);
            }

            return stats;
        } catch (Exception e) {
            log.error("Errore stats graph: {}", e.getMessage());
            return Map.of("error", "Errore stats: " + e.getMessage());
        }
    }

    @Tool(name = "graph_list_backends",
          description = "List configured and available graph backends (neo4j, age).")
    public Map<String, Object> listBackends() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> backends = new ArrayList<>();

        for (Map.Entry<String, CypherExecutor> entry : executors.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", entry.getKey());
            info.put("type", entry.getValue().getType());
            info.put("status", "available");
            backends.add(info);
        }

        result.put("backends", backends);
        result.put("default", executors.isEmpty() ? null : executors.keySet().iterator().next());
        return result;
    }

    private CypherExecutor resolveExecutor(String backend) {
        if (executors.isEmpty()) return null;

        if (backend != null && !backend.isBlank()) {
            CypherExecutor executor = executors.get(backend);
            if (executor == null) {
                log.warn("Backend '{}' non trovato, disponibili: {}", backend, executors.keySet());
            }
            return executor;
        }

        // Default: primo disponibile
        return executors.values().iterator().next();
    }
}
