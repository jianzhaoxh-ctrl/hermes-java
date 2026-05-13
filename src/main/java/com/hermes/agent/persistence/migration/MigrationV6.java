package com.hermes.agent.persistence.migration;

import com.hermes.agent.persistence.SchemaMigration;

/**
 * V6: 推理链字段（支持多轮推理连贯性）
 *
 * <p>新增 messages 表字段：
 * <ul>
 *   <li>reasoning: 推理文本</li>
 *   <li>reasoning_details: 推理详情（JSON）</li>
 *   <li>codex_reasoning_items: Codex 推理项（JSON）</li>
 * </ul>
 */
public class MigrationV6 implements SchemaMigration {

    @Override
    public int version() {
        return 6;
    }

    @Override
    public String description() {
        return "添加推理链字段到 messages 表";
    }

    @Override
    public String upSql() {
        return """
            ALTER TABLE messages ADD COLUMN reasoning TEXT;
            ALTER TABLE messages ADD COLUMN reasoning_details TEXT;
            ALTER TABLE messages ADD COLUMN codex_reasoning_items TEXT;

            UPDATE schema_version SET version = 6;
            """;
    }
}
