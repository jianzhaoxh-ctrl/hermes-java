package com.hermes.agent.persistence.migration;

import com.hermes.agent.persistence.SchemaMigration;

/**
 * V2: 添加 finish_reason 列到 messages 表
 */
public class MigrationV2 implements SchemaMigration {

    @Override
    public int version() {
        return 2;
    }

    @Override
    public String description() {
        return "添加 messages.finish_reason 列";
    }

    @Override
    public String upSql() {
        return """
            ALTER TABLE messages ADD COLUMN finish_reason TEXT;
            UPDATE schema_version SET version = 2;
            """;
    }

    @Override
    public String downSql() {
        // SQLite 不支持 DROP COLUMN，需要重建表
        return null;
    }
}
