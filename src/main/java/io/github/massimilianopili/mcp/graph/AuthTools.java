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
 * MCP tools per Keycloak SSO — query sul grafo AGE knowledge_graph.
 * Sostituisce le sezioni Autenticazione, SSO, OAuth2 Proxy, SAML, JWT di CLAUDE.md.
 */
@Service
@ConditionalOnProperty(name = "mcp.graph.enabled", havingValue = "true")
public class AuthTools {

    private static final Logger log = LoggerFactory.getLogger(AuthTools.class);

    private final Map<String, CypherExecutor> executors;

    public AuthTools(@Qualifier("graphExecutors") Map<String, CypherExecutor> executors) {
        this.executors = executors;
    }

    // ─── Tool: auth_get_client ─────────────────────────────────────────────────

    @Tool(name = "auth_get_client",
          description = "Dettagli completi di un client Keycloak: protocollo, redirect URI, ruoli definiti, "
                      + "servizi che lo usano, route protette. Sostituisce la sezione SSO di CLAUDE.md. "
                      + "Client noti: gitea, oauth2-proxy, go-filemanager, dashboard-chat, wiki, grafana, "
                      + "knowledge-graph, minio, jenkins, server-api, nvidia_client, claude_client, codex_client.")
    public Map<String, Object> getClient(
            @ToolParam(description = "Client ID Keycloak (es. gitea, oauth2-proxy, wiki)") String clientId) {
        CypherExecutor age = ageExecutor();
        if (age == null) return error("AGE backend non disponibile");
        String cid = esc(clientId);

        // Client details
        List<Map<String, Object>> clientRows = age.execute(
                "MATCH (c:KeycloakClient {client_id: '" + cid + "'}) " +
                "RETURN {client_id: c.client_id, protocol: c.protocol, description: c.description, " +
                "redirect_uris: c.redirect_uris, web_origins: c.web_origins, " +
                "entry_point: c.entry_point, nameid_format: c.nameid_format, binding: c.binding, " +
                "group_claim: c.group_claim, admin_group: c.admin_group}", null);
        if (clientRows.isEmpty()) return error("Client non trovato: " + clientId);

        Map<String, Object> result = new LinkedHashMap<>(clientRows.get(0));

        // Realm
        List<Map<String, Object>> realmRows = age.execute(
                "MATCH (c:KeycloakClient {client_id: '" + cid + "'})-[:BELONGS_TO]->(r:KeycloakRealm) " +
                "RETURN {realm: r.name, hostname: r.hostname}", null);
        if (!realmRows.isEmpty()) result.put("realm", realmRows.get(0));

        // Roles defined by this client
        List<Map<String, Object>> roleRows = age.execute(
                "MATCH (c:KeycloakClient {client_id: '" + cid + "'})-[:DEFINES_ROLE]->(r:KeycloakRole) " +
                "RETURN {name: r.name, description: r.description}", null);
        result.put("roles", roleRows);

        // Services authenticating with this client
        List<Map<String, Object>> svcRows = age.execute(
                "MATCH (s:DockerService)-[:AUTHENTICATES_WITH]->(c:KeycloakClient {client_id: '" + cid + "'}) " +
                "RETURN {service: s.name, image: s.image}", null);
        result.put("services", svcRows);

        // Routes protected by this client
        List<Map<String, Object>> routeRows = age.execute(
                "MATCH (r:NginxRoute)-[:PROTECTED_BY]->(c:KeycloakClient {client_id: '" + cid + "'}) " +
                "RETURN {path: r.path, backend: r.backend}", null);
        result.put("protected_routes", routeRows);

        return result;
    }

    // ─── Tool: auth_get_flow ──────────────────────────────────────────────────

    @Tool(name = "auth_get_flow",
          description = "Flusso di autenticazione completo per un tipo di auth: servizi coinvolti, "
                      + "client Keycloak, route protette, configurazione. "
                      + "Tipi: 'oidc' (OIDC nativo), 'saml', 'oauth2-proxy' (OAuth2 Proxy), 'jwt' (JWT Bearer).")
    public Map<String, Object> getFlow(
            @ToolParam(description = "Tipo di flusso: oidc, saml, oauth2-proxy, jwt") String flowType) {
        CypherExecutor age = ageExecutor();
        if (age == null) return error("AGE backend non disponibile");
        String ft = esc(flowType).toLowerCase();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flow_type", ft);

        // Map flow type to protocol or auth pattern
        String protocolFilter;
        String authPatternFilter;
        switch (ft) {
            case "oidc":
                protocolFilter = "oidc";
                authPatternFilter = "OIDC nativo";
                result.put("description", "OIDC nativo — il servizio parla direttamente con Keycloak. "
                        + "Discovery URL backchannel: http://keycloak:8080/auth/realms/sol/.well-known/openid-configuration");
                break;
            case "saml":
                protocolFilter = "saml";
                authPatternFilter = "SAML";
                result.put("description", "SAML — il servizio usa SAML via Keycloak. "
                        + "Entry point: https://sol.massimilianopili.com/auth/realms/sol/protocol/saml");
                break;
            case "oauth2-proxy":
                protocolFilter = null;
                authPatternFilter = "OAuth2 Proxy";
                result.put("description", "OAuth2 Proxy — nginx auth_request delega a oauth2-proxy, "
                        + "che autentica via Keycloak OIDC. Due istanze: Tailscale (:4180) e Pubblica (:4181).");
                break;
            case "jwt":
                protocolFilter = null;
                authPatternFilter = "JWT Bearer";
                result.put("description", "JWT Bearer — nginx auth_request /internal/jwt/validate, "
                        + "delegato a jwt-gateway:8094. Valida firma RS256 via Keycloak JWKS. "
                        + "Enforce read-only per ruolo readonly (403 su POST/PUT/DELETE/PATCH).");
                break;
            default:
                return error("Tipo non valido. Valori: oidc, saml, oauth2-proxy, jwt");
        }

        // Clients with this protocol
        if (protocolFilter != null) {
            List<Map<String, Object>> clients = age.execute(
                    "MATCH (c:KeycloakClient {protocol: '" + esc(protocolFilter) + "'}) " +
                    "RETURN {client_id: c.client_id, description: c.description}", null);
            result.put("clients", clients);
        }

        // Routes with this auth pattern
        List<Map<String, Object>> routes = age.execute(
                "MATCH (r:NginxRoute)-[:USES_AUTH]->(a:AuthPattern {name: '" + esc(authPatternFilter) + "'}) " +
                "RETURN {path: r.path, backend: r.backend}", null);
        result.put("routes", routes);

        // Services on those routes
        List<Map<String, Object>> services = age.execute(
                "MATCH (s:DockerService)-[:EXPOSED_VIA]->(r:NginxRoute)-[:USES_AUTH]->(a:AuthPattern {name: '" + esc(authPatternFilter) + "'}) " +
                "RETURN {service: s.name, path: r.path}", null);
        result.put("services", services);

        return result;
    }

    // ─── Tool: auth_list_clients ──────────────────────────────────────────────

    @Tool(name = "auth_list_clients",
          description = "Lista tutti i client Keycloak nel realm SOL con protocollo e descrizione sintetica.")
    public List<Map<String, Object>> listClients() {
        CypherExecutor age = ageExecutor();
        if (age == null) return List.of(error("AGE backend non disponibile"));

        return age.execute(
                "MATCH (c:KeycloakClient)-[:BELONGS_TO]->(r:KeycloakRealm) " +
                "RETURN {client_id: c.client_id, protocol: c.protocol, " +
                "realm: r.name, description: c.description} " +
                "ORDER BY c.client_id", null);
    }

    // ─── Tool: auth_get_user ──────────────────────────────────────────────────

    @Tool(name = "auth_get_user",
          description = "Info su un utente Keycloak/Gitea: email, admin status, login source, ruoli assegnati, "
                      + "client associati. Utenti noti: sol_root, root, massimiliano, visitor.")
    public Map<String, Object> getUser(
            @ToolParam(description = "Username (es. sol_root, visitor)") String username) {
        CypherExecutor age = ageExecutor();
        if (age == null) return error("AGE backend non disponibile");
        String u = esc(username);

        List<Map<String, Object>> userRows = age.execute(
                "MATCH (u:KeycloakUser {username: '" + u + "'}) " +
                "RETURN {username: u.username, email: u.email, is_admin: u.is_admin, " +
                "login_source: u.login_source, description: u.description}", null);
        if (userRows.isEmpty()) return error("Utente non trovato: " + username);

        Map<String, Object> result = new LinkedHashMap<>(userRows.get(0));

        // Roles
        List<Map<String, Object>> roleRows = age.execute(
                "MATCH (u:KeycloakUser {username: '" + u + "'})-[:HAS_ROLE]->(r:KeycloakRole) " +
                "RETURN {role: r.name, client_id: r.client_id, description: r.description}", null);
        result.put("roles", roleRows);

        // Associated clients (via roles)
        List<Map<String, Object>> clientRows = age.execute(
                "MATCH (u:KeycloakUser {username: '" + u + "'})-[:HAS_ROLE]->(r:KeycloakRole)" +
                "<-[:DEFINES_ROLE]-(c:KeycloakClient) " +
                "RETURN {client_id: c.client_id, protocol: c.protocol}", null);
        result.put("associated_clients", clientRows);

        return result;
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
