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

/**
 * MCP tools per l'infrastruttura SOL — query sul grafo AGE knowledge_graph.
 * Sostituisce la lettura manuale delle sezioni di CLAUDE.md (Servizi, Routing, Auth).
 *
 * Tutte le query usano il backend AGE. Il Cypher usa RETURN {k: v} map syntax
 * (compatibile con AgeCypherExecutor single-column senza modifiche all'executor).
 */
@Service
@ConditionalOnProperty(name = "mcp.graph.enabled", havingValue = "true")
public class InfraTools {

    private static final Logger log = LoggerFactory.getLogger(InfraTools.class);

    private final Map<String, CypherExecutor> executors;

    public InfraTools(@Qualifier("graphExecutors") Map<String, CypherExecutor> executors) {
        this.executors = executors;
    }

    // ─── Tool: infra_get_service ──────────────────────────────────────────────

    @Tool(name = "infra_get_service",
          description = "Full details for a Docker service: image, port, directory, "
                      + "exposed nginx routes (with auth), databases used, dependencies. "
                      + "Replaces the 'Services and Ports' table in CLAUDE.md.")
    public Map<String, Object> getService(
            @ToolParam(description = "Docker container name (e.g. gitea, keycloak, grafana)") String name) {
        CypherExecutor age = ageExecutor();
        if (age == null) return error("AGE backend non disponibile");
        String n = esc(name);

        List<Map<String, Object>> svcRows = age.execute(
                "MATCH (s:DockerService {name: '" + n + "'}) " +
                "RETURN {name: s.name, image: s.image, port_internal: s.port_internal, " +
                "directory: s.directory, memory_limit: s.memory_limit, last_synced: s.last_synced}", null);
        if (svcRows.isEmpty()) return error("Servizio non trovato: " + name);

        Map<String, Object> result = new LinkedHashMap<>(svcRows.get(0));

        List<Map<String, Object>> routeRows = age.execute(
                "MATCH (s:DockerService {name: '" + n + "'})-[:EXPOSED_VIA]->(r:NginxRoute)-[:USES_AUTH]->(a:AuthPattern) " +
                "RETURN {path: r.path, backend: r.backend, auth: a.name}", null);
        result.put("routes", routeRows);

        List<Map<String, Object>> dbRows = age.execute(
                "MATCH (s:DockerService {name: '" + n + "'})-[:USES_DATABASE]->(d:Database) " +
                "RETURN {name: d.name, engine: d.engine}", null);
        result.put("databases", dbRows.stream().map(r -> r.get("name")).toList());

        List<Map<String, Object>> depRows = age.execute(
                "MATCH (s:DockerService {name: '" + n + "'})-[:DEPENDS_ON]->(d:DockerService) " +
                "RETURN {name: d.name}", null);
        result.put("depends_on", depRows.stream().map(r -> r.get("name")).toList());

        return result;
    }

    // ─── Tool: infra_get_route ────────────────────────────────────────────────

    @Tool(name = "infra_get_route",
          description = "Details for an nginx route: backend, auth type, linked Docker service. "
                      + "Replaces the path-based routing table in CLAUDE.md.")
    public Map<String, Object> getRoute(
            @ToolParam(description = "Nginx path (e.g. /git/, /api/, /grafana/)") String path) {
        CypherExecutor age = ageExecutor();
        if (age == null) return error("AGE backend non disponibile");
        String p = esc(path);

        List<Map<String, Object>> routeRows = age.execute(
                "MATCH (r:NginxRoute {path: '" + p + "'})-[:USES_AUTH]->(a:AuthPattern) " +
                "RETURN {path: r.path, backend: r.backend, strip_prefix: r.strip_prefix, " +
                "exact: r.exact, auth_name: a.name, auth_type: a.type}", null);
        if (routeRows.isEmpty()) {
            // Try without auth relation (some routes may lack it)
            routeRows = age.execute(
                    "MATCH (r:NginxRoute {path: '" + p + "'}) " +
                    "RETURN {path: r.path, backend: r.backend, strip_prefix: r.strip_prefix, exact: r.exact}", null);
        }
        if (routeRows.isEmpty()) return error("Route non trovata: " + path);

        Map<String, Object> result = new LinkedHashMap<>(routeRows.get(0));

        // Add serving service
        List<Map<String, Object>> svcRows = age.execute(
                "MATCH (s:DockerService)-[:EXPOSED_VIA]->(r:NginxRoute {path: '" + p + "'}) " +
                "RETURN {name: s.name, image: s.image}", null);
        if (!svcRows.isEmpty()) result.put("service", svcRows.get(0));

        return result;
    }

    // ─── Tool: infra_find_by_auth ─────────────────────────────────────────────

    @Tool(name = "infra_find_by_auth",
          description = "List all routes and services using a given authentication type. "
                      + "Valid types: 'JWT Bearer', 'OIDC nativo', 'OAuth2 Proxy', 'SAML', 'Nessuna', 'Keycloak nativo'. "
                      + "Replaces the Authentication and SSO section of CLAUDE.md.")
    public List<Map<String, Object>> findByAuth(
            @ToolParam(description = "Auth pattern name (e.g. 'JWT Bearer', 'OAuth2 Proxy', 'OIDC nativo')") String authType) {
        CypherExecutor age = ageExecutor();
        if (age == null) return List.of(error("AGE backend non disponibile"));
        String a = esc(authType);

        return age.execute(
                "MATCH (s:DockerService)-[:EXPOSED_VIA]->(r:NginxRoute)-[:USES_AUTH]->(a:AuthPattern {name: '" + a + "'}) " +
                "RETURN {service: s.name, path: r.path, backend: r.backend}", null);
    }

    // ─── Tool: infra_get_dependencies ────────────────────────────────────────

    @Tool(name = "infra_get_dependencies",
          description = "Direct dependencies of a Docker service (depends_on in docker-compose). "
                      + "Shows both the service's dependencies and services that depend on it.")
    public Map<String, Object> getDependencies(
            @ToolParam(description = "Docker container name") String name) {
        CypherExecutor age = ageExecutor();
        if (age == null) return error("AGE backend non disponibile");
        String n = esc(name);

        List<Map<String, Object>> deps = age.execute(
                "MATCH (s:DockerService {name: '" + n + "'})-[:DEPENDS_ON]->(d:DockerService) " +
                "RETURN {name: d.name, image: d.image}", null);

        List<Map<String, Object>> dependants = age.execute(
                "MATCH (d:DockerService)-[:DEPENDS_ON]->(s:DockerService {name: '" + n + "'}) " +
                "RETURN {name: d.name, image: d.image}", null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", name);
        result.put("depends_on", deps);
        result.put("required_by", dependants);
        return result;
    }

    // ─── Tool: infra_get_db_consumers ────────────────────────────────────────

    @Tool(name = "infra_get_db_consumers",
          description = "List Docker services consuming a specific database. "
                      + "Valid databases: postgres, redis, mongodb, neo4j, libsql, age.")
    public List<Map<String, Object>> getDbConsumers(
            @ToolParam(description = "Database name (e.g. postgres, redis, mongodb)") String db) {
        CypherExecutor age = ageExecutor();
        if (age == null) return List.of(error("AGE backend non disponibile"));
        String d = esc(db);

        return age.execute(
                "MATCH (s:DockerService)-[:USES_DATABASE]->(d:Database {name: '" + d + "'}) " +
                "RETURN {service: s.name, image: s.image, directory: s.directory}", null);
    }

    // ─── Tool: infra_port_map ─────────────────────────────────────────────────

    @Tool(name = "infra_port_map",
          description = "Complete nginx path -> service -> auth mapping. "
                      + "Equivalent to the full routing table in CLAUDE.md. "
                      + "Use to answer questions like 'which path exposes X?' or 'which services are public?'.")
    public List<Map<String, Object>> portMap() {
        CypherExecutor age = ageExecutor();
        if (age == null) return List.of(error("AGE backend non disponibile"));

        return age.execute(
                "MATCH (s:DockerService)-[:EXPOSED_VIA]->(r:NginxRoute)-[:USES_AUTH]->(a:AuthPattern) " +
                "RETURN {path: r.path, service: s.name, backend: r.backend, auth: a.name} " +
                "ORDER BY r.path", null);
    }

    // ─── Tool: infra_search ───────────────────────────────────────────────────

    @Tool(name = "infra_search",
          description = "Full-text search across all infrastructure nodes (DockerService, NginxRoute, Database, AuthPattern). "
                      + "Searches all string property values.")
    public List<Map<String, Object>> search(
            @ToolParam(description = "Text to search for (case-insensitive)") String query) {
        CypherExecutor age = ageExecutor();
        if (age == null) return List.of(error("AGE backend non disponibile"));
        if (query == null || query.isBlank()) return List.of(error("Query vuota"));

        // Fetch all infra nodes and filter in Java (AGE lacks full-text index)
        List<Map<String, Object>> allNodes = age.execute(
                "MATCH (n) WHERE n.domain = 'infra' RETURN {id: id(n), label: labels(n)[0], " +
                "name: n.name, path: n.path, image: n.image, engine: n.engine, backend: n.backend}", null);

        String q = query.toLowerCase();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> node : allNodes) {
            boolean matched = node.values().stream()
                    .filter(v -> v instanceof String)
                    .anyMatch(v -> ((String) v).toLowerCase().contains(q));
            if (matched) results.add(node);
        }
        return results;
    }

    // ─── Tool: infra_update_status ────────────────────────────────────────────

    @Tool(name = "infra_update_status",
          description = "Update the operational status of a Docker service in the knowledge graph (e.g. 'running', 'stopped', 'degraded'). "
                      + "Useful after deploy or maintenance operations to keep the graph up to date.")
    public Map<String, Object> updateStatus(
            @ToolParam(description = "Docker container name") String name,
            @ToolParam(description = "Operational status: running, stopped, degraded, unknown") String status) {
        CypherExecutor age = ageExecutor();
        if (age == null) return error("AGE backend non disponibile");

        List<String> allowed = List.of("running", "stopped", "degraded", "unknown");
        if (!allowed.contains(status)) {
            return error("Stato non valido. Valori ammessi: " + allowed);
        }

        String n = esc(name);
        String s = esc(status);
        List<Map<String, Object>> rows = age.execute(
                "MATCH (svc:DockerService {name: '" + n + "'}) " +
                "SET svc.status = '" + s + "' " +
                "RETURN {name: svc.name, status: svc.status}", null);

        if (rows.isEmpty()) return error("Servizio non trovato: " + name);
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("updated", true);
        return result;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private CypherExecutor ageExecutor() {
        return executors.get("age");
    }

    /** Escape a string for inline embedding in AGE Cypher (single-quote safe). */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
    }

    private Map<String, Object> error(String msg) {
        return Map.of("error", msg);
    }
}
