# MCP Graph Tools

Spring Boot starter che fornisce tool MCP per graph database: Neo4j (Bolt) e Apache AGE (PostgreSQL). Query Cypher read/write, schema inspection, statistiche. Pubblicato su Maven Central come `io.github.massimilianopili:mcp-graph-tools`.

## Build

```bash
# Build
/opt/maven/bin/mvn clean compile

# Install locale (senza GPG)
/opt/maven/bin/mvn clean install -Dgpg.skip=true

# Deploy su Maven Central
/opt/maven/bin/mvn clean deploy
```

Java 17+ richiesto. Maven: `/opt/maven/bin/mvn`.

## Struttura Progetto

```
src/main/java/io/github/massimilianopili/mcp/graph/
├── GraphProperties.java              # @ConfigurationProperties(prefix = "mcp.graph")
├── CypherExecutor.java               # Interfaccia comune per esecuzione Cypher
├── Neo4jCypherExecutor.java           # Implementazione Neo4j (Bolt protocol)
├── AgeCypherExecutor.java             # Implementazione Apache AGE (PostgreSQL JDBC)
├── GraphConfig.java                  # Driver Neo4j + DataSource AGE, registry CypherExecutor
├── GraphTools.java                   # @Tool: query, write, schema, stats, backends
└── GraphToolsAutoConfiguration.java  # Spring Boot auto-config

src/main/resources/META-INF/spring/
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

## Tool (5 totali)

- `graph_query` — Query Cypher read-only (MATCH, RETURN, COUNT, path traversal). Parametri: cypher, params (JSON), backend (neo4j/age). Max 100 righe.
- `graph_write` — Query Cypher mutanti (CREATE, MERGE, SET, DELETE). Restituisce summary + righe risultato.
- `graph_schema` — Introspection: node labels, relationship types, property keys. Neo4j usa `CALL db.labels()`, AGE usa MATCH+DISTINCT.
- `graph_stats` — Statistiche: totale nodi/relazioni, conteggio per label/tipo. Neo4j usa APOC per conteggi per-label.
- `graph_list_backends` — Lista backend configurati e disponibili con stato.

## Architettura Multi-Backend

### CypherExecutor (interfaccia)
```java
List<Map<String, Object>> execute(String cypher, Map<String, Object> params);
String getType();  // "neo4j" o "age"
```

### Neo4jCypherExecutor
- Driver Bolt con connection pooling. Sessioni read-only per query.
- Converte tipi Neo4j: NODE → Map (_id, _labels, props), RELATIONSHIP → Map (_id, _type, _startNodeId, _endNodeId, props), PATH → Map (_length, _nodes).

### AgeCypherExecutor
- JDBC su PostgreSQL con estensione Apache AGE.
- Init automatico: `CREATE EXTENSION IF NOT EXISTS age` + `create_graph()`.
- Wrappa Cypher in SQL: `SELECT * FROM cypher('<graph>', $$ ... $$) AS (result agtype)`.
- Parsing agtype JSON (strip suffissi `::vertex`, `::edge`).
- Limitazione: AGE supporta un sottoinsieme di openCypher (no MERGE ON CREATE, no datetime(), no EXISTS).

### GraphConfig
- **Neo4j**: driver creato solo se `neo4jPassword` configurato. `verifyConnectivity()` allo startup.
- **AGE**: HikariCP DataSource dedicato (`age-pool`, max 2 conn). Creato solo se `ageEnabled=true` + `ageDbUrl`.
- Registry `Map<String, CypherExecutor>` con tutti i backend disponibili.

## Pattern Chiave

- **@Tool** (Spring AI): metodi sincroni. Registrati tramite `MethodToolCallbackProvider`.
- **Attivazione**: `@ConditionalOnProperty(name = "mcp.graph.enabled", havingValue = "true")`.
- **Backend resolution**: parametro `backend` opzionale nei tool. Se omesso, usa il primo disponibile.
- **Nessun SDK**: Neo4j usa il driver ufficiale Java, AGE usa JDBC puro.

## Configurazione

```properties
# Obbligatoria — abilita tutti i tool
MCP_GRAPH_ENABLED=true

# Neo4j (opzionale — attivo se neo4jPassword configurato)
MCP_GRAPH_NEO4J_URI=bolt://neo4j:7687          # default: bolt://neo4j:7687
MCP_GRAPH_NEO4J_USERNAME=neo4j                  # default: neo4j
MCP_GRAPH_NEO4J_PASSWORD=mypassword

# Apache AGE (opzionale — attivo se ageEnabled=true + ageDbUrl)
MCP_GRAPH_AGE_ENABLED=true
MCP_GRAPH_AGE_DB_URL=jdbc:postgresql://postgres:5432/embeddings
MCP_GRAPH_AGE_DB_USERNAME=postgres
MCP_GRAPH_AGE_DB_PASSWORD=dbpassword
MCP_GRAPH_AGE_GRAPH_NAME=knowledge_graph        # default: knowledge_graph
```

## Dipendenze

- Spring Boot 3.4.1 (spring-boot-autoconfigure, spring-boot-starter-jdbc)
- Spring AI 1.0.0 (spring-ai-model)
- Neo4j Java Driver 5.27.0
- PostgreSQL JDBC (provided)
- Jackson Databind (provided)

## Maven Central

- GroupId: `io.github.massimilianopili`
- Plugin: `central-publishing-maven-plugin` v0.6.0
- Credenziali: Central Portal token in `~/.m2/settings.xml` (server id: `central`)
