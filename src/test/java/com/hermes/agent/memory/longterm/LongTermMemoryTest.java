package com.hermes.agent.memory.longterm;

import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 长期记忆系统单元测试
 *
 * <p>覆盖组件：
 * <ul>
 *   <li>LongTermMemory — 实体创建、序列化</li>
 *   <li>LongTermMemoryDao — CRUD、FTS5 搜索、ByteBuffer 向量</li>
 *   <li>EnhancedSessionSummary — 结构化摘要</li>
 *   <li>Schema Migration V8 — 表和索引验证</li>
 * </ul>
 */
class LongTermMemoryTest {

    private static final String TEST_DB_PATH = System.getProperty("java.io.tmpdir") + "/hermes_ltm_test.db";
    private com.hermes.agent.persistence.SQLiteBackend backend;
    private LongTermMemoryDao dao;
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() throws IOException {
        Path dbPath = Paths.get(TEST_DB_PATH);
        Files.deleteIfExists(dbPath);
        Files.deleteIfExists(Paths.get(TEST_DB_PATH + "-wal"));
        Files.deleteIfExists(Paths.get(TEST_DB_PATH + "-shm"));

        backend = new com.hermes.agent.persistence.SQLiteBackend(TEST_DB_PATH, 2);
        backend.initialize();
        jdbcTemplate = backend.getJdbcTemplate();

        com.hermes.agent.persistence.SchemaMigrator migrator =
                new com.hermes.agent.persistence.SchemaMigrator(jdbcTemplate);
        migrator.migrate();

        dao = new LongTermMemoryDao(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.shutdown();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LongTermMemory 实体测试
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LongTermMemory 实体")
    class EntityTests {

        @Test
        @DisplayName("create() 生成完整实体")
        void testCreate() {
            LongTermMemory mem = LongTermMemory.create(
                    "user1",
                    LongTermMemory.Category.FACT,
                    "用户偏好深色模式",
                    0.8,
                    "session-001",
                    "用户设置偏好"
            );

            assertNotNull(mem.getId());
            assertTrue(mem.getId().startsWith("mem_"));
            assertEquals("user1", mem.getUserId());
            assertEquals(LongTermMemory.Category.FACT, mem.getCategory());
            assertEquals("用户偏好深色模式", mem.getContent());
            assertEquals(0.8, mem.getImportance(), 0.001);
            assertEquals("session-001", mem.getSourceSessionId());
            assertEquals("用户设置偏好", mem.getSourceContext());
            assertNotNull(mem.getCreatedAt());
            assertEquals(0, mem.getAccessCount());
            assertFalse(mem.isAutoForget());
        }

        @Test
        @DisplayName("Category.fromValue 正确映射")
        void testCategoryFromValue() {
            assertEquals(LongTermMemory.Category.FACT, LongTermMemory.Category.fromValue("fact"));
            assertEquals(LongTermMemory.Category.PREFERENCE, LongTermMemory.Category.fromValue("preference"));
            assertEquals(LongTermMemory.Category.LESSON, LongTermMemory.Category.fromValue("lesson"));
            assertEquals(LongTermMemory.Category.DECISION, LongTermMemory.Category.fromValue("decision"));
            assertEquals(LongTermMemory.Category.CONTEXT, LongTermMemory.Category.fromValue("context"));
            assertEquals(LongTermMemory.Category.CONTEXT, LongTermMemory.Category.fromValue("unknown"));
        }

        @Test
        @DisplayName("toMap / fromMap 序列化往返")
        void testSerializationRoundTrip() {
            LongTermMemory original = LongTermMemory.create(
                    "user1", LongTermMemory.Category.DECISION,
                    "使用 React 框架", 0.9, "s1", "技术选型讨论"
            );
            original.setAccessCount(5);
            original.setAutoForget(true);

            Map<String, Object> map = original.toMap();
            LongTermMemory restored = LongTermMemory.fromMap(map);

            assertEquals(original.getId(), restored.getId());
            assertEquals(original.getUserId(), restored.getUserId());
            assertEquals(original.getCategory(), restored.getCategory());
            assertEquals(original.getContent(), restored.getContent());
            assertEquals(original.getImportance(), restored.getImportance(), 0.001);
            assertEquals(original.getAccessCount(), restored.getAccessCount());
            assertEquals(original.isAutoForget(), restored.isAutoForget());
        }

        @Test
        @DisplayName("markAccessed() 更新访问计数和时间")
        void testMarkAccessed() throws InterruptedException {
            LongTermMemory mem = LongTermMemory.create("u1", LongTermMemory.Category.FACT, "test", 0.5, null, null);
            long originalTime = mem.getLastAccessedAt();
            int originalCount = mem.getAccessCount();

            Thread.sleep(10);
            mem.markAccessed();

            assertEquals(originalCount + 1, mem.getAccessCount());
            assertTrue(mem.getLastAccessedAt() >= originalTime);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LongTermMemoryDao 测试
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LongTermMemoryDao CRUD")
    class DaoTests {

        @Test
        @DisplayName("insert + findById 完整往返")
        void testInsertAndFind() {
            LongTermMemory mem = LongTermMemory.create(
                    "user1", LongTermMemory.Category.FACT,
                    "Spring Boot 3.2 需要 Java 17+", 0.7, "s1", "依赖检查"
            );
            dao.insert(mem);

            Optional<LongTermMemory> found = dao.findById(mem.getId());
            assertTrue(found.isPresent());
            assertEquals(mem.getContent(), found.get().getContent());
            assertEquals(mem.getCategory(), found.get().getCategory());
            assertEquals(mem.getImportance(), found.get().getImportance(), 0.001);
        }

        @Test
        @DisplayName("insert 带向量，findById 正确恢复")
        void testInsertWithEmbedding() {
            LongTermMemory mem = LongTermMemory.create(
                    "user1", LongTermMemory.Category.CONTEXT,
                    "向量测试", 0.5, null, null
            );
            float[] embedding = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
            mem.setEmbedding(embedding);
            dao.insert(mem);

            Optional<LongTermMemory> found = dao.findById(mem.getId());
            assertTrue(found.isPresent());
            assertNotNull(found.get().getEmbedding());
            assertArrayEquals(embedding, found.get().getEmbedding(), 0.001f);
        }

        @Test
        @DisplayName("findByUserId 返回用户的所有记忆")
        void testFindByUserId() {
            dao.insert(LongTermMemory.create("user1", LongTermMemory.Category.FACT, "fact1", 0.5, null, null));
            dao.insert(LongTermMemory.create("user1", LongTermMemory.Category.PREFERENCE, "pref1", 0.7, null, null));
            dao.insert(LongTermMemory.create("user2", LongTermMemory.Category.FACT, "fact2", 0.6, null, null));

            List<LongTermMemory> user1Mems = dao.findByUserId("user1");
            assertEquals(2, user1Mems.size());

            List<LongTermMemory> user2Mems = dao.findByUserId("user2");
            assertEquals(1, user2Mems.size());
        }

        @Test
        @DisplayName("findByCategory 过滤正确")
        void testFindByCategory() {
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT, "f1", 0.5, null, null));
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.PREFERENCE, "p1", 0.5, null, null));
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT, "f2", 0.5, null, null));

            List<LongTermMemory> facts = dao.findByCategory("u1", LongTermMemory.Category.FACT, 10);
            assertEquals(2, facts.size());

            List<LongTermMemory> prefs = dao.findByCategory("u1", LongTermMemory.Category.PREFERENCE, 10);
            assertEquals(1, prefs.size());
        }

        @Test
        @DisplayName("findByImportance 阈值过滤")
        void testFindByImportance() {
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT, "low", 0.3, null, null));
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT, "high", 0.9, null, null));
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT, "mid", 0.6, null, null));

            List<LongTermMemory> important = dao.findByImportance("u1", 0.7, 10);
            assertEquals(1, important.size());
            assertEquals("high", important.get(0).getContent());
        }

        @Test
        @DisplayName("updateImportance 更新重要性")
        void testUpdateImportance() {
            LongTermMemory mem = LongTermMemory.create("u1", LongTermMemory.Category.FACT, "original", 0.5, null, null);
            dao.insert(mem);

            dao.updateImportance(mem.getId(), 0.8, false);

            Optional<LongTermMemory> found = dao.findById(mem.getId());
            assertTrue(found.isPresent());
            assertEquals(0.8, found.get().getImportance(), 0.001);
        }

        @Test
        @DisplayName("deleteById 删除记忆")
        void testDeleteById() {
            LongTermMemory mem = LongTermMemory.create("u1", LongTermMemory.Category.FACT, "to-delete", 0.5, null, null);
            dao.insert(mem);
            assertTrue(dao.findById(mem.getId()).isPresent());

            dao.deleteById(mem.getId());
            assertFalse(dao.findById(mem.getId()).isPresent());
        }

        @Test
        @DisplayName("searchFts 全文搜索")
        void testFtsSearch() {
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT,
                    "Spring Boot 是一个 Java 微服务框架", 0.5, null, null));
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT,
                    "React 是一个前端 UI 框架", 0.5, null, null));
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.LESSON,
                    "使用 Docker 部署 Spring Boot 应用", 0.6, null, null));

            List<LongTermMemory> springResults = dao.searchFts("Spring", "u1", 10);
            assertEquals(2, springResults.size());

            List<LongTermMemory> reactResults = dao.searchFts("React", "u1", 10);
            assertEquals(1, reactResults.size());
        }

        @Test
        @DisplayName("findBySessionId 通过来源会话查找")
        void testFindBySessionId() {
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT, "f1", 0.5, "session-A", null));
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT, "f2", 0.5, "session-A", null));
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT, "f3", 0.5, "session-B", null));

            List<LongTermMemory> sessionA = dao.findBySessionId("session-A");
            assertEquals(2, sessionA.size());
        }

        @Test
        @DisplayName("countByUserId 返回正确计数")
        void testCountByUserId() {
            assertEquals(0, dao.countByUserId("u1"));

            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT, "f1", 0.5, null, null));
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT, "f2", 0.5, null, null));

            assertEquals(2, dao.countByUserId("u1"));
        }

        @Test
        @DisplayName("ByteBuffer 向量序列化往返（大向量 256 维）")
        void testLargeEmbeddingRoundTrip() {
            float[] largeEmbedding = new float[256];
            Random rng = new Random(42);
            for (int i = 0; i < largeEmbedding.length; i++) {
                largeEmbedding[i] = rng.nextFloat();
            }

            LongTermMemory mem = LongTermMemory.create("u1", LongTermMemory.Category.CONTEXT,
                    "大向量测试", 0.5, null, null);
            mem.setEmbedding(largeEmbedding);
            dao.insert(mem);

            Optional<LongTermMemory> found = dao.findById(mem.getId());
            assertTrue(found.isPresent());
            assertNotNull(found.get().getEmbedding());
            assertEquals(256, found.get().getEmbedding().length);
            assertArrayEquals(largeEmbedding, found.get().getEmbedding(), 0.001f);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // EnhancedSessionSummary 测试
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EnhancedSessionSummary 结构化摘要")
    class SummaryTests {

        @Test
        @DisplayName("构造函数创建摘要")
        void testCreateSummary() {
            EnhancedSessionSummary summary = new EnhancedSessionSummary("session-001");
            summary.setTitle("技术选型讨论");
            assertEquals("session-001", summary.getSessionId());
            assertEquals("技术选型讨论", summary.getTitle());
            assertNotNull(summary.getCreatedAt());
            assertNotNull(summary.getLastActiveAt());
            assertEquals(0.5, summary.getImportance(), 0.001);
        }

        @Test
        @DisplayName("13 字段完整设置和获取")
        void testAll13Fields() {
            EnhancedSessionSummary summary = new EnhancedSessionSummary();
            summary.setSessionId("s1");
            summary.setActiveTask("实现记忆系统");
            summary.setGoal("构建三层记忆架构");
            summary.setConstraints("Java 21, Spring Boot 3.2");
            summary.setCompletedActions("Phase 1-3 完成");
            summary.setActiveState("编码中");
            summary.setInProgress("Phase 4 遗忘机制");
            summary.setBlocked("等待 LLM 服务");
            summary.setKeyDecisions("使用 SQLite + FTS5");
            summary.setResolvedQuestions("向量搜索方案已确定");
            summary.setPendingUserAsks("是否需要 Redis 支持");
            summary.setRelevantFiles("LongTermMemory.java, Agent.java");
            summary.setRemainingWork("Phase 5 画像融合");

            assertEquals("实现记忆系统", summary.getActiveTask());
            assertEquals("构建三层记忆架构", summary.getGoal());
            assertEquals("Java 21, Spring Boot 3.2", summary.getConstraints());
            assertEquals("Phase 1-3 完成", summary.getCompletedActions());
            assertEquals("编码中", summary.getActiveState());
            assertEquals("Phase 4 遗忘机制", summary.getInProgress());
            assertEquals("等待 LLM 服务", summary.getBlocked());
            assertEquals("使用 SQLite + FTS5", summary.getKeyDecisions());
            assertEquals("向量搜索方案已确定", summary.getResolvedQuestions());
            assertEquals("是否需要 Redis 支持", summary.getPendingUserAsks());
            assertEquals("LongTermMemory.java, Agent.java", summary.getRelevantFiles());
            assertEquals("Phase 5 画像融合", summary.getRemainingWork());
        }

        @Test
        @DisplayName("会话链 parentSessionId")
        void testSessionChain() {
            EnhancedSessionSummary parent = new EnhancedSessionSummary("s1");
            parent.setTitle("初始会话");
            EnhancedSessionSummary child = new EnhancedSessionSummary("s2");
            child.setTitle("续接会话");
            child.setParentSessionId("s1");

            assertEquals("s1", child.getParentSessionId());
        }

        @Test
        @DisplayName("关联记忆和会话 ID（List<String> 类型）")
        void testLinkedIds() {
            EnhancedSessionSummary summary = new EnhancedSessionSummary();
            summary.setSessionId("s1");
            summary.setLinkedMemoryIds(Arrays.asList("mem_001", "mem_002"));
            summary.setLinkedSessionIds(Arrays.asList("s_prev1", "s_prev2"));

            assertTrue(summary.getLinkedMemoryIds().contains("mem_001"));
            assertTrue(summary.getLinkedSessionIds().contains("s_prev1"));
        }

        @Test
        @DisplayName("addLinkedMemoryId 添加关联")
        void testAddLinkedMemoryId() {
            EnhancedSessionSummary summary = new EnhancedSessionSummary("s1");
            summary.addLinkedMemoryId("mem_001");
            summary.addLinkedMemoryId("mem_002");
            // 重复添加不应重复
            summary.addLinkedMemoryId("mem_001");

            assertEquals(2, summary.getLinkedMemoryIds().size());
        }

        @Test
        @DisplayName("fromText 解析纯文本摘要")
        void testFromText() {
            EnhancedSessionSummary summary = EnhancedSessionSummary.fromText("s1", "这是一个关于技术选型的讨论");
            assertEquals("s1", summary.getSessionId());
        }

        @Test
        @DisplayName("toMap / fromMap 序列化往返")
        void testMapRoundTrip() {
            EnhancedSessionSummary original = new EnhancedSessionSummary("s1");
            original.setTitle("技术讨论");
            original.setActiveTask("设计数据库");
            original.setGoal("实现持久化");
            original.setImportance(0.8);
            original.setLinkedMemoryIds(Arrays.asList("mem_1", "mem_2"));

            Map<String, Object> map = original.toMap();
            EnhancedSessionSummary restored = EnhancedSessionSummary.fromMap(map);

            assertEquals("s1", restored.getSessionId());
            assertEquals("技术讨论", restored.getTitle());
            assertEquals("设计数据库", restored.getActiveTask());
            assertEquals(0.8, restored.getImportance(), 0.001);
            assertEquals(2, restored.getLinkedMemoryIds().size());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 跨会话搜索集成测试
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("跨会话搜索集成")
    class CrossSessionSearchTests {

        @Test
        @DisplayName("长期记忆 + FTS5 搜索协同工作")
        void testMemoryAndFtsCoexist() {
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.FACT,
                    "项目使用 Spring Boot 3.2 框架", 0.8, null, null));
            dao.insert(LongTermMemory.create("u1", LongTermMemory.Category.DECISION,
                    "选择 SQLite 作为嵌入数据库", 0.9, null, null));

            // FTS 搜索长期记忆
            List<LongTermMemory> results = dao.searchFts("Spring", "u1", 10);
            assertEquals(1, results.size());
            assertEquals("项目使用 Spring Boot 3.2 框架", results.get(0).getContent());

            // 重要性过滤
            List<LongTermMemory> important = dao.findByImportance("u1", 0.85, 10);
            assertEquals(1, important.size());
            assertEquals("选择 SQLite 作为嵌入数据库", important.get(0).getContent());
        }

        @Test
        @DisplayName("FTS5 跨会话消息搜索")
        void testCrossSessionMessageSearch() {
            // 先创建 sessions（外键约束：需要 started_at NOT NULL）
            double now = (double) System.currentTimeMillis();
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO sessions (id, started_at) VALUES (?, ?)", "session-A", now);
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO sessions (id, started_at) VALUES (?, ?)", "session-B", now);
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO sessions (id, started_at) VALUES (?, ?)", "session-C", now);

            jdbcTemplate.update(
                    "INSERT INTO messages (session_id, role, content, timestamp) VALUES (?, ?, ?, ?)",
                    "session-A", "user", "我需要一个 Spring Boot 项目模板", now);
            jdbcTemplate.update(
                    "INSERT INTO messages (session_id, role, content, timestamp) VALUES (?, ?, ?, ?)",
                    "session-B", "user", "如何配置 Spring Security", now);
            jdbcTemplate.update(
                    "INSERT INTO messages (session_id, role, content, timestamp) VALUES (?, ?, ?, ?)",
                    "session-C", "user", "Docker 部署最佳实践", now);

            // 直接 FTS5 搜索验证索引工作
            List<String> results = jdbcTemplate.queryForList(
                    "SELECT m.content FROM messages_fts f JOIN messages m ON f.rowid = m.id " +
                    "WHERE messages_fts MATCH ? ORDER BY rank",
                    String.class, "Spring");
            assertEquals(2, results.size());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 遗忘机制测试
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("遗忘机制")
    class ForgettingTests {

        @Test
        @DisplayName("自动遗忘标记和查询")
        void testAutoForgetFlag() {
            LongTermMemory mem = LongTermMemory.create("u1", LongTermMemory.Category.FACT,
                    "临时笔记", 0.3, null, null);
            mem.setAutoForget(true);
            dao.insert(mem);

            Optional<LongTermMemory> found = dao.findById(mem.getId());
            assertTrue(found.isPresent());
            assertTrue(found.get().isAutoForget());
        }

        @Test
        @DisplayName("低重要性记忆不重要的判断")
        void testLowImportanceMemory() {
            LongTermMemory low = LongTermMemory.create("u1", LongTermMemory.Category.FACT,
                    "不重要", 0.1, null, null);
            LongTermMemory high = LongTermMemory.create("u1", LongTermMemory.Category.DECISION,
                    "关键决策", 0.95, null, null);

            dao.insert(low);
            dao.insert(high);

            List<LongTermMemory> important = dao.findByImportance("u1", 0.5, 10);
            assertEquals(1, important.size());
            assertEquals("关键决策", important.get(0).getContent());
        }

        @Test
        @DisplayName("getForgetPriority 遗忘优先级逻辑")
        void testForgetPriority() {
            // 高重要性 + 高访问 → 低遗忘优先级
            LongTermMemory important = LongTermMemory.create("u1", LongTermMemory.Category.DECISION,
                    "关键决策", 0.95, null, null);
            for (int i = 0; i < 10; i++) important.markAccessed();
            double importantScore = important.getForgetPriority();
            assertTrue(importantScore < 0.5, "高重要性高访问记忆应有低遗忘优先级: " + importantScore);

            // 低重要性 + 低访问 → 高遗忘优先级
            LongTermMemory trivial = LongTermMemory.create("u1", LongTermMemory.Category.FACT,
                    "临时笔记", 0.1, null, null);
            double trivialScore = trivial.getForgetPriority();
            assertTrue(trivialScore > 0.5, "低重要性记忆应有较高遗忘优先级: " + trivialScore);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Migration V8 验证
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Schema Migration V8")
    class MigrationTests {

        @Test
        @DisplayName("V8 表已正确创建")
        void testV8TablesExist() {
            int count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='long_term_memories'",
                    Integer.class);
            assertEquals(1, count);

            count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='session_summaries'",
                    Integer.class);
            assertEquals(1, count);

            count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='user_profiles_ext'",
                    Integer.class);
            assertEquals(1, count);
        }

        @Test
        @DisplayName("FTS5 虚拟表已创建")
        void testFts5TableExists() {
            int count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='long_term_memories_fts'",
                    Integer.class);
            assertEquals(1, count);
        }

        @Test
        @DisplayName("FTS5 触发器已创建")
        void testFts5TriggersExist() {
            int count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'long_term_memories_%'",
                    Integer.class);
            assertEquals(3, count);  // ai, ad, au
        }

        @Test
        @DisplayName("session_summaries 13 字段可写入读取")
        void testSessionSummariesAllFields() {
            long now = System.currentTimeMillis();
            jdbcTemplate.update("""
                INSERT INTO session_summaries (
                    session_id, parent_session_id, title, created_at, last_active_at,
                    active_task, goal, constraints, completed_actions, active_state,
                    in_progress, blocked, key_decisions, resolved_questions,
                    pending_user_asks, relevant_files, remaining_work, critical_context,
                    importance, linked_memory_ids, linked_session_ids, extracted_from_long_term
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    "s1", "s0", "测试摘要", now, now,
                    "任务", "目标", "约束", "已完成", "活跃",
                    "进行中", "阻塞", "决策", "已解决",
                    "待确认", "文件", "剩余", "关键",
                    0.8, "mem_1", "s_prev", 0);

            String title = jdbcTemplate.queryForObject(
                    "SELECT title FROM session_summaries WHERE session_id = ?", String.class, "s1");
            assertEquals("测试摘要", title);

            String task = jdbcTemplate.queryForObject(
                    "SELECT active_task FROM session_summaries WHERE session_id = ?", String.class, "s1");
            assertEquals("任务", task);
        }

        @Test
        @DisplayName("user_profiles_ext 完整字段可写入读取")
        void testUserProfilesExtAllFields() {
            long now = System.currentTimeMillis();
            jdbcTemplate.update("""
                INSERT INTO user_profiles_ext (
                    user_id, properties, preferences, known_topics, persistent_memory_ids,
                    topic_expertise, learned_patterns, conversation_count, first_seen, last_seen
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    "user1", "{}", "{}", "[]", "[]",
                    "{\"Java\":0.8}", "[]", 5, now, now);

            int count = jdbcTemplate.queryForObject(
                    "SELECT conversation_count FROM user_profiles_ext WHERE user_id = ?",
                    Integer.class, "user1");
            assertEquals(5, count);

            String expertise = jdbcTemplate.queryForObject(
                    "SELECT topic_expertise FROM user_profiles_ext WHERE user_id = ?",
                    String.class, "user1");
            assertTrue(expertise.contains("Java"));
        }

        @Test
        @DisplayName("性能索引已创建")
        void testIndexesExist() {
            String[] expectedIndexes = {
                    "idx_ltm_user_id", "idx_ltm_category", "idx_ltm_importance",
                    "idx_ltm_created_at", "idx_ltm_last_accessed", "idx_ltm_source_session",
                    "idx_ss_parent", "idx_ss_created"
            };

            for (String idx : expectedIndexes) {
                int count = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sqlite_master WHERE type='index' AND name=?",
                        Integer.class, idx);
                assertEquals(1, count, "索引 " + idx + " 应存在");
            }
        }
    }
}
