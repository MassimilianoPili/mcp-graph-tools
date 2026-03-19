# MCP Graph Tools

Spring Boot starter MCP per graph database (Apache AGE + Neo4j). Query Cypher, schema, stats, write. Include 19 tool infrastruttura specializzati (infra, auth, ops, net). Maven Central: `io.github.massimilianopili:mcp-graph-tools`.

## Build

```bash
/opt/maven/bin/mvn clean compile
/opt/maven/bin/mvn clean install -Dgpg.skip=true
/opt/maven/bin/mvn clean deploy
```

Java 17+. Maven: `/opt/maven/bin/mvn`.

Tool, struttura, configurazione e dipendenze: vedi [README.md](README.md).
Ricerca semantica: `embeddings_search_docs("mcp-graph-tools")`
