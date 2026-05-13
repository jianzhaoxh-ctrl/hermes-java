package com.hermes.agent.config;

import com.hermes.agent.persistence.SQLiteBackend;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 持久化层配置 — 将 SQLiteBackend 内部创建的 JdbcTemplate 暴露为 Spring Bean
 *
 * <p>SQLiteBackend 在 @PostConstruct 中自行创建 JdbcTemplate（非 Spring 管理），
 * 但记忆系统的新组件（LongTermMemoryDao 等）通过构造函数注入 JdbcTemplate。
 * 此配置类桥接两者，使 JdbcTemplate 可被 Spring 依赖注入。
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public JdbcTemplate jdbcTemplate(SQLiteBackend sqliteBackend) {
        JdbcTemplate jt = sqliteBackend.getJdbcTemplate();
        if (jt == null) {
            throw new IllegalStateException("SQLiteBackend has not been initialized — JdbcTemplate is null");
        }
        return jt;
    }
}
