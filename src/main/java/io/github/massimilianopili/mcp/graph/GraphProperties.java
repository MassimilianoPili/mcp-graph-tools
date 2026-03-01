package io.github.massimilianopili.mcp.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.graph")
public class GraphProperties {

    private boolean enabled;

    // Neo4j
    private String neo4jUri = "bolt://neo4j:7687";
    private String neo4jUsername = "neo4j";
    private String neo4jPassword;

    // Apache AGE (PostgreSQL)
    private boolean ageEnabled = false;
    private String ageDbUrl;
    private String ageDbUsername;
    private String ageDbPassword;
    private String ageGraphName = "knowledge_graph";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getNeo4jUri() { return neo4jUri; }
    public void setNeo4jUri(String neo4jUri) { this.neo4jUri = neo4jUri; }

    public String getNeo4jUsername() { return neo4jUsername; }
    public void setNeo4jUsername(String neo4jUsername) { this.neo4jUsername = neo4jUsername; }

    public String getNeo4jPassword() { return neo4jPassword; }
    public void setNeo4jPassword(String neo4jPassword) { this.neo4jPassword = neo4jPassword; }

    public boolean isAgeEnabled() { return ageEnabled; }
    public void setAgeEnabled(boolean ageEnabled) { this.ageEnabled = ageEnabled; }

    public String getAgeDbUrl() { return ageDbUrl; }
    public void setAgeDbUrl(String ageDbUrl) { this.ageDbUrl = ageDbUrl; }

    public String getAgeDbUsername() { return ageDbUsername; }
    public void setAgeDbUsername(String ageDbUsername) { this.ageDbUsername = ageDbUsername; }

    public String getAgeDbPassword() { return ageDbPassword; }
    public void setAgeDbPassword(String ageDbPassword) { this.ageDbPassword = ageDbPassword; }

    public String getAgeGraphName() { return ageGraphName; }
    public void setAgeGraphName(String ageGraphName) { this.ageGraphName = ageGraphName; }
}
