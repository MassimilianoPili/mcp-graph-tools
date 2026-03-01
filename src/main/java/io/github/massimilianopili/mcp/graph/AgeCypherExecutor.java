package io.github.massimilianopili.mcp.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgeCypherExecutor implements CypherExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgeCypherExecutor.class);
    private final JdbcTemplate jdbc;
    private final String graphName;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgeCypherExecutor(JdbcTemplate jdbc, String graphName) {
        this.jdbc = jdbc;
        this.graphName = graphName;
    }

    public void initGraph() {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS age");
            jdbc.execute("LOAD 'age'");
            jdbc.execute("SET search_path = ag_catalog, \"$user\", public");
            try {
                jdbc.execute("SELECT create_graph('" + graphName + "')");
                log.info("AGE graph '{}' creato", graphName);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                    log.debug("AGE graph '{}' gia' esistente", graphName);
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            log.error("Errore inizializzazione AGE: {}", e.getMessage());
            throw new RuntimeException("Inizializzazione AGE fallita", e);
        }
    }

    @Override
    public List<Map<String, Object>> execute(String cypher, Map<String, Object> params) {
        jdbc.execute("LOAD 'age'");
        jdbc.execute("SET search_path = ag_catalog, \"$user\", public");

        String sql = "SELECT * FROM cypher('" + graphName + "', $$ " + cypher + " $$) AS (result agtype)";

        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query(sql, rs -> {
            while (rs.next()) {
                String agtype = rs.getString("result");
                Map<String, Object> row = new LinkedHashMap<>();
                try {
                    if (agtype != null && (agtype.startsWith("{") || agtype.startsWith("["))) {
                        String cleaned = cleanAgtype(agtype);
                        Object parsed = mapper.readValue(cleaned, Object.class);
                        if (parsed instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> mapParsed = (Map<String, Object>) parsed;
                            row.putAll(mapParsed);
                        } else {
                            row.put("result", parsed);
                        }
                    } else {
                        row.put("result", agtype);
                    }
                } catch (Exception e) {
                    row.put("result", agtype);
                }
                rows.add(row);
            }
            return null;
        });

        return rows;
    }

    @Override
    public String getType() {
        return "age";
    }

    private String cleanAgtype(String agtype) {
        return agtype.replaceAll("::vertex|::edge|::path|::numeric|::integer|::float|::boolean|::string", "");
    }
}
