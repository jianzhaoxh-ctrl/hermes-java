package com.hermes.agent.persistence.migration;

import com.hermes.agent.persistence.SchemaMigration;

/**
 * V5: 计费字段 + 压缩链支持
 *
 * <p>新增 sessions 表字段：
 * <ul>
 *   <li>Token 计数：input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens</li>
 *   <li>计费：billing_provider, billing_base_url, billing_mode</li>
 *   <li>成本：estimated_cost_usd, actual_cost_usd, cost_status, cost_source, pricing_version</li>
 *   <li>压缩链：parent_session_id, end_reason</li>
 * </ul>
 */
public class MigrationV5 implements SchemaMigration {

    @Override
    public int version() {
        return 5;
    }

    @Override
    public String description() {
        return "添加计费字段 + 压缩链支持（11 个字段）";
    }

    @Override
    public String upSql() {
        return """
            -- Token 计数
            ALTER TABLE sessions ADD COLUMN input_tokens INTEGER DEFAULT 0;
            ALTER TABLE sessions ADD COLUMN output_tokens INTEGER DEFAULT 0;
            ALTER TABLE sessions ADD COLUMN cache_read_tokens INTEGER DEFAULT 0;
            ALTER TABLE sessions ADD COLUMN cache_write_tokens INTEGER DEFAULT 0;
            ALTER TABLE sessions ADD COLUMN reasoning_tokens INTEGER DEFAULT 0;

            -- 计费信息
            ALTER TABLE sessions ADD COLUMN billing_provider TEXT;
            ALTER TABLE sessions ADD COLUMN billing_base_url TEXT;
            ALTER TABLE sessions ADD COLUMN billing_mode TEXT;

            -- 成本追踪
            ALTER TABLE sessions ADD COLUMN estimated_cost_usd REAL;
            ALTER TABLE sessions ADD COLUMN actual_cost_usd REAL;
            ALTER TABLE sessions ADD COLUMN cost_status TEXT;
            ALTER TABLE sessions ADD COLUMN cost_source TEXT;
            ALTER TABLE sessions ADD COLUMN pricing_version TEXT;

            -- 压缩链支持
            ALTER TABLE sessions ADD COLUMN parent_session_id TEXT REFERENCES sessions(id);
            ALTER TABLE sessions ADD COLUMN end_reason TEXT;

            -- 压缩链索引
            CREATE INDEX IF NOT EXISTS idx_sessions_parent ON sessions(parent_session_id);

            UPDATE schema_version SET version = 5;
            """;
    }
}
