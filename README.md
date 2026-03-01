# MCP Graph Tools

Spring Boot starter providing 5 MCP tools for graph database operations. Supports both Neo4j (Bolt protocol) and Apache AGE (PostgreSQL extension) as backends with a unified Cypher query interface.

## Installation

```xml
<dependency>
    <groupId>io.github.massimilianopili</groupId>
    <artifactId>mcp-graph-tools</artifactId>
    <version>0.1.0</version>
</dependency>
```

Requires Java 21+ and Spring AI 1.0.0+.

## Tools

| Tool | Description |
|------|-------------|
| `graph_query` | Execute read-only Cypher queries (MATCH, RETURN, COUNT, path traversal). Max 100 rows. |
| `graph_write` | Execute mutating Cypher (CREATE, MERGE, SET, DELETE). Returns summary + result rows. |
| `graph_schema` | Schema introspection: node labels, relationship types, property keys. |
| `graph_stats` | Statistics: total nodes/relationships, counts per label/type. |
| `graph_list_backends` | List configured backends with availability status. |

All tools accept an optional `backend` parameter (`"neo4j"` or `"age"`) — defaults to first available.

## Backends

| Backend | Protocol | Notes |
|---------|----------|-------|
| **Neo4j** | Bolt (Java Driver 5.27) | Full openCypher support, APOC for advanced stats |
| **Apache AGE** | JDBC (PostgreSQL) | Cypher subset on PostgreSQL, zero additional infra if you already have Postgres |

## Configuration

```properties
# Required — enables all graph tools
MCP_GRAPH_ENABLED=true

# Neo4j (optional — active when password is set)
MCP_GRAPH_NEO4J_URI=bolt://neo4j:7687
MCP_GRAPH_NEO4J_USERNAME=neo4j
MCP_GRAPH_NEO4J_PASSWORD=mypassword

# Apache AGE (optional — active when ageEnabled=true + ageDbUrl set)
MCP_GRAPH_AGE_ENABLED=true
MCP_GRAPH_AGE_DB_URL=jdbc:postgresql://localhost:5432/mydb
MCP_GRAPH_AGE_DB_USERNAME=postgres
MCP_GRAPH_AGE_DB_PASSWORD=secret
MCP_GRAPH_AGE_GRAPH_NAME=knowledge_graph    # default: knowledge_graph
```

Both backends can be active simultaneously. The `backend` parameter in each tool selects which one to use.

## How It Works

- Uses `@Tool` (Spring AI) for synchronous MCP tool methods
- Auto-configured via `GraphToolsAutoConfiguration` with `@ConditionalOnProperty(name = "mcp.graph.enabled", havingValue = "true")`
- `CypherExecutor` interface abstracts both backends
- Neo4j: Bolt driver with connection pooling, type conversion for Node/Relationship/Path
- AGE: auto-creates extension and graph, wraps Cypher in SQL, parses agtype JSON

## Requirements

- Java 21+
- Spring Boot 3.4+
- Spring AI 1.0.0+
- Neo4j 5.x (for Neo4j backend) and/or PostgreSQL with AGE extension (for AGE backend)

## License

[MIT License](LICENSE)
