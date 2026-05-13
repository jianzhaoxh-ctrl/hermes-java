package com.hermes.agent.persistence.migration;

import com.hermes.agent.persistence.SchemaMigration;

/**
 * V3: 添加 title 列到 sessions 表
 */
public class MigrationV3 implements SchemaMigration {

    @Override
    public int version() {
        return 3;
    }

    @Override
    public String description() {
        return "添加 sessions.title 列";
    }

    @Override
    public String upSql() {
        return """
            ALTER TABLE sessions ADD COLUMN title TEXT;
            UPDATE schema_version SET version = 3;
            """;
    }
}
