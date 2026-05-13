package com.hermes.agent.persistence.migration;

import com.hermes.agent.persistence.SchemaMigration;

/**
 * V4: 标题唯一索引（仅非 NULL 值）
 */
public class MigrationV4 implements SchemaMigration {

    @Override
    public int version() {
        return 4;
    }

    @Override
    public String description() {
        return "创建 sessions.title 唯一索引（WHERE title IS NOT NULL）";
    }

    @Override
    public String upSql() {
        return """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_title_unique
            ON sessions(title) WHERE title IS NOT NULL;
            UPDATE schema_version SET version = 4;
            """;
    }

    @Override
    public String downSql() {
        return """
            DROP INDEX IF EXISTS idx_sessions_title_unique;
            UPDATE schema_version SET version = 3;
            """;
    }
}
