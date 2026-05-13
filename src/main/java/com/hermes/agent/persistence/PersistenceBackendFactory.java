package com.hermes.agent.persistence;

import com.hermes.agent.config.AgentConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持久化后端工厂 — 根据 config.yaml 选择合适的存储后端。
 *
 * <p>支持的后端：
 * <ul>
 *   <li>sqlite — SQLite + FTS5（推荐）</li>
 *   <li>jsonfile — JSON 文件（兼容旧版）</li>
 *   <li>redis — Redis（分布式场景）</li>
 * </ul>
 *
 * <p>配置示例 (config.yaml)：
 * <pre>
 * memory:
 *   backend: sqlite
 *   db_path: ~/.hermes/hermes.db
 * </pre>
 */
@Component
public class PersistenceBackendFactory {

    private static final Logger log = LoggerFactory.getLogger(PersistenceBackendFactory.class);

    private final AgentConfig config;
    private final Map<String, PersistenceBackend> backends = new ConcurrentHashMap<>();
    private PersistenceBackend defaultBackend;

    public PersistenceBackendFactory(AgentConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        // 注册可用后端
        registerBackend(new JsonFileBackend(config.resolveDataDir()));

        // SQLite 后端（如果可用）
        try {
            SQLiteBackend sqliteBackend = new SQLiteBackend(
                config.getSqlitePath(),
                config.getSqlitePoolSize()
            );
            sqliteBackend.initialize();
            registerBackend(sqliteBackend);
        } catch (Exception e) {
            log.warn("[PersistenceFactory] SQLite 后端不可用: {}", e.getMessage());
        }

        // 选择默认后端
        String backendName = config.getMemoryBackend();
        this.defaultBackend = getBackend(backendName)
            .or(() -> getBackend("jsonfile"))
            .orElseThrow(() -> new RuntimeException("No available persistence backend"));

        log.info("[PersistenceFactory] 默认后端: {}", defaultBackend.name());
    }

    /**
     * 注册持久化后端
     */
    public void registerBackend(PersistenceBackend backend) {
        if (backend.isAvailable()) {
            backends.put(backend.name(), backend);
            log.info("[PersistenceFactory] 注册后端: {} ({})", backend.name(), backend.getClass().getSimpleName());
        } else {
            log.warn("[PersistenceFactory] 后端不可用，跳过注册: {}", backend.name());
        }
    }

    /**
     * 获取指定名称的后端
     */
    public Optional<PersistenceBackend> getBackend(String name) {
        return Optional.ofNullable(backends.get(name));
    }

    /**
     * 获取默认后端
     */
    public PersistenceBackend getDefaultBackend() {
        return defaultBackend;
    }

    /**
     * 获取所有可用后端
     */
    public Map<String, PersistenceBackend> getAvailableBackends() {
        return new ConcurrentHashMap<>(backends);
    }

    /**
     * 数据迁移：从 JSON 迁移到 SQLite
     */
    public void migrateToJsonFileToSQLite() {
        Optional<PersistenceBackend> jsonOpt = getBackend("jsonfile");
        Optional<PersistenceBackend> sqliteOpt = getBackend("sqlite");

        if (jsonOpt.isEmpty() || sqliteOpt.isEmpty()) {
            log.warn("[PersistenceFactory] 无法执行迁移：缺少必要的后端");
            return;
        }

        JsonFileBackend jsonBackend = (JsonFileBackend) jsonOpt.get();
        SQLiteBackend sqliteBackend = (SQLiteBackend) sqliteOpt.get();

        log.info("[PersistenceFactory] 开始从 JSON 迁移到 SQLite...");

        sqliteBackend.migrateFromJson(
            jsonBackend.getSessionMessages(),
            jsonBackend.getUserProfiles(),
            jsonBackend.getSkillMemories()
        );

        log.info("[PersistenceFactory] 迁移完成，切换默认后端到 SQLite");
        this.defaultBackend = sqliteBackend;
    }
}
