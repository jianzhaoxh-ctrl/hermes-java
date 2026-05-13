package com.hermes.agent.persistence;

import com.hermes.agent.persistence.migration.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema 迁移系统 + 增强功能测试
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaMigrationTest {

    private static SQLiteDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static SQLiteBackend backend;
    private static Path tempDb;

    @BeforeAll
    static void setup() throws Exception {
        // 创建临时数据库
        tempDb = Files.createTempFile("hermes-test-", ".db");
        tempDb.toFile().deleteOnExit();

        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDb.toString());

        jdbcTemplate = new JdbcTemplate(dataSource);
        backend = new SQLiteBackend(tempDb.toString(), 1);
        backend.initialize();
    }

    @AfterAll
    static void cleanup() {
        backend.shutdown();
        try {
            Files.deleteIfExists(tempDb);
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════
    // Schema 迁移测试
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    void testSchemaVersion() {
        int version = jdbcTemplate.queryForObject(
            "SELECT version FROM schema_version LIMIT 1", Integer.class
        );
        assertEquals(8, version, "Schema 版本应为 8");
    }

    @Test
    @Order(2)
    void testSessionsTableHasBillingFields() {
        // 验证计费字段存在
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pragma_table_info('sessions') WHERE name = 'billing_provider'",
            Integer.class
        );
        assertEquals(1, count, "sessions 表应有 billing_provider 字段");
    }

    @Test
    @Order(3)
    void testSessionsTableHasCompressionChain() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pragma_table_info('sessions') WHERE name = 'parent_session_id'",
            Integer.class
        );
        assertEquals(1, count, "sessions 表应有 parent_session_id 字段");
    }

    @Test
    @Order(4)
    void testSessionsTableHasTitle() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pragma_table_info('sessions') WHERE name = 'title'",
            Integer.class
        );
        assertEquals(1, count, "sessions 表应有 title 字段");
    }

    @Test
    @Order(5)
    void testMessagesTableHasReasoning() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pragma_table_info('messages') WHERE name = 'reasoning'",
            Integer.class
        );
        assertEquals(1, count, "messages 表应有 reasoning 字段");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 会话标题管理测试
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    void testSetTitle() {
        String sessionId = "test-title-1";
        backend.createSession(sessionId, "cli", null, "qwen-plus", null, null, null);

        assertTrue(backend.setSessionTitle(sessionId, "我的第一个会话"));
        assertEquals("我的第一个会话", backend.getSessionTitle(sessionId));
    }

    @Test
    @Order(11)
    void testTitleUniqueness() {
        String session1 = "test-title-unique-1";
        String session2 = "test-title-unique-2";

        backend.createSession(session1, "cli", null, "qwen-plus", null, null, null);
        backend.createSession(session2, "cli", null, "qwen-plus", null, null, null);

        backend.setSessionTitle(session1, "重复标题");

        // 第二个会话设置相同标题应抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            backend.setSessionTitle(session2, "重复标题");
        });
    }

    @Test
    @Order(12)
    void testTitleTooLong() {
        String sessionId = "test-title-long";
        backend.createSession(sessionId, "cli", null, "qwen-plus", null, null, null);

        String longTitle = "a".repeat(101);
        assertThrows(IllegalArgumentException.class, () -> {
            backend.setSessionTitle(sessionId, longTitle);
        });
    }

    @Test
    @Order(13)
    void testAutoNumberTitle() {
        String base = "自动编号测试";
        String session1 = "test-auto-1";
        String session2 = "test-auto-2";

        backend.createSession(session1, "cli", null, "qwen-plus", null, null, null);
        backend.setSessionTitle(session1, base);

        String nextTitle = backend.getNextTitleInLineage(base);
        assertEquals(base + " #2", nextTitle);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 压缩链测试
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    void testCompressionChain() {
        String parent = "compress-parent";
        String child = "compress-child";

        // 创建父会话
        backend.createSession(parent, "cli", null, "qwen-plus", null, null, null);
        backend.endSession(parent, "compression");

        // 创建压缩续接会话
        backend.createSession(child, "cli", null, "qwen-plus", null, null, parent);

        // 获取压缩链末端
        String tip = backend.getCompressionTip(parent);
        assertEquals(child, tip, "压缩链末端应为子会话");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 计费字段测试
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    void testTokenCounts() {
        String sessionId = "test-tokens";
        backend.createSession(sessionId, "cli", null, "qwen-plus", null, null, null);

        // 更新 token 计数
        backend.updateTokenCounts(sessionId, 100, 50, 10, 5, 20, 0.01, null, "dashscope");

        Map<String, Object> session = backend.getSession(sessionId).orElseThrow();
        assertEquals(100, session.get("inputTokens"));
        assertEquals(50, session.get("outputTokens"));
        assertEquals(0.01, session.get("estimatedCostUsd"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 增强搜索测试
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(40)
    void testSearchWithSourceFilter() {
        String sessionCli = "search-cli";
        String sessionTg = "search-telegram";

        backend.createSession(sessionCli, "cli", null, "qwen-plus", null, null, null);
        backend.createSession(sessionTg, "telegram", null, "qwen-plus", null, null, null);

        // 添加消息
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", "测试搜索关键词 Docker");
        backend.saveMessage(sessionCli, msg);

        msg.put("content", "测试搜索关键词 Kubernetes");
        backend.saveMessage(sessionTg, msg);

        // 搜索并过滤来源
        List<PersistenceBackend.SearchResult> results = backend.searchMessagesEnhanced(
            "关键词",
            List.of("cli"),  // 仅搜索 CLI 来源
            null, null, 10
        );

        assertFalse(results.isEmpty(), "应找到结果");
        // 所有结果应来自 CLI 会话
        for (PersistenceBackend.SearchResult result : results) {
            // 验证 session_id 以 "search-cli" 开头（因为我们的消息在那个会话）
            assertTrue(result.sessionId().equals(sessionCli));
        }
    }

    @Test
    @Order(41)
    void testSearchWithRoleFilter() {
        String sessionId = "search-role";
        backend.createSession(sessionId, "cli", null, "qwen-plus", null, null, null);

        // 添加用户消息
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "用户提问：如何部署 Docker？");
        backend.saveMessage(sessionId, userMsg);

        // 添加助手消息
        Map<String, Object> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", "助手回答：Docker 部署步骤如下...");
        backend.saveMessage(sessionId, assistantMsg);

        // 仅搜索用户消息
        List<PersistenceBackend.SearchResult> results = backend.searchMessagesEnhanced(
            "Docker",
            null,
            List.of("user"),  // 仅搜索用户消息
            null, 10
        );

        for (PersistenceBackend.SearchResult result : results) {
            assertEquals("user", result.role());
        }
    }

    @Test
    @Order(42)
    void testSearchContextWindow() {
        String sessionId = "search-context";
        backend.createSession(sessionId, "cli", null, "qwen-plus", null, null, null);

        // 添加多条消息
        for (int i = 0; i < 5; i++) {
            Map<String, Object> msg = new HashMap<>();
            msg.put("role", i % 2 == 0 ? "user" : "assistant");
            msg.put("content", "消息 " + i + " 包含目标关键词");
            backend.saveMessage(sessionId, msg);
        }

        // 获取消息 ID
        List<Long> ids = jdbcTemplate.queryForList(
            "SELECT id FROM messages WHERE session_id = ? ORDER BY timestamp",
            Long.class, sessionId
        );

        // 获取上下文窗口
        Long middleId = ids.get(2);
        List<Map<String, String>> context = backend.getContextMessages(middleId);

        assertNotNull(context, "上下文不应为 null");
        assertTrue(context.size() >= 1, "应至少返回中间消息");
    }

    // ═══════════════════════════════════════════════════════════════════
    // FTS5 中文搜索测试
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(50)
    void testChineseSearch() {
        String sessionId = "chinese-search";
        backend.createSession(sessionId, "cli", null, "qwen-plus", null, null, null);

        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", "我喜欢吃苹果和香蕉");
        backend.saveMessage(sessionId, msg);

        // 搜索中文关键词
        List<PersistenceBackend.SearchResult> results = backend.searchMessages("苹果", null, 10);

        assertFalse(results.isEmpty(), "应找到中文搜索结果");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 数据迁移测试
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(60)
    void testMigrateFromJson() {
        // 准备测试数据
        Map<String, List<Map<String, Object>>> sessionMessages = new HashMap<>();
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", "迁移测试消息");
        sessionMessages.put("migrate-test", List.of(msg));

        Map<String, Map<String, Object>> userProfiles = new HashMap<>();
        userProfiles.put("user-1", Map.of("name", "测试用户"));

        Map<String, List<String>> skillMemories = new HashMap<>();
        skillMemories.put("test-skill", List.of("技能记忆内容"));

        // 执行迁移
        backend.migrateFromJson(sessionMessages, userProfiles, skillMemories);

        // 验证迁移结果
        List<Map<String, Object>> messages = backend.getSessionMessages("migrate-test");
        assertFalse(messages.isEmpty(), "迁移后应有消息");

        assertTrue(backend.getUserProfile("user-1").isPresent(), "迁移后应有用户画像");
        assertFalse(backend.getSkillMemory("test-skill").isEmpty(), "迁移后应有技能记忆");
    }
}
