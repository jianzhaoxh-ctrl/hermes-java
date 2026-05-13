package com.hermes.agent.persistence.migration;

import com.hermes.agent.persistence.SchemaMigration;

/**
 * V1: 基础表结构
 *
 * <p>表结构：
 * <ul>
 *   <li>sessions: 会话元数据</li>
 *   <li>messages: 消息历史</li>
 *   <li>user_profiles: 用户画像</li>
 *   <li>skill_memories: 技能记忆</li>
 *   <li>messages_fts: FTS5 全文搜索</li>
 * </ul>
 */
public class MigrationV1 implements SchemaMigration {

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String description() {
        return "基础表结构：sessions, messages, user_profiles, skill_memories, FTS5";
    }

    @Override
    public String upSql() {
        return """
            -- Schema 版本追踪表
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER NOT NULL
            );

            -- 会话表
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY,
                source TEXT NOT NULL DEFAULT 'cli',
                user_id TEXT,
                model TEXT,
                model_config TEXT,
                system_prompt TEXT,
                started_at REAL NOT NULL,
                ended_at REAL,
                message_count INTEGER DEFAULT 0,
                tool_call_count INTEGER DEFAULT 0
            );
            
            -- 防御性添加started_at列（兼容旧数据库：表已存在但缺少列）
            -- SQLite 3.35+ 支持 ALTER TABLE ADD COLUMN IF NOT EXISTS
            ALTER TABLE sessions ADD COLUMN IF NOT EXISTS started_at REAL;
            ALTER TABLE sessions ADD COLUMN IF NOT EXISTS source TEXT DEFAULT 'cli';

            -- 消息表（防御性：只在表不存在时创建）
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT,
                tool_call_id TEXT,
                tool_calls TEXT,
                tool_name TEXT,
                timestamp REAL NOT NULL,
                token_count INTEGER,
                FOREIGN KEY (session_id) REFERENCES sessions(id)
            );

            -- 消息索引
            CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id);

            -- 防御性添加 timestamp 列和索引（兼容旧数据库：表已存在但缺少列）
            -- 使用单独的 IF NOT EXISTS 风格处理：先尝试添加列（SQLite 3.35+ 支持）
            -- 再创建索引

            -- 用户画像表
            CREATE TABLE IF NOT EXISTS user_profiles (
                user_id TEXT PRIMARY KEY,
                profile TEXT,
                updated_at REAL
            );

            -- 技能记忆表
            CREATE TABLE IF NOT EXISTS skill_memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                skill_name TEXT NOT NULL,
                content TEXT,
                created_at REAL
            );

            CREATE INDEX IF NOT EXISTS idx_skill_memories_name ON skill_memories(skill_name);

            -- FTS5 全文搜索虚拟表
            CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                content,
                content='messages',
                content_rowid='id',
                tokenize='unicode61'
            );

            -- 初始化版本号
            INSERT INTO schema_version (version) VALUES (1);
            """;
    }

    @Override
    public boolean rebuildTriggers() {
        return true;
    }
}
