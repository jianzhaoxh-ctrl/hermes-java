package com.hermes.agent.persistence;

/**
 * Schema 迁移接口 — 支持 SQLite 数据库版本升级
 *
 * <p>每个迁移实现：
 * <ul>
 *   <li>定义目标版本号</li>
 *   <li>提供迁移 SQL 脚本</li>
 *   <li>可选提供回滚脚本</li>
 * </ul>
 *
 * <p>设计参考 hermes-agent-main/hermes_state.py
 */
public interface SchemaMigration {

    /**
     * 目标版本号（从 1 开始）
     */
    int version();

    /**
     * 迁移描述
     */
    String description();

    /**
     * 升级 SQL 脚本
     */
    String upSql();

    /**
     * 回滚 SQL 脚本（可选）
     */
    default String downSql() {
        return null;
    }

    /**
     * 是否需要重建索引/触发器
     */
    default boolean rebuildTriggers() {
        return false;
    }
}
