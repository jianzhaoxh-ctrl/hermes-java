package com.hermes.agent.persistence.migration;

import com.hermes.agent.persistence.SchemaMigration;

/**
 * V7: 性能索引补齐
 *
 * <p>参考 Python 版 hermes_state.py _init_schema() 中的索引定义，
 * 补齐 Java 版缺失的性能索引。
 *
 * <p>索引列表：
 * <ul>
 *   <li>idx_sessions_source — 按来源过滤会话</li>
 *   <li>idx_sessions_parent — 压缩链查询</li>
 *   <li>idx_sessions_started — 按时间排序会话</li>
 *   <li>idx_messages_session_ts — 消息按会话+时间复合查询</li>
 * </ul>
 *
 * <p>注意：这些索引之前在 ensurePerformanceIndexes() 中通过临时 SQL 创建，
 * 现在改为通过正式迁移流程管理，确保：
 * <ul>
 *   <li>旧数据库升级时索引正确创建</li>
 *   <li>新数据库安装时索引自动包含</li>
 *   <li>索引创建有版本追踪</li>
 * </ul>
 */
public class MigrationV7 implements SchemaMigration {

    @Override
    public int version() {
        return 7;
    }

    @Override
    public String description() {
        return "性能索引补齐：sessions(source/parent/started), messages(session_id+timestamp)";
    }

    @Override
    public String upSql() {
        return """
            CREATE INDEX IF NOT EXISTS idx_sessions_source ON sessions(source);
            CREATE INDEX IF NOT EXISTS idx_sessions_parent ON sessions(parent_session_id);
            CREATE INDEX IF NOT EXISTS idx_sessions_started ON sessions(started_at DESC);
            CREATE INDEX IF NOT EXISTS idx_messages_session_ts ON messages(session_id, timestamp);
            """;
    }

    @Override
    public boolean rebuildTriggers() {
        return false;
    }
}
