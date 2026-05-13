package com.hermes.agent.memory;

import com.hermes.agent.persistence.PersistenceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 会话全文搜索服务 — 跨会话检索历史对话。
 *
 * <p>参照 Python 版 hermes_state.py 的 FTS5 功能实现，Java 版使用纯内存倒排索引：
 * <ul>
 *   <li>启动时从 PersistenceService 加载所有会话消息构建索引</li>
 *   <li>支持关键词搜索和简单布尔查询（AND / OR）</li>
 *   <li>支持按 sessionId 限定搜索范围</li>
 *   <li>搜索结果按相关度排序（命中次数 × 时间衰减）</li>
 *   <li>新消息自动增量索引</li>
 * </ul>
 *
 * <p>注：Java 生态也可用 Lucene 实现 FTS，但为保持零外部依赖采用纯 Java 实现。
 * 如果后续需要更强大的搜索能力，可以替换为 Lucene 或 SQLite FTS5 后端。
 */
@Component
public class SessionSearchService {

    private static final Logger log = LoggerFactory.getLogger(SessionSearchService.class);

    private final PersistenceService persistence;

    /** 倒排索引：token → Set<DocId> */
    private final Map<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();

    /** 文档存储：docId → 文档内容 */
    private final Map<String, SearchDocument> documents = new ConcurrentHashMap<>();

    /** sessionId → Set<docId> */
    private final Map<String, Set<String>> sessionDocMap = new ConcurrentHashMap<>();

    private long docIdCounter = 0;

    // ── 中文分词：简单的双字符滑动窗口 + 英文空格分词 ──
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z0-9]+");

    public SessionSearchService(PersistenceService persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    public void loadFromDisk() {
        try {
            Map<String, List<Map<String, Object>>> saved = persistence.loadSessionMessages();
            int totalDocs = 0;
            for (Map.Entry<String, List<Map<String, Object>>> entry : saved.entrySet()) {
                String sessionId = entry.getKey();
                for (Map<String, Object> msgMap : entry.getValue()) {
                    String role = (String) msgMap.get("role");
                    String content = (String) msgMap.get("content");
                    if (content != null && !content.isBlank()) {
                        indexDocument(sessionId, role, content);
                        totalDocs++;
                    }
                }
            }
            log.info("[SessionSearch] 索引构建完成: {} 个文档, {} 个 token", totalDocs, invertedIndex.size());
        } catch (Exception e) {
            log.warn("[SessionSearch] 加载失败: {}", e.getMessage());
        }
    }

    /**
     * 索引一条新消息（增量索引）。
     */
    public void indexDocument(String sessionId, String role, String content) {
        if (content == null || content.isBlank()) return;

        String docId = "doc_" + (docIdCounter++);
        SearchDocument doc = new SearchDocument(docId, sessionId, role, content, System.currentTimeMillis());
        documents.put(docId, doc);
        sessionDocMap.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(docId);

        // 分词并更新倒排索引
        Set<String> tokens = tokenize(content);
        for (String token : tokens) {
            invertedIndex.computeIfAbsent(token.toLowerCase(), k -> ConcurrentHashMap.newKeySet()).add(docId);
        }
    }

    /**
     * 搜索历史对话。
     *
     * @param query    搜索查询（支持多词 AND 查询）
     * @param limit    返回结果上限
     * @param sessionId 限定会话范围（可选，null 表示搜索所有会话）
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query, int limit, String sessionId) {
        if (query == null || query.isBlank()) return List.of();

        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return List.of();

        // 找到包含所有查询 token 的文档（AND 查询）
        Set<String> candidateDocs = null;
        for (String token : queryTokens) {
            Set<String> docs = invertedIndex.getOrDefault(token.toLowerCase(), Set.of());
            if (candidateDocs == null) {
                candidateDocs = new HashSet<>(docs);
            } else {
                candidateDocs.retainAll(docs);
            }
            if (candidateDocs.isEmpty()) break;
        }

        if (candidateDocs == null || candidateDocs.isEmpty()) {
            // 回退：OR 查询（任意 token 匹配）
            candidateDocs = new HashSet<>();
            for (String token : queryTokens) {
                candidateDocs.addAll(invertedIndex.getOrDefault(token.toLowerCase(), Set.of()));
            }
        }

        // 按 sessionId 过滤
        if (sessionId != null && !sessionId.isBlank()) {
            Set<String> sessionDocs = sessionDocMap.getOrDefault(sessionId, Set.of());
            candidateDocs.retainAll(sessionDocs);
        }

        // 计算相关度并排序
        return candidateDocs.stream()
                .map(documents::get)
                .filter(Objects::nonNull)
                .map(doc -> {
                    // 相关度 = 命中 token 数 × 时间衰减
                    int hitCount = 0;
                    String lowerContent = doc.content.toLowerCase();
                    for (String token : queryTokens) {
                        if (lowerContent.contains(token.toLowerCase())) hitCount++;
                    }
                    double timeDecay = 1.0 / (1.0 + (System.currentTimeMillis() - doc.timestamp) / 3600000.0);
                    double score = hitCount * timeDecay;
                    return new SearchResult(doc, hitCount, score);
                })
                .sorted(Comparator.comparingDouble((SearchResult r) -> r.score).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 删除某个会话的所有索引。
     */
    public void clearSession(String sessionId) {
        Set<String> docIds = sessionDocMap.remove(sessionId);
        if (docIds == null) return;
        for (String docId : docIds) {
            SearchDocument doc = documents.remove(docId);
            if (doc != null) {
                Set<String> tokens = tokenize(doc.content);
                for (String token : tokens) {
                    Set<String> set = invertedIndex.get(token.toLowerCase());
                    if (set != null) set.remove(docId);
                }
            }
        }
        log.debug("[SessionSearch] 清除会话 {} 的索引", sessionId);
    }

    /**
     * 获取搜索统计信息。
     */
    public Map<String, Object> getStats() {
        return Map.of(
                "totalDocuments", documents.size(),
                "uniqueTokens", invertedIndex.size(),
                "indexedSessions", sessionDocMap.size()
        );
    }

    // ═══════════════════════════════════════════════════════════
    //  分词器
    // ═══════════════════════════════════════════════════════════

    /**
     * 混合分词：英文按空格分词 + 中文双字符滑动窗口。
     */
    static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return tokens;

        // 英文单词分词
        java.util.regex.Matcher wordMatcher = WORD_PATTERN.matcher(text);
        while (wordMatcher.find()) {
            String word = wordMatcher.group().toLowerCase();
            if (word.length() >= 2) tokens.add(word);
        }

        // 中文双字符滑动窗口
        StringBuilder cjkBuffer = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (CJK_PATTERN.matcher(String.valueOf(ch)).matches()) {
                cjkBuffer.append(ch);
            } else {
                if (cjkBuffer.length() >= 2) {
                    String cjkText = cjkBuffer.toString();
                    for (int j = 0; j < cjkText.length() - 1; j++) {
                        tokens.add(cjkText.substring(j, j + 2));
                    }
                    // 也加入三字组合（更精确匹配）
                    for (int j = 0; j < cjkText.length() - 2; j++) {
                        tokens.add(cjkText.substring(j, j + 3));
                    }
                }
                cjkBuffer.setLength(0);
            }
        }
        // 处理末尾中文字符
        if (cjkBuffer.length() >= 2) {
            String cjkText = cjkBuffer.toString();
            for (int j = 0; j < cjkText.length() - 1; j++) {
                tokens.add(cjkText.substring(j, j + 2));
            }
            for (int j = 0; j < cjkText.length() - 2; j++) {
                tokens.add(cjkText.substring(j, j + 3));
            }
        }

        return tokens;
    }

    // ═══════════════════════════════════════════════════════════
    //  数据类
    // ═══════════════════════════════════════════════════════════

    public static class SearchDocument {
        public final String docId;
        public final String sessionId;
        public final String role;
        public final String content;
        public final long timestamp;

        public SearchDocument(String docId, String sessionId, String role, String content, long timestamp) {
            this.docId = docId;
            this.sessionId = sessionId;
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }
    }

    public static class SearchResult {
        public final SearchDocument document;
        public final int hitCount;
        public final double score;

        public SearchResult(SearchDocument document, int hitCount, double score) {
            this.document = document;
            this.hitCount = hitCount;
            this.score = score;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", document.sessionId);
            m.put("role", document.role);
            m.put("content", document.content.length() > 300
                    ? document.content.substring(0, 300) + "..."
                    : document.content);
            m.put("relevanceScore", String.format("%.2f", score));
            m.put("hitCount", hitCount);
            return m;
        }
    }
}
