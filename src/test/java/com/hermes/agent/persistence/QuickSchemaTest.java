package com.hermes.agent.persistence;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 快速验证 Schema 迁移和核心功能
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuickSchemaTest {

    private static SQLiteBackend backend;
    private static Path tempDb;

    @BeforeAll
    static void setup() throws Exception {
        tempDb = Files.createTempFile("hermes-quick-", ".db");
        tempDb.toFile().deleteOnExit();

        backend = new SQLiteBackend(tempDb.toString(), 1);
        backend.initialize();

        System.out.println("✓ 数据库初始化成功: " + tempDb);
    }

    @AfterAll
    static void cleanup() {
        backend.shutdown();
        try { Files.deleteIfExists(tempDb); } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    void testSchemaVersion() {
        int version = backend.getSchemaVersion();
        assertEquals(8, version, "Schema 版本应为 8");
        System.out.println("✓ Schema 版本: v" + version);
    }

    @Test
    @Order(2)
    void testSessionWithMetadata() {
        String sessionId = "meta-test-" + System.currentTimeMillis();
        backend.createSession(sessionId, "cli", "user-1", "qwen-plus", 
                "{\"temp\":0.7}", "You are helpful.", null);

        // 设置标题
        assertTrue(backend.setSessionTitle(sessionId, "测试会话标题"));
        assertEquals("测试会话标题", backend.getSessionTitle(sessionId));

        // 更新 token 计数
        backend.updateTokenCounts(sessionId, 100, 50, 0, 0, 0, 0.01, null, "dashscope");

        // 获取会话详情
        Map<String, Object> session = backend.getSession(sessionId).orElseThrow();
        assertEquals("cli", session.get("source"));
        assertEquals("user-1", session.get("userId"));
        assertEquals(100, session.get("inputTokens"));
        assertEquals(50, session.get("outputTokens"));

        System.out.println("✓ 会话元数据功能正常");
    }

    @Test
    @Order(3)
    void testCompressionChain() {
        String parent = "parent-" + System.currentTimeMillis();
        String child = "child-" + System.currentTimeMillis();

        backend.createSession(parent, "cli", null, "qwen-plus", null, null, null);
        backend.endSession(parent, "compression");
        backend.createSession(child, "cli", null, "qwen-plus", null, null, parent);

        String tip = backend.getCompressionTip(parent);
        assertEquals(child, tip, "压缩链末端应为子会话");

        System.out.println("✓ 压缩链追踪正常");
    }

    @Test
    @Order(4)
    void testMessageWithReasoning() {
        String sessionId = "reasoning-test-" + System.currentTimeMillis();
        backend.createSession(sessionId, "cli", null, "qwen-plus", null, null, null);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "答案是 42");
        msg.put("reasoning", "首先分析问题，然后得出结论...");
        msg.put("reasoningDetails", Map.of("steps", List.of("分析", "推理", "结论")));
        backend.saveMessage(sessionId, msg);

        List<Map<String, Object>> messages = backend.getSessionMessages(sessionId);
        assertFalse(messages.isEmpty());

        Map<String, Object> saved = messages.get(0);
        assertEquals("assistant", saved.get("role"));
        assertEquals("答案是 42", saved.get("content"));
        assertEquals("首先分析问题，然后得出结论...", saved.get("reasoning"));

        System.out.println("✓ 推理链字段正常");
    }

    @Test
    @Order(5)
    void testTitleUniqueness() {
        String session1 = "unique-1-" + System.currentTimeMillis();
        String session2 = "unique-2-" + System.currentTimeMillis();

        backend.createSession(session1, "cli", null, "qwen-plus", null, null, null);
        backend.createSession(session2, "cli", null, "qwen-plus", null, null, null);

        backend.setSessionTitle(session1, "唯一标题测试");

        // 第二个会话设置相同标题应抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            backend.setSessionTitle(session2, "唯一标题测试");
        });

        System.out.println("✓ 标题唯一性约束正常");
    }

    @Test
    @Order(6)
    void testAutoNumberTitle() {
        String base = "自动编号-" + System.currentTimeMillis();
        String session1 = "auto-1-" + System.currentTimeMillis();

        backend.createSession(session1, "cli", null, "qwen-plus", null, null, null);
        backend.setSessionTitle(session1, base);

        String next = backend.getNextTitleInLineage(base);
        assertTrue(next.contains("#2"), "下一个标题应包含编号");

        System.out.println("✓ 自动编号: " + base + " → " + next);
    }

    @Test
    @Order(7)
    void testEnhancedSearch() {
        String sessionId = "search-test-" + System.currentTimeMillis();
        backend.createSession(sessionId, "telegram", null, "qwen-plus", null, null, null);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", "测试增强搜索功能的关键词 Docker");
        backend.saveMessage(sessionId, msg);

        // 测试搜索
        List<PersistenceBackend.SearchResult> results = backend.searchMessages("Docker", null, 10);
        assertFalse(results.isEmpty(), "应找到搜索结果");

        // 测试 snippet 字段（替代了原来的 content）
        PersistenceBackend.SearchResult first = results.get(0);
        assertNotNull(first.snippet(), "应有摘要片段");

        System.out.println("✓ 增强搜索正常，snippet: " + first.snippet().substring(0, Math.min(50, first.snippet().length())) + "...");
    }

    @Test
    @Order(8)
    void testContextWindow() {
        String sessionId = "context-test-" + System.currentTimeMillis();
        backend.createSession(sessionId, "cli", null, "qwen-plus", null, null, null);

        // 添加多条消息
        for (int i = 0; i < 5; i++) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", i % 2 == 0 ? "user" : "assistant");
            msg.put("content", "上下文消息 " + i);
            backend.saveMessage(sessionId, msg);
        }

        // 获取中间消息的 ID
        Long middleId = (Long) backend.getSessionMessages(sessionId).get(2).get("id");

        // 获取上下文窗口
        List<Map<String, String>> context = backend.getContextMessages(middleId);
        assertNotNull(context);
        assertTrue(context.size() >= 1, "应至少返回中间消息");

        System.out.println("✓ 上下文窗口: " + context.size() + " 条消息");
    }

    @Test
    @Order(9)
    void testDataMigration() {
        String sessionId = "migrate-test-" + System.currentTimeMillis();

        Map<String, List<Map<String, Object>>> sessions = new HashMap<>();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", "迁移测试消息");
        sessions.put(sessionId, List.of(msg));

        Map<String, Map<String, Object>> profiles = Map.of("user-1", Map.of("name", "测试用户"));
        Map<String, List<String>> skills = Map.of("skill-1", List.of("技能记忆"));

        backend.migrateFromJson(sessions, profiles, skills);

        // 验证迁移结果
        assertFalse(backend.getSessionMessages(sessionId).isEmpty());
        assertTrue(backend.getUserProfile("user-1").isPresent());
        assertFalse(backend.getSkillMemory("skill-1").isEmpty());

        System.out.println("✓ 数据迁移正常");
    }
}
