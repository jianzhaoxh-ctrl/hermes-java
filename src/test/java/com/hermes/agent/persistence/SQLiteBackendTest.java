package com.hermes.agent.persistence;

import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLiteBackend 单元测试
 */
class SQLiteBackendTest {

    private static final String TEST_DB_PATH = System.getProperty("java.io.tmpdir") + "/hermes_test.db";
    private SQLiteBackend backend;

    @BeforeEach
    void setUp() throws IOException {
        // 清理旧的测试数据库
        Path dbPath = Paths.get(TEST_DB_PATH);
        Files.deleteIfExists(dbPath);
        Files.deleteIfExists(Paths.get(TEST_DB_PATH + "-wal"));
        Files.deleteIfExists(Paths.get(TEST_DB_PATH + "-shm"));

        backend = new SQLiteBackend(TEST_DB_PATH, 2);
        backend.initialize();
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.shutdown();
        }
    }

    @Test
    @DisplayName("测试保存和获取消息")
    void testSaveAndGetMessage() {
        String sessionId = "test-session-001";
        
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", "你好，这是一条测试消息");
        message.put("timestamp", System.currentTimeMillis() / 1000);

        backend.saveMessage(sessionId, message);

        List<Map<String, Object>> messages = backend.getSessionMessages(sessionId);
        
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("你好，这是一条测试消息", messages.get(0).get("content"));
    }

    @Test
    @DisplayName("测试消息限制")
    void testMessageLimit() {
        String sessionId = "test-session-002";
        long baseTime = System.currentTimeMillis() / 1000;
        
        for (int i = 0; i < 10; i++) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", i % 2 == 0 ? "user" : "assistant");
            message.put("content", "消息 #" + i);
            message.put("timestamp", baseTime + i);
            backend.saveMessage(sessionId, message);
        }

        List<Map<String, Object>> messages = backend.getSessionMessages(sessionId, 5);
        
        assertEquals(5, messages.size());
        // 应该返回最后 5 条消息（时间顺序）
        assertTrue(messages.get(0).get("content").toString().contains("#5"));
    }

    @Test
    @DisplayName("测试用户画像")
    void testUserProfile() {
        String userId = "user-001";
        
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "张三");
        profile.put("role", "开发者");
        profile.put("preferences", Arrays.asList("Python", "Java"));

        backend.saveUserProfile(userId, profile);

        Optional<Map<String, Object>> loaded = backend.getUserProfile(userId);
        
        assertTrue(loaded.isPresent());
        assertEquals("张三", loaded.get().get("name"));
        assertEquals("开发者", loaded.get().get("role"));
    }

    @Test
    @DisplayName("测试技能记忆")
    void testSkillMemory() {
        String skillName = "code-review";
        
        backend.saveSkillMemory(skillName, "学会了检查空指针");
        backend.saveSkillMemory(skillName, "学会了检查资源泄漏");

        List<String> memories = backend.getSkillMemory(skillName);
        
        assertEquals(2, memories.size());
        // 不依赖顺序，只验证两条记录都存在
        assertTrue(memories.stream().anyMatch(m -> m.contains("资源泄漏")));
        assertTrue(memories.stream().anyMatch(m -> m.contains("空指针")));
    }

    @Test
    @DisplayName("测试全文搜索")
    void testFullTextSearch() {
        String sessionId = "search-session";
        
        Map<String, Object> msg1 = new LinkedHashMap<>();
        msg1.put("role", "user");
        msg1.put("content", "我想学习 Python 编程");
        backend.saveMessage(sessionId, msg1);

        Map<String, Object> msg2 = new LinkedHashMap<>();
        msg2.put("role", "assistant");
        msg2.put("content", "Python 是一门很棒的编程语言");
        backend.saveMessage(sessionId, msg2);

        Map<String, Object> msg3 = new LinkedHashMap<>();
        msg3.put("role", "user");
        msg3.put("content", "Java 和 Python 哪个更好？");
        backend.saveMessage(sessionId, msg3);

        // 搜索 "Python"
        List<PersistenceBackend.SearchResult> results = backend.searchMessages("Python", null, 10);
        
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(r -> r.snippet().contains("Python")));
    }

    @Test
    @DisplayName("测试清除会话")
    void testClearSession() {
        String sessionId = "clear-session";
        
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", "测试消息");
        backend.saveMessage(sessionId, message);

        assertFalse(backend.getSessionMessages(sessionId).isEmpty());

        backend.clearSession(sessionId);

        assertTrue(backend.getSessionMessages(sessionId).isEmpty());
    }

    @Test
    @DisplayName("测试获取所有会话ID")
    void testGetAllSessionIds() {
        backend.saveMessage("session-1", Map.of("role", "user", "content", "msg1"));
        backend.saveMessage("session-2", Map.of("role", "user", "content", "msg2"));
        backend.saveMessage("session-3", Map.of("role", "user", "content", "msg3"));

        List<String> sessionIds = backend.getAllSessionIds();
        
        assertTrue(sessionIds.size() >= 3);
        assertTrue(sessionIds.contains("session-1"));
        assertTrue(sessionIds.contains("session-2"));
        assertTrue(sessionIds.contains("session-3"));
    }
}
