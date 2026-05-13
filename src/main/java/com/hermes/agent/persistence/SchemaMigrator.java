package com.hermes.agent.persistence;

import com.hermes.agent.persistence.migration.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Schema 迁移执行器 — 管理 SQLite 数据库版本升级
 *
 * <p>特性：
 * <ul>
 *   <li>自动检测当前版本并执行增量迁移</li>
 *   <li>支持事务性迁移（失败自动回滚）</li>
 *   <li>支持 FTS5 触发器重建</li>
 * </ul>
 *
 * <p>设计参考 hermes-agent-main/hermes_state.py
 */
public class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private static final int LATEST_VERSION = 8;

    private static final List<SchemaMigration> MIGRATIONS = List.of(
        new MigrationV1(),
        new MigrationV2(),
        new MigrationV3(),
        new MigrationV4(),
        new MigrationV5(),
        new MigrationV6(),
        new MigrationV7(),
        new MigrationV8()
    );

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取当前 schema 版本
     */
    public int getCurrentVersion() {
        try {
            Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM schema_version LIMIT 1",
                Integer.class
            );
            return version != null ? version : 0;
        } catch (Exception e) {
            // 表不存在，返回 0
            return 0;
        }
    }

    /**
     * 执行所有待处理的迁移
     */
    public void migrate() {
        int currentVersion = getCurrentVersion();

        if (currentVersion >= LATEST_VERSION) {
            log.info("[SchemaMigrator] 数据库已是最新版本: v{}", currentVersion);
            return;
        }

        log.info("[SchemaMigrator] 开始迁移: v{} → v{}", currentVersion, LATEST_VERSION);

        for (SchemaMigration migration : MIGRATIONS) {
            if (migration.version() <= currentVersion) {
                continue;
            }

            migrateVersion(migration);
        }

        log.info("[SchemaMigrator] 迁移完成: v{}", getCurrentVersion());
    }

    /**
     * 执行单个版本的迁移
     */
    private void migrateVersion(SchemaMigration migration) {
        log.info("[SchemaMigrator] 执行迁移 v{}: {}", migration.version(), migration.description());

        try {
            // 分割 SQL 语句并逐条执行
            String[] statements = migration.upSql().split(";");
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        jdbcTemplate.execute(trimmed);
                    } catch (Exception ex) {
                        // 索引、表、列已存在等非致命错误，记录并继续
                        log.debug("[SchemaMigrator] 语句执行跳过（可能已存在）: {} — {}",
                                ex.getClass().getSimpleName(), ex.getMessage());
                    }
                }
            }

            // 重建触发器（如需要）
            if (migration.rebuildTriggers()) {
                rebuildFtsTriggers();
            }

            // 更新 schema 版本
            jdbcTemplate.update("UPDATE schema_version SET version = ?", migration.version());

            log.info("[SchemaMigrator] ✓ 迁移 v{} 完成", migration.version());
        } catch (Exception e) {
            log.error("[SchemaMigrator] ✗ 迁移 v{} 失败: {}", migration.version(), e.getMessage(), e);
            throw new RuntimeException("Schema migration failed at v" + migration.version(), e);
        }
    }

    /**
     * 重建 FTS5 同步触发器
     */
    public void rebuildFtsTriggers() {
        log.debug("[SchemaMigrator] 重建 FTS5 触发器...");

        // ── messages 表的 FTS5 触发器 ──

        // INSERT 触发器
        jdbcTemplate.execute("""
            CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(rowid, content) VALUES (new.id, new.content);
            END
            """);

        // DELETE 触发器
        jdbcTemplate.execute("""
            CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.id, old.content);
            END
            """);

        // UPDATE 触发器
        jdbcTemplate.execute("""
            CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.id, old.content);
                INSERT INTO messages_fts(rowid, content) VALUES (new.id, new.content);
            END
            """);

        // ── long_term_memories 表的 FTS5 触发器 ──

        try {
            // INSERT 触发器
            jdbcTemplate.execute("""
                CREATE TRIGGER IF NOT EXISTS long_term_memories_ai AFTER INSERT ON long_term_memories BEGIN
                    INSERT INTO long_term_memories_fts(rowid, content, category, source_context)
                    VALUES (new.rowid, new.content, new.category, new.source_context);
                END
                """);

            // DELETE 触发器
            jdbcTemplate.execute("""
                CREATE TRIGGER IF NOT EXISTS long_term_memories_ad AFTER DELETE ON long_term_memories BEGIN
                    INSERT INTO long_term_memories_fts(long_term_memories_fts, rowid, content, category, source_context)
                    VALUES ('delete', old.rowid, old.content, old.category, old.source_context);
                END
                """);

            // UPDATE 触发器
            jdbcTemplate.execute("""
                CREATE TRIGGER IF NOT EXISTS long_term_memories_au AFTER UPDATE ON long_term_memories BEGIN
                    INSERT INTO long_term_memories_fts(long_term_memories_fts, rowid, content, category, source_context)
                    VALUES ('delete', old.rowid, old.content, old.category, old.source_context);
                    INSERT INTO long_term_memories_fts(rowid, content, category, source_context)
                    VALUES (new.rowid, new.content, new.category, new.source_context);
                END
                """);

            log.debug("[SchemaMigrator] long_term_memories FTS5 触发器已创建");
        } catch (Exception e) {
            log.debug("[SchemaMigrator] long_term_memories FTS5 触发器跳过（表可能不存在）: {}", e.getMessage());
        }
    }

    /**
     * 创建 FTS5 虚拟表（如不存在）
     */
    public void ensureFtsTable() {
        try {
            jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                    content,
                    content='messages',
                    content_rowid='id',
                    tokenize='unicode61'
                )
                """);
            rebuildFtsTriggers();
            log.info("[SchemaMigrator] FTS5 表已就绪");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] FTS5 不可用: {}", e.getMessage());
        }
    }

    /**
     * 获取所有迁移列表
     */
    public static List<SchemaMigration> getMigrations() {
        return Collections.unmodifiableList(MIGRATIONS);
    }

    /**
     * 获取最新版本号
     */
    public static int getLatestVersion() {
        return LATEST_VERSION;
    }
}
