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
 * MCP tools per conoscenza operativa — query sul grafo AGE knowledge_graph.
 * Sostituisce le sezioni Operazioni comuni, Troubleshooting, Convenzioni di CLAUDE.md.
 */
@Service
@ConditionalOnProperty(name = "mcp.graph.enabled", havingValue = "true")
public class OpsTools {

    private static final Logger log = LoggerFactory.getLogger(OpsTools.class);

    private final Map<String, CypherExecutor> executors;

    public OpsTools(@Qualifier("graphExecutors") Map<String, CypherExecutor> executors) {
        this.executors = executors;
    }

    // ─── Tool: ops_get_command ──────────────────────────────────────────────────

    @Tool(name = "ops_get_command",
          description = "Cerca comandi operativi per nome o categoria. "
                      + "Categorie: docker, systemd, maintenance, emergenza, query, cicd, sso, ssh, network, wiki. "
                      + "Se il parametro matcha un nome esatto, ritorna quel comando. "
                      + "Altrimenti cerca per categoria e ritorna tutti i comandi della categoria. "
                      + "Sostituisce la sezione 'Operazioni comuni' di CLAUDE.md.")
    public List<Map<String, Object>> getCommand(
            @ToolParam(description = "Nome comando (es. nginx_force_recreate) o categoria (es. docker, sso)") String nameOrCategory) {
        CypherExecutor age = ageExecutor();
        if (age == null) return List.of(error("AGE backend non disponibile"));
        String q = esc(nameOrCategory);

        // Try exact name first
        List<Map<String, Object>> rows = age.execute(
                "MATCH (c:Command {name: '" + q + "'}) " +
                "RETURN {name: c.name, category: c.category, syntax: c.syntax, description: c.description}", null);
        if (!rows.isEmpty()) {
            // Add related services
            Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
            List<Map<String, Object>> svcRows = age.execute(
                    "MATCH (c:Command {name: '" + q + "'})-[:APPLIES_TO]->(s:DockerService) " +
                    "RETURN {name: s.name}", null);
            result.put("related_services", svcRows.stream().map(r -> r.get("name")).toList());
            return List.of(result);
        }

        // Try category
        rows = age.execute(
                "MATCH (c:Command {category: '" + q + "'}) " +
                "RETURN {name: c.name, category: c.category, syntax: c.syntax, description: c.description} " +
                "ORDER BY c.name", null);
        if (!rows.isEmpty()) return rows;

        // Fuzzy search in description
        rows = age.execute(
                "MATCH (c:Command) WHERE c.description CONTAINS '" + q + "' OR c.name CONTAINS '" + q + "' " +
                "RETURN {name: c.name, category: c.category, syntax: c.syntax, description: c.description} " +
                "ORDER BY c.name", null);
        if (!rows.isEmpty()) return rows;

        return List.of(error("Nessun comando trovato per: " + nameOrCategory));
    }

    // ─── Tool: ops_troubleshoot ─────────────────────────────────────────────────

    @Tool(name = "ops_troubleshoot",
          description = "Cerca soluzioni per un problema o un servizio. "
                      + "Cerca nel campo 'problem' e 'cause' dei nodi Troubleshooting. "
                      + "Se il parametro e' un nome servizio, ritorna tutti i troubleshooting collegati. "
                      + "Problemi noti: SSO Gitea, OAuth2 Proxy 500, nginx 500/502, Gitea 404, "
                      + "Keycloak discovery, container disconnessi, WikiJS SAML doppio login. "
                      + "Sostituisce la sezione 'Troubleshooting' di CLAUDE.md.")
    public List<Map<String, Object>> troubleshoot(
            @ToolParam(description = "Problema (es. 'nginx 502') o nome servizio (es. 'keycloak')") String problemOrService) {
        CypherExecutor age = ageExecutor();
        if (age == null) return List.of(error("AGE backend non disponibile"));
        String q = esc(problemOrService);

        // Search by service first
        List<Map<String, Object>> svcRows = age.execute(
                "MATCH (t:Troubleshooting)-[:RESOLVES]->(s:DockerService {name: '" + q + "'}) " +
                "RETURN {problem: t.problem, cause: t.cause, solution: t.solution}", null);
        if (!svcRows.isEmpty()) return svcRows;

        // Search in problem field
        List<Map<String, Object>> rows = age.execute(
                "MATCH (t:Troubleshooting) WHERE t.problem CONTAINS '" + q + "' " +
                "RETURN {problem: t.problem, cause: t.cause, solution: t.solution}", null);
        if (!rows.isEmpty()) {
            // Enrich with related services
            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> result = new LinkedHashMap<>(row);
                String problem = esc((String) row.get("problem"));
                List<Map<String, Object>> relSvc = age.execute(
                        "MATCH (t:Troubleshooting {problem: '" + problem + "'})-[:RESOLVES]->(s:DockerService) " +
                        "RETURN {name: s.name}", null);
                result.put("services", relSvc.stream().map(r -> r.get("name")).toList());
                enriched.add(result);
            }
            return enriched;
        }

        // Search in cause field
        rows = age.execute(
                "MATCH (t:Troubleshooting) WHERE t.cause CONTAINS '" + q + "' " +
                "RETURN {problem: t.problem, cause: t.cause, solution: t.solution}", null);
        if (!rows.isEmpty()) return rows;

        return List.of(error("Nessun troubleshooting trovato per: " + problemOrService));
    }

    // ─── Tool: ops_get_convention ───────────────────────────────────────────────

    @Tool(name = "ops_get_convention",
          description = "Convenzioni operative per categoria. "
                      + "Categorie: git, docker, workflow, documentation, communication, security, operations. "
                      + "Senza parametro, ritorna tutte le convenzioni. "
                      + "Sostituisce le convenzioni sparse in CLAUDE.md e MEMORY.md.")
    public List<Map<String, Object>> getConvention(
            @ToolParam(description = "Categoria (es. git, docker, workflow) o vuoto per tutte") String category) {
        CypherExecutor age = ageExecutor();
        if (age == null) return List.of(error("AGE backend non disponibile"));

        if (category == null || category.isBlank()) {
            return age.execute(
                    "MATCH (c:Convention) " +
                    "RETURN {name: c.name, category: c.category, rule: c.rule, rationale: c.rationale} " +
                    "ORDER BY c.category, c.name", null);
        }

        String cat = esc(category);
        List<Map<String, Object>> rows = age.execute(
                "MATCH (c:Convention {category: '" + cat + "'}) " +
                "RETURN {name: c.name, category: c.category, rule: c.rule, rationale: c.rationale} " +
                "ORDER BY c.name", null);
        if (!rows.isEmpty()) return rows;

        // Fuzzy search
        rows = age.execute(
                "MATCH (c:Convention) WHERE c.rule CONTAINS '" + cat + "' OR c.name CONTAINS '" + cat + "' " +
                "RETURN {name: c.name, category: c.category, rule: c.rule, rationale: c.rationale} " +
                "ORDER BY c.name", null);
        if (!rows.isEmpty()) return rows;

        return List.of(error("Nessuna convenzione trovata per: " + category));
    }

    // ─── Tool: ops_list_systemd ─────────────────────────────────────────────────

    @Tool(name = "ops_list_systemd",
          description = "Lista tutti i servizi systemd user-level e system-level registrati nel grafo. "
                      + "Include: dashboard-api, ttyd, ssh-agent, claude-cleanup, wiki-embargo, "
                      + "infra-graph-sync, tailscale-watchdog. "
                      + "Ritorna unit_file, tipo, exec_start, descrizione. "
                      + "Sostituisce le sezioni servizi systemd di CLAUDE.md.")
    public List<Map<String, Object>> listSystemd() {
        CypherExecutor age = ageExecutor();
        if (age == null) return List.of(error("AGE backend non disponibile"));

        return age.execute(
                "MATCH (s:SystemdService) " +
                "RETURN {name: s.name, unit_file: s.unit_file, type: s.type, " +
                "exec_start: s.exec_start, user_level: s.user_level, description: s.description} " +
                "ORDER BY s.name", null);
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
