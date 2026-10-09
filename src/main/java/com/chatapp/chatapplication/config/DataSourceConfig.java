package com.chatapp.chatapplication.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Universal dynamic DataSource configuration.
 * Automatically parses cloud database URLs (Render, Heroku, Supabase, Neon) from
 * DATABASE_URL or DB_URL (supporting both postgres:// and jdbc:postgresql:// formats),
 * with a seamless zero-config fallback to embedded H2 so cloud containers always boot cleanly.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    @Primary
    public DataSource dataSource(Environment env) {
        String rawDbUrl = env.getProperty("DATABASE_URL");
        if (rawDbUrl == null || rawDbUrl.trim().isEmpty()) {
            rawDbUrl = env.getProperty("DB_URL");
        }
        if (rawDbUrl == null || rawDbUrl.trim().isEmpty()) {
            rawDbUrl = env.getProperty("spring.datasource.url");
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("PulseChatHikariPool");

        if (rawDbUrl != null && !rawDbUrl.trim().isEmpty() && !rawDbUrl.startsWith("jdbc:h2:")) {
            try {
                if (rawDbUrl.startsWith("postgres://") || rawDbUrl.startsWith("postgresql://")) {
                    URI uri = new URI(rawDbUrl);
                    String host = uri.getHost();
                    int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                    String path = uri.getPath();
                    String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path;

                    config.setJdbcUrl(jdbcUrl);
                    config.setDriverClassName("org.postgresql.Driver");

                    String userInfo = uri.getUserInfo();
                    if (userInfo != null && userInfo.contains(":")) {
                        String[] parts = userInfo.split(":", 2);
                        config.setUsername(parts[0]);
                        config.setPassword(parts[1]);
                    }
                    log.info("Configured PostgreSQL connection to {}:{}", host, port);
                } else {
                    config.setJdbcUrl(rawDbUrl);
                    String username = env.getProperty("DB_USERNAME", env.getProperty("spring.datasource.username", "postgres"));
                    String password = env.getProperty("DB_PASSWORD", env.getProperty("spring.datasource.password", ""));
                    String driver = env.getProperty("DB_DRIVER", env.getProperty("spring.datasource.driver-class-name", "org.postgresql.Driver"));

                    config.setUsername(username);
                    config.setPassword(password);
                    config.setDriverClassName(driver);
                    log.info("Configured JDBC connection to {}", rawDbUrl);
                }

                config.setMaximumPoolSize(10);
                config.setMinimumIdle(2);
                config.setConnectionTimeout(30000);
                config.setIdleTimeout(300000);
                return new HikariDataSource(config);
            } catch (Exception e) {
                log.warn("Failed to parse external database URL, falling back to embedded database: {}", e.getMessage());
            }
        }

        // Seamless zero-config fallback: embedded in-memory H2 database
        // Guaranteed to start on all container platforms (Docker, Render, local) without file locking issues
        log.info("Initializing embedded H2 database for PulseChat");
        config.setJdbcUrl("jdbc:h2:mem:pulsechat;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        config.setDriverClassName("org.h2.Driver");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        return new HikariDataSource(config);
    }
}
