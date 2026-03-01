package io.github.massimilianopili.mcp.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Neo4jCypherExecutor implements CypherExecutor {

    private static final Logger log = LoggerFactory.getLogger(Neo4jCypherExecutor.class);
    private final Driver driver;

    public Neo4jCypherExecutor(Driver driver) {
        this.driver = driver;
    }

    @Override
    public List<Map<String, Object>> execute(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            Result result = session.run(cypher, params != null ? params : Map.of());
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                Map<String, Object> row = new LinkedHashMap<>();
                for (String key : record.keys()) {
                    row.put(key, convertValue(record.get(key)));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    @Override
    public String getType() {
        return "neo4j";
    }

    private Object convertValue(Value value) {
        if (value == null || value.isNull()) return null;

        switch (value.type().name()) {
            case "NODE" -> {
                Node node = value.asNode();
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("_id", node.id());
                map.put("_labels", node.labels());
                node.asMap().forEach(map::put);
                return map;
            }
            case "RELATIONSHIP" -> {
                Relationship rel = value.asRelationship();
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("_id", rel.id());
                map.put("_type", rel.type());
                map.put("_startNodeId", rel.startNodeId());
                map.put("_endNodeId", rel.endNodeId());
                rel.asMap().forEach(map::put);
                return map;
            }
            case "PATH" -> {
                Path path = value.asPath();
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("_length", path.length());
                List<Object> nodes = new ArrayList<>();
                path.nodes().forEach(n -> {
                    Map<String, Object> nm = new LinkedHashMap<>();
                    nm.put("_id", n.id());
                    nm.put("_labels", n.labels());
                    n.asMap().forEach(nm::put);
                    nodes.add(nm);
                });
                map.put("_nodes", nodes);
                return map;
            }
            case "LIST" -> {
                return value.asList(this::convertValue);
            }
            case "MAP" -> {
                Map<String, Object> map = new LinkedHashMap<>();
                value.asMap(this::convertValue).forEach(map::put);
                return map;
            }
            default -> {
                return value.asObject();
            }
        }
    }
}
