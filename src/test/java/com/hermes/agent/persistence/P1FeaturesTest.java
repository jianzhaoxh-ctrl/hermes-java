package com.hermes.agent.persistence;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.hermes.agent.persistence.PersistenceBackend.SearchResult;

/**
 * P1 功能测试 — 验证 4 个参考 Python 版补齐的功能：
 * 1. 会话列表增强（预览 + 最后活跃）
 * 2. 压缩链投影
 * 3. 搜索结果上下文内嵌
 * 4. 按时间清理 + 子会话孤儿处理
 */
class P1FeaturesTest {

    private SQLiteBackend backend;
    private Path tempDb;

    @BeforeEach
    void init() throws Exception {
        tempDb = Files.createTempDirectory("hermes-p1-test").resolve("test-" + System.nanoTime() + ".db");
        backend = new SQLiteBackend(tempDb.toString(), 2);
        backend.initialize();
    }

    @AfterEach
    void cleanup() {
        backend.shutdown();
        try { Files.deleteIfExists(tempDb); } catch (Exception ignored) {}
        try { Files.deleteIfExists(tempDb.getParent()); } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════
    // 1. 会话列表增强（预览 + 最后活跃）
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testListSessionsRich_previewAndLastActive() {
        // 创建会话
        backend.createSession("s1", "cli", "user1", "qwen-plus", null, null, null);

        // 添加消息
        Map<String, Object> msg1 = new LinkedHashMap<>();
        msg1.put("role", "user");
        msg1.put("content", "这是一条用户消息，用于测试预览功能是否正常工作");
        backend.saveMessage("s1", msg1);

        Map<String, Object> msg2 = new LinkedHashMap<>();
        msg2.put("role", "assistant");
        msg2.put("content", "助手回复");
        backend.saveMessage("s1", msg2);

        // 查询增强版会话列表
        List<Map<String, Object>> sessions = backend.listSessionsRich(null, 10, 0, false);

        assertFalse(sessions.isEmpty(), "应该至少返回 1 个会话");

        Map<String, Object> session = sessions.get(0);
        assertEquals("s1", session.get("id"));
        assertNotNull(session.get("preview"), "应该包含 preview 字段");
        assertNotNull(session.get("lastActive"), "应该包含 lastActive 字段");

        // preview 应包含用户消息的前 60 字符
        String preview = (String) session.get("preview");
        assertFalse(preview.isEmpty(), "preview 不应为空");
        assertTrue(preview.contains("用户消息"), "preview 应包含原始消息内容");
    }

    @Test
    void testListSessionsRich_sourceFilter() {
        backend.createSession("s1", "cli", null, "qwen", null, null, null);
        backend.createSession("s2", "telegram", null, "qwen", null, null, null);
        backend.createSession("s3", "cli", null, "qwen", null, null, null);

        List<Map<String, Object>> cliSessions = backend.listSessionsRich("cli", 10, 0, false);
        assertEquals(2, cliSessions.size(), "应只返回 cli 来源的会话");

        List<Map<String, Object>> allSessions = backend.listSessionsRich(null, 10, 0, false);
        assertEquals(3, allSessions.size(), "不设过滤应返回全部");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 2. 压缩链投影
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testCompressionChainProjection() {
        // 创建根会话
        backend.createSession("root", "cli", null, "qwen", null, null, null);

        Map<String, Object> rootMsg = new LinkedHashMap<>();
        rootMsg.put("role", "user");
        rootMsg.put("content", "根会话用户消息");
        backend.saveMessage("root", rootMsg);

        // 结束根会话（compression）
        backend.endSession("root", "compression");

        // 创建续接会话
        backend.createSession("child1", "cli", null, "qwen", null, null, "root");

        Map<String, Object> childMsg = new LinkedHashMap<>();
        childMsg.put("role", "user");
        childMsg.put("content", "续接会话用户消息，这是新的对话");
        backend.saveMessage("child1", childMsg);

        Map<String, Object> childMsg2 = new LinkedHashMap<>();
        childMsg2.put("role", "assistant");
        childMsg2.put("content", "续接助手回复");
        backend.saveMessage("child1", childMsg2);

        // 不投影：应该返回 root 会话
        List<Map<String, Object>> noProjection = backend.listSessionsRich(null, 10, 0, false);
        assertEquals(1, noProjection.size(), "不投影应只有 1 个根会话");
        assertEquals("root", noProjection.get(0).get("id"), "不投影时应返回原始 root id");

        // 投影：root 应被投影到 child1
        List<Map<String, Object>> withProjection = backend.listSessionsRich(null, 10, 0, true);
        assertEquals(1, withProjection.size(), "投影后仍应只有 1 个条目");
        Map<String, Object> projected = withProjection.get(0);
        assertEquals("child1", projected.get("id"), "投影后 id 应为续接会话的 id");
        assertEquals("root", projected.get("_lineageRootId"), "应保留 _lineageRootId 指向原始根");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. 搜索结果上下文内嵌
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testSearchMessagesWithContext() {
        backend.createSession("s1", "cli", null, "qwen", null, null, null);

        // 添加连续消息
        Map<String, Object> msg1 = new LinkedHashMap<>();
        msg1.put("role", "user");
        msg1.put("content", "Docker 部署问题");
        backend.saveMessage("s1", msg1);

        Map<String, Object> msg2 = new LinkedHashMap<>();
        msg2.put("role", "assistant");
        msg2.put("content", "可以使用 docker-compose 来部署");
        backend.saveMessage("s1", msg2);

        Map<String, Object> msg3 = new LinkedHashMap<>();
        msg3.put("role", "user");
        msg3.put("content", "Kubernetes 和 Docker 有什么区别");
        backend.saveMessage("s1", msg3);

        // 搜索带上下文
        List<SearchResult> results = backend.searchMessagesWithContext("Docker", null, null, null, 10);

        assertFalse(results.isEmpty(), "应该有搜索结果");

        for (SearchResult r : results) {
            assertNotNull(r.context(), "搜索结果应包含 context 字段");
            // context 可能包含前后消息
            assertTrue(r.context().size() >= 1, "context 至少应包含匹配消息本身");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 4. 删除会话 + 子会话孤儿处理
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testDeleteSession_orphanChildren() {
        // 创建父会话和子会话
        backend.createSession("parent", "cli", null, "qwen", null, null, null);
        backend.createSession("child", "cli", null, "qwen", null, null, "parent");

        // 添加消息
        Map<String, Object> parentMsg = new LinkedHashMap<>();
        parentMsg.put("role", "user");
        parentMsg.put("content", "父会话消息");
        backend.saveMessage("parent", parentMsg);

        Map<String, Object> childMsg = new LinkedHashMap<>();
        childMsg.put("role", "user");
        childMsg.put("content", "子会话消息");
        backend.saveMessage("child", childMsg);

        // 验证子会话有父
        Optional<Map<String, Object>> childBefore = backend.getSession("child");
        assertTrue(childBefore.isPresent());
        assertEquals("parent", childBefore.get().get("parentSessionId"));

        // 删除父会话
        boolean deleted = backend.deleteSession("parent");
        assertTrue(deleted, "删除应成功");

        // 父会话应已删除
        assertFalse(backend.getSession("parent").isPresent(), "父会话应不存在");

        // 子会话应仍存在，但 parentSessionId 应为 NULL（孤儿化）
        Optional<Map<String, Object>> childAfter = backend.getSession("child");
        assertTrue(childAfter.isPresent(), "子会话应仍存在");
        assertNull(childAfter.get().get("parentSessionId"), "子会话的 parentSessionId 应被置空");
    }

    @Test
    void testDeleteSession_notFound() {
        boolean deleted = backend.deleteSession("nonexistent");
        assertFalse(deleted, "不存在的会话应返回 false");
    }

    @Test
    void testPruneSessions() {
        // 创建已结束的旧会话
        backend.createSession("old1", "cli", null, "qwen", null, null, null);
        backend.endSession("old1", "completed");

        // 手动修改 started_at 模拟 100 天前
        backend.updateSessionTimestamp("old1", System.currentTimeMillis() / 1000 - 100 * 86400L);

        // 创建活跃的新会话（不应被清理）
        backend.createSession("active1", "cli", null, "qwen", null, null, null);

        // 清理 90 天前的会话
        int pruned = backend.pruneSessions(90, null);
        assertEquals(1, pruned, "应清理 1 个旧会话");

        // 活跃会话应仍存在
        assertTrue(backend.getSession("active1").isPresent(), "活跃会话不应被清理");

        // 旧会话应已删除
        assertFalse(backend.getSession("old1").isPresent(), "旧会话应已被清理");
    }

    @Test
    void testPruneSessions_withSourceFilter() {
        // 创建已结束的旧会话（不同来源）
        backend.createSession("old-cli", "cli", null, "qwen", null, null, null);
        backend.endSession("old-cli", "completed");
        backend.updateSessionTimestamp("old-cli", System.currentTimeMillis() / 1000 - 100 * 86400L);

        backend.createSession("old-tg", "telegram", null, "qwen", null, null, null);
        backend.endSession("old-tg", "completed");
        backend.updateSessionTimestamp("old-tg", System.currentTimeMillis() / 1000 - 100 * 86400L);

        // 只清理 telegram 来源
        int pruned = backend.pruneSessions(90, "telegram");
        assertEquals(1, pruned, "只应清理 telegram 来源");

        assertFalse(backend.getSession("old-tg").isPresent(), "telegram 会话应被清理");
        assertTrue(backend.getSession("old-cli").isPresent(), "cli 会话不应被清理");
    }

    @Test
    void testPruneSessions_orphanChildren() {
        // 创建父子会话链
        backend.createSession("old-parent", "cli", null, "qwen", null, null, null);
        backend.endSession("old-parent", "completed");
        backend.updateSessionTimestamp("old-parent", System.currentTimeMillis() / 1000 - 100 * 86400L);

        backend.createSession("young-child", "cli", null, "qwen", null, null, "old-parent");

        // 清理时，子会话应被孤儿化
        int pruned = backend.pruneSessions(90, null);
        assertEquals(1, pruned);

        // 子会话应仍存在
        Optional<Map<String, Object>> child = backend.getSession("young-child");
        assertTrue(child.isPresent(), "子会话应仍存在");
        assertNull(child.get().get("parentSessionId"), "子会话的 parentSessionId 应被置空");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 5. clearSession 行为修正（不再删除会话本身，只清空消息）
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testClearSession_preservesSession() {
        backend.createSession("s1", "cli", null, "qwen", null, null, null);
        backend.setSessionTitle("s1", "测试会话");

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", "消息内容");
        backend.saveMessage("s1", msg);

        // 清空会话
        backend.clearSession("s1");

        // 会话应仍存在
        assertTrue(backend.getSession("s1").isPresent(), "会话应仍存在");
        assertEquals("测试会话", backend.getSessionTitle("s1"), "标题应保留");

        // 消息应被清空
        List<Map<String, Object>> messages = backend.getSessionMessages("s1");
        assertTrue(messages.isEmpty(), "消息应被清空");
    }
}
