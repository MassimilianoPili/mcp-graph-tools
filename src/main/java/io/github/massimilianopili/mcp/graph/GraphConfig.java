package io.github.massimilianopili.mcp.graph;

import com.zaxxer.hikari.HikariDataSource;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "mcp.graph.enabled", havingValue = "true")
@EnableConfigurationProperties(GraphProperties.class)
public class GraphConfig {

    private static final Logger log = LoggerFactory.getLogger(GraphConfig.class);

    @Bean("neo4jDriver")
    public Driver neo4jDriver(GraphProperties props) {
        String uri = props.getNeo4jUri();
        String user = props.getNeo4jUsername();
        String credential = props.getNeo4jPassword();

        if (credential == null || credential.isBlank()) {
            log.warn("Neo4j credential non configurata, driver non disponibile");
            return null;
        }

        Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, credential));
        driver.verifyConnectivity();
        log.info("Neo4j driver connesso: {}", uri);
        return driver;
    }

    @Bean("graphExecutors")
    public Map<String, CypherExecutor> graphExecutors(GraphProperties props) {
        Map<String, CypherExecutor> executors = new LinkedHashMap<>();

        // Neo4j
        if (props.getNeo4jPassword() != null && !props.getNeo4jPassword().isBlank()) {
            try {
                Driver driver = neo4jDriver(props);
                if (driver != null) {
                    executors.put("neo4j", new Neo4jCypherExecutor(driver));
                    log.info("Backend graph registrato: neo4j ({})", props.getNeo4jUri());
                }
            } catch (Exception e) {
                log.error("Neo4j non disponibile: {}", e.getMessage());
            }
        }

        // Apache AGE
        if (props.isAgeEnabled() && props.getAgeDbUrl() != null && !props.getAgeDbUrl().isBlank()) {
            try {
                HikariDataSource ds = new HikariDataSource();
                ds.setJdbcUrl(props.getAgeDbUrl());
                ds.setUsername(props.getAgeDbUsername());
                ds.setPassword(props.getAgeDbPassword());
                ds.setMaximumPoolSize(2);
                ds.setPoolName("age-pool");

                JdbcTemplate jdbc = new JdbcTemplate(ds);
                AgeCypherExecutor ageExecutor = new AgeCypherExecutor(jdbc, props.getAgeGraphName());
                ageExecutor.initGraph();
                executors.put("age", ageExecutor);
                log.info("Backend graph registrato: age (graph={})", props.getAgeGraphName());
            } catch (Exception e) {
                log.error("Apache AGE non disponibile: {}", e.getMessage());
            }
        }

        if (executors.isEmpty()) {
            log.warn("Nessun backend graph configurato!");
        }

        return executors;
    }
}
