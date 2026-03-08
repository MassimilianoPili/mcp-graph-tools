package io.github.massimilianopili.mcp.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools per networking e configurazione — query sul grafo AGE knowledge_graph.
 * Sostituisce le sezioni Tailscale, Cloudflare, subpath config, pattern nginx di CLAUDE.md.
 */
@Service
@ConditionalOnProperty(name = "mcp.graph.enabled", havingValue = "true")
public class NetTools {

    private static final Logger log = LoggerFactory.getLogger(NetTools.class);

    private final Map<String, CypherExecutor> executors;

    public NetTools(@Qualifier("graphExecutors") Map<String, CypherExecutor> executors) {
        this.executors = executors;
    }

    // ─── Tool: net_get_endpoint ─────────────────────────────────────────────────

    @Tool(name = "net_get_endpoint",
          description = "URL di accesso per un servizio: Tailscale, pubblico, Tor. "
                      + "Cerca per nome servizio Docker e ritorna tutti gli endpoint associati. "
                      + "Sostituisce le tabelle 'URL Tailscale' e 'Cloudflare Tunnel' di CLAUDE.md.")
    public Map<String, Object> getEndpoint(
            @ToolParam(description = "Nome del servizio Docker (es. gitea, keycloak, proxy-ai)") String service) {
        CypherExecutor age = ageExecutor();
        if (age == null) return error("AGE backend non disponibile");
        String svc = esc(service);

        List<Map<String, Object>> endpoints = age.execute(
                "MATCH (s:DockerService {name: '" + svc + "'})-[:REACHABLE_AT]->(e:Endpoint) " +
                "RETURN {url: e.url, type: e.type, description: e.description} " +
                "ORDER BY e.type", null);

        if (endpoints.isEmpty()) {
            // Try searching in endpoint URL or description
            endpoints = age.execute(
                    "MATCH (e:Endpoint) WHERE e.url CONTAINS '" + svc + "' OR e.description CONTAINS '" + svc + "' " +
                    "RETURN {url: e.url, type: e.type, description: e.description} " +
                    "ORDER BY e.type", null);
        }

        if (endpoints.isEmpty()) return error("Nessun endpoint trovato per: " + service);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", service);
        result.put("endpoints", endpoints);
        return result;
    }

    // ─── Tool: net_get_subpath ──────────────────────────────────────────────────

    @Tool(name = "net_get_subpath",
          description = "Configurazione subpath di un servizio Docker: parametro, valore, effetto. "
                      + "Spiega come il servizio gestisce il subpath nginx (Pattern A: prefix stripping, "
                      + "Pattern B: gestione interna con SCRIPT_NAME/--base-url, BASE_PATH). "
                      + "Sostituisce la tabella 'Configurazioni subpath' di CLAUDE.md.")
    public Map<String, Object> getSubpath(
            @ToolParam(description = "Nome del servizio Docker (es. gitea, keycloak, pgadmin)") String service) {
        CypherExecutor age = ageExecutor();
        if (age == null) return error("AGE backend non disponibile");
        String svc = esc(service);

        List<Map<String, Object>> configs = age.execute(
                "MATCH (s:DockerService {name: '" + svc + "'})-[:CONFIGURED_WITH]->(c:SubpathConfig) " +
                "RETURN {parameter: c.parameter, value: c.value, effect: c.effect, description: c.description} " +
                "ORDER BY c.parameter", null);

        if (configs.isEmpty()) return error("Nessuna configurazione subpath per: " + service);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", service);
        result.put("configs", configs);
        return result;
    }

    // ─── Tool: net_get_nginx_pattern ────────────────────────────────────────────

    @Tool(name = "net_get_nginx_pattern",
          description = "Pattern architetturale nginx: lazy_dns, prefix_stripping, auth_request_oauth2, auth_request_jwt. "
                      + "Include descrizione, tipo, esempio di configurazione. "
                      + "Sostituisce la sezione 'Nginx — Pattern architetturali' di CLAUDE.md.")
    public Map<String, Object> getNginxPattern(
            @ToolParam(description = "Nome pattern: lazy_dns, prefix_stripping, auth_request_oauth2, auth_request_jwt") String name) {
        CypherExecutor age = ageExecutor();
        if (age == null) return error("AGE backend non disponibile");
        String n = esc(name);

        List<Map<String, Object>> rows = age.execute(
                "MATCH (p:NginxPattern {name: '" + n + "'}) " +
                "RETURN {name: p.name, pattern_type: p.pattern_type, " +
                "description: p.description, example: p.example}", null);

        if (rows.isEmpty()) {
            // List all patterns
            rows = age.execute(
                    "MATCH (p:NginxPattern) " +
                    "RETURN {name: p.name, pattern_type: p.pattern_type, description: p.description} " +
                    "ORDER BY p.name", null);
            if (!rows.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("error", "Pattern non trovato: " + name);
                result.put("available_patterns", rows);
                return result;
            }
            return error("Nessun pattern nginx nel grafo");
        }

        return new LinkedHashMap<>(rows.get(0));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private CypherExecutor ageExecutor() {
        return executors.get("age");
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
    }

    private Map<String, Object> error(String msg) {
        return Map.of("error", msg);
    }
}
