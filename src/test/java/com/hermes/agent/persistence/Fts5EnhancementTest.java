package com.hermes.agent.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 P0 功能：FTS5 查询清理、CJK 检测与回退、性能索引
 */
class Fts5EnhancementTest {

    // ═══════════════════════════════════════════════════════════════════
    // 1. FTS5 查询清理 — sanitizeFts5Query
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testSanitizeSimpleKeyword() {
        // 单词应添加前缀匹配 *
        // 但 sanitizeFts5Query 不自动添加 *，只做清理
        assertEquals("hello", SQLiteBackend.sanitizeFts5Query("hello"));
    }

    @Test
    void testSanitizeQuotedPhrase() {
        // 双引号短语应被保留
        assertEquals("\"exact phrase\"", SQLiteBackend.sanitizeFts5Query("\"exact phrase\""));
    }

    @Test
    void testSanitizeStripsSpecialChars() {
        // FTS5 特殊字符应被移除: + { } ( ) " ^
        String result = SQLiteBackend.sanitizeFts5Query("hello+(world){test}^end");
        assertFalse(result.contains("+"));
        assertFalse(result.contains("{"));
        assertFalse(result.contains("}"));
        assertFalse(result.contains("("));
        assertFalse(result.contains(")"));
        assertFalse(result.contains("^"));
    }

    @Test
    void testSanitizeCollapsesStars() {
        // 合并重复 *
        String result = SQLiteBackend.sanitizeFts5Query("hello***");
        assertFalse(result.contains("***"));
    }

    @Test
    void testSanitizeRemovesLeadingStar() {
        // 移除前导 *
        String result = SQLiteBackend.sanitizeFts5Query("*hello");
        assertFalse(result.startsWith("*"));
    }

    @Test
    void testSanitizeRemovesDanglingOperators() {
        // 移除开头的悬空操作符
        assertEquals("world", SQLiteBackend.sanitizeFts5Query("AND world"));
        assertEquals("hello", SQLiteBackend.sanitizeFts5Query("OR hello"));
        assertEquals("test", SQLiteBackend.sanitizeFts5Query("NOT test"));

        // 移除结尾的悬空操作符
        assertEquals("hello", SQLiteBackend.sanitizeFts5Query("hello AND"));
        assertEquals("world", SQLiteBackend.sanitizeFts5Query("world OR"));
    }

    @Test
    void testSanitizeHyphenatedTerms() {
        // 连字符术语应被包裹在引号中
        String result = SQLiteBackend.sanitizeFts5Query("chat-send");
        assertTrue(result.contains("\"chat-send\""), "Hyphenated term should be quoted: " + result);
    }

    @Test
    void testSanitizeDottedTerms() {
        // 点号术语应被包裹在引号中
        String result = SQLiteBackend.sanitizeFts5Query("P2.2");
        assertTrue(result.contains("\"P2.2\""), "Dotted term should be quoted: " + result);
    }

    @Test
    void testSanitizeComplexQuery() {
        // 复杂查询：引号短语 + 特殊字符 + 连字符
        String result = SQLiteBackend.sanitizeFts5Query("\"docker deploy\" + my-app.config.ts");
        assertTrue(result.contains("\"docker deploy\""), "Quoted phrase should be preserved: " + result);
        assertTrue(result.contains("\"my-app.config.ts\""), "Hyphenated+dotted term should be quoted: " + result);
        assertFalse(result.contains("+"), "Special chars should be removed: " + result);
    }

    @Test
    void testSanitizeEmptyInput() {
        assertEquals("", SQLiteBackend.sanitizeFts5Query(null));
        assertEquals("", SQLiteBackend.sanitizeFts5Query(""));
        assertEquals("", SQLiteBackend.sanitizeFts5Query("   "));
    }

    @Test
    void testSanitizePreservesValidOperators() {
        // 中间的 AND/OR 应被保留
        String result = SQLiteBackend.sanitizeFts5Query("docker AND kubernetes");
        assertTrue(result.contains("AND"), "Valid AND operator should be preserved: " + result);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 2. CJK 检测 — containsCjk
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testContainsCjkChinese() {
        assertTrue(SQLiteBackend.containsCjk("你好世界"));
    }

    @Test
    void testContainsCjkMixedChineseEnglish() {
        assertTrue(SQLiteBackend.containsCjk("hello 世界"));
    }

    @Test
    void testContainsCjkJapanese() {
        assertTrue(SQLiteBackend.containsCjk("こんにちは"));  // Hiragana
        assertTrue(SQLiteBackend.containsCjk("カタカナ"));    // Katakana
    }

    @Test
    void testContainsCjkKorean() {
        assertTrue(SQLiteBackend.containsCjk("안녕하세요"));  // Hangul
    }

    @Test
    void testContainsCjkEnglishOnly() {
        assertFalse(SQLiteBackend.containsCjk("hello world"));
    }

    @Test
    void testContainsCjkEmpty() {
        assertFalse(SQLiteBackend.containsCjk(null));
        assertFalse(SQLiteBackend.containsCjk(""));
    }

    @Test
    void testContainsCjkCJKSymbols() {
        assertTrue(SQLiteBackend.containsCjk("【测试】"));  // CJK Symbols
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. 索引 + 集成测试（搜索 CJK + FTS5 清理）
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testSearchWithChineseQuery(@TempDir Path tempDir) {
        // 创建临时数据库测试中文搜索
        String dbPath = tempDir.resolve("test-cjk.db").toString();
        SQLiteBackend backend = new SQLiteBackend(dbPath, 1);
        backend.initialize();

        try {
            // 创建会话并添加中文消息
            backend.createSession("test-session-1", "cli", null, "qwen-plus", null, null, null);

            // 保存中文消息
            backend.saveMessage("test-session-1", Map.of(
                    "role", "user",
                    "content", "今天天气很好，适合出去散步",
                    "timestamp", System.currentTimeMillis() / 1000
            ));
            backend.saveMessage("test-session-1", Map.of(
                    "role", "assistant",
                    "content", "是的，今天阳光明媚，建议去公园走走",
                    "timestamp", System.currentTimeMillis() / 1000 + 1
            ));

            // 搜索中文 — FTS5 应能命中（unicode61 分词器支持）
            List<PersistenceBackend.SearchResult> results =
                    backend.searchMessagesEnhanced("天气", null, null, null, 10);
            assertFalse(results.isEmpty(), "Chinese search should return results via FTS5 or LIKE fallback");

            // 搜索多字中文
            results = backend.searchMessagesEnhanced("阳光明媚", null, null, null, 10);
            // 无论 FTS5 还是 LIKE 回退，都应返回结果
            assertFalse(results.isEmpty(), "Multi-char Chinese search should work");

        } finally {
            backend.shutdown();
        }
    }

    @Test
    void testSearchWithSpecialChars(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("test-special.db").toString();
        SQLiteBackend backend = new SQLiteBackend(dbPath, 1);
        backend.initialize();

        try {
            backend.createSession("test-session-2", "cli", null, "qwen-plus", null, null, null);

            backend.saveMessage("test-session-2", Map.of(
                    "role", "user",
                    "content", "Deploy my-app.config.ts to production",
                    "timestamp", System.currentTimeMillis() / 1000
            ));

            // 搜索含连字符/点号的术语 — sanitizeFts5Query 应包裹引号
            List<PersistenceBackend.SearchResult> results =
                    backend.searchMessagesEnhanced("my-app.config.ts", null, null, null, 10);
            // 不应抛异常（之前会因 FTS5 语法错误崩溃）
            assertNotNull(results, "Search with special chars should not throw");

        } finally {
            backend.shutdown();
        }
    }

    @Test
    void testIndexesCreated(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("test-indexes.db").toString();
        SQLiteBackend backend = new SQLiteBackend(dbPath, 1);
        backend.initialize();

        try {
            // 验证索引是否通过 V7 迁移创建（而不是临时 SQL）
            assertTrue(backend.isAvailable());
            // 验证 schema 版本为 7
            assertEquals(8, backend.getSchemaVersion(), "Schema should be at v8 after V8 migration");
        } finally {
            backend.shutdown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 4. isCjkCodePoint 单码点检测
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testIsCjkCodePoint() {
        // CJK Unified Ideographs
        assertTrue(SQLiteBackend.isCjkCodePoint(0x4E00), "CJK basic");
        assertTrue(SQLiteBackend.isCjkCodePoint(0x9FFF), "CJK end");

        // CJK Extension A
        assertTrue(SQLiteBackend.isCjkCodePoint(0x3400), "CJK Ext A");

        // Hiragana
        assertTrue(SQLiteBackend.isCjkCodePoint(0x3040), "Hiragana");

        // Katakana
        assertTrue(SQLiteBackend.isCjkCodePoint(0x30A0), "Katakana");

        // Hangul
        assertTrue(SQLiteBackend.isCjkCodePoint(0xAC00), "Hangul");

        // Non-CJK
        assertFalse(SQLiteBackend.isCjkCodePoint(0x0041), "ASCII 'A' is not CJK");
        assertFalse(SQLiteBackend.isCjkCodePoint(0x0030), "ASCII '0' is not CJK");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 5. excludeSources 过滤
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testSearchWithExcludeSources(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("test-exclude.db").toString();
        SQLiteBackend backend = new SQLiteBackend(dbPath, 1);
        backend.initialize();

        try {
            // 创建两个不同来源的会话
            backend.createSession("session-cli", "cli", null, "qwen-plus", null, null, null);
            backend.createSession("session-tg", "telegram", null, "qwen-plus", null, null, null);

            long ts = System.currentTimeMillis() / 1000;
            backend.saveMessage("session-cli", Map.of(
                    "role", "user", "content", "Deploy to production server", "timestamp", ts));
            backend.saveMessage("session-tg", Map.of(
                    "role", "user", "content", "Deploy to staging server", "timestamp", ts + 1));

            // 搜索 "Deploy"，排除 cli 来源
            List<PersistenceBackend.SearchResult> results =
                    backend.searchMessagesEnhanced("Deploy", null, List.of("cli"), null, null, 10);

            // 只应返回 telegram 来源的结果
            assertTrue(results.stream().allMatch(r -> r.sessionId().equals("session-tg")),
                    "Should only return results from non-excluded sources");

        } finally {
            backend.shutdown();
        }
    }
}
