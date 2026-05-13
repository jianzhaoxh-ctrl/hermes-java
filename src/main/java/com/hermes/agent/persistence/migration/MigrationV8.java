package com.hermes.agent.persistence.migration;

import com.hermes.agent.persistence.SchemaMigration;

/**
 * V8: 长期记忆系统（跨时空记忆）
 *
 * <p>新增表：
 * <ul>
 *   <li>long_term_memories — 长期记忆存储</li>
 *   <li>long_term_memories_fts — FTS5 全文索引</li>
 *   <li>session_summaries — 增强版会话摘要</li>
 *   <li>user_profiles_ext — 用户画像扩展（含主题专业度、学习模式）</li>
 * </ul>
 *
 * <p>核心特性：
 * <ul>
 *   <li>importance: LLM 重要性评分（0.0 ~ 1.0）</li>
 *   <li>category: fact | preference | context | lesson | decision</li>
 *   <li>auto_forget: 基于艾宾浩斯曲线的自动遗忘</li>
 *   <li>session 链：parent_session_id 建立会话关系</li>
 *   <li>FTS5 全文检索</li>
 * </ul>
 */
public class MigrationV8 implements SchemaMigration {

    @Override
    public int version() {
        return 8;
    }

    @Override
    public String description() {
        return "长期记忆系统：long_term_memories + session_summaries + FTS5 + 遗忘机制";
    }

    @Override
    public String upSql() {
        return """
            -- 长期记忆表
            CREATE TABLE IF NOT EXISTS long_term_memories (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL DEFAULT 'global',
                category TEXT NOT NULL DEFAULT 'context',
                content TEXT NOT NULL,
                importance REAL DEFAULT 0.5,
                auto_forget INTEGER DEFAULT 0,
                embedding BLOB,
                source_session_id TEXT,
                source_context TEXT,
                created_at INTEGER NOT NULL,
                last_accessed_at INTEGER,
                access_count INTEGER DEFAULT 0,
                predicted_forget_at INTEGER,
                metadata TEXT
            );
            
            -- 长期记忆 FTS5 全文索引
            CREATE VIRTUAL TABLE IF NOT EXISTS long_term_memories_fts USING fts5(
                content,
                category,
                source_context,
                content='long_term_memories',
                content_rowid='rowid',
                tokenize='unicode61'
            );
            
            -- 增强版会话摘要表
            CREATE TABLE IF NOT EXISTS session_summaries (
                session_id TEXT PRIMARY KEY,
                parent_session_id TEXT,
                title TEXT,
                created_at INTEGER,
                last_active_at INTEGER,
                
                -- 13 字段结构化摘要
                active_task TEXT,
                goal TEXT,
                constraints TEXT,
                completed_actions TEXT,
                active_state TEXT,
                in_progress TEXT,
                blocked TEXT,
                key_decisions TEXT,
                resolved_questions TEXT,
                pending_user_asks TEXT,
                relevant_files TEXT,
                remaining_work TEXT,
                critical_context TEXT,
                
                -- 跨时空增强
                importance REAL DEFAULT 0.5,
                linked_memory_ids TEXT,
                linked_session_ids TEXT,
                extracted_from_long_term INTEGER DEFAULT 0
            );
            
            -- 用户画像扩展表
            CREATE TABLE IF NOT EXISTS user_profiles_ext (
                user_id TEXT PRIMARY KEY,
                properties TEXT,
                preferences TEXT,
                known_topics TEXT,
                persistent_memory_ids TEXT,
                topic_expertise TEXT,
                learned_patterns TEXT,
                conversation_count INTEGER DEFAULT 0,
                first_seen INTEGER,
                last_seen INTEGER
            );
            
            -- 性能索引
            CREATE INDEX IF NOT EXISTS idx_ltm_user_id ON long_term_memories(user_id);
            CREATE INDEX IF NOT EXISTS idx_ltm_category ON long_term_memories(category);
            CREATE INDEX IF NOT EXISTS idx_ltm_importance ON long_term_memories(importance DESC);
            CREATE INDEX IF NOT EXISTS idx_ltm_created_at ON long_term_memories(created_at DESC);
            CREATE INDEX IF NOT EXISTS idx_ltm_last_accessed ON long_term_memories(last_accessed_at);
            CREATE INDEX IF NOT EXISTS idx_ltm_source_session ON long_term_memories(source_session_id);
            
            -- 会话摘要索引
            CREATE INDEX IF NOT EXISTS idx_ss_parent ON session_summaries(parent_session_id);
            CREATE INDEX IF NOT EXISTS idx_ss_created ON session_summaries(created_at DESC);
            
            -- 用户画像扩展索引
            CREATE INDEX IF NOT EXISTS idx_upe_topics ON user_profiles_ext(known_topics);
            """;
    }

    @Override
    public boolean rebuildTriggers() {
        return true;  // 需要重建 FTS5 触发器
    }

    /**
     * 重建长期记忆 FTS5 触发器
     */
    public String rebuildFtsTriggersSql() {
        return """
            -- INSERT 触发器
            CREATE TRIGGER IF NOT EXISTS long_term_memories_ai AFTER INSERT ON long_term_memories BEGIN
                INSERT INTO long_term_memories_fts(rowid, content, category, source_context) 
                VALUES (new.rowid, new.content, new.category, new.source_context);
            END;
            
            -- DELETE 触发器
            CREATE TRIGGER IF NOT EXISTS long_term_memories_ad AFTER DELETE ON long_term_memories BEGIN
                INSERT INTO long_term_memories_fts(long_term_memories_fts, rowid, content, category, source_context) 
                VALUES ('delete', old.rowid, old.content, old.category, old.source_context);
            END;
            
            -- UPDATE 触发器
            CREATE TRIGGER IF NOT EXISTS long_term_memories_au AFTER UPDATE ON long_term_memories BEGIN
                INSERT INTO long_term_memories_fts(long_term_memories_fts, rowid, content, category, source_context) 
                VALUES ('delete', old.rowid, old.content, old.category, old.source_context);
                INSERT INTO long_term_memories_fts(rowid, content, category, source_context) 
                VALUES (new.rowid, new.content, new.category, new.source_context);
            END;
            """;
    }
}