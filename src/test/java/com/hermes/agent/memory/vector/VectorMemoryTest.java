package com.hermes.agent.memory.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

/**
 * 向量记忆单元测试
 */
class VectorMemoryTest {

    private MockEmbeddingService embeddingService;
    private MemoryVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        embeddingService = new MockEmbeddingService();
        // 简单配置
        MockConfig config = new MockConfig();
        vectorStore = new MemoryVectorStore(config, embeddingService);
    }

    @Test
    @DisplayName("测试添加和搜索向量")
    void testAddAndSearch() {
        // 添加记忆
        assertTrue(vectorStore.addText("mem_1", "我喜欢吃苹果", "session_1", "preference", null));
        assertTrue(vectorStore.addText("mem_2", "我的名字是张三", "session_1", "fact", null));
        assertTrue(vectorStore.addText("mem_3", "我喜欢吃香蕉", "session_1", "preference", null));

        // 搜索相似记忆
        List<MemoryVectorStore.SearchResult> results = vectorStore.searchText("我喜欢什么水果", 5, "session_1", null);

        assertFalse(results.isEmpty());
        System.out.println("搜索结果:");
        for (MemoryVectorStore.SearchResult result : results) {
            System.out.printf("- %s (score: %.4f)%n", result.entry.content, result.score);
        }
    }

    @Test
    @DisplayName("测试按类型过滤")
    void testSearchByType() {
        vectorStore.addText("mem_1", "我喜欢吃苹果", "session_1", "preference", null);
        vectorStore.addText("mem_2", "我的名字是张三", "session_1", "fact", null);

        List<MemoryVectorStore.SearchResult> results = vectorStore.searchText("我", 10, "session_1", "preference");

        for (MemoryVectorStore.SearchResult result : results) {
            assertEquals("preference", result.entry.type);
        }
    }

    @Test
    @DisplayName("测试删除记忆")
    void testDelete() {
        vectorStore.addText("mem_1", "测试记忆", "session_1", "general", null);

        assertTrue(vectorStore.delete("mem_1"));
        assertNull(vectorStore.get("mem_1"));
    }

    @Test
    @DisplayName("测试会话隔离")
    void testSessionIsolation() {
        vectorStore.addText("mem_1", "会话1的记忆", "session_1", "general", null);
        vectorStore.addText("mem_2", "会话2的记忆", "session_2", "general", null);

        List<MemoryVectorStore.SearchResult> results1 = vectorStore.searchText("记忆", 10, "session_1", null);
        List<MemoryVectorStore.SearchResult> results2 = vectorStore.searchText("记忆", 10, "session_2", null);

        assertEquals(1, results1.size());
        assertEquals("会话1的记忆", results1.get(0).entry.content);

        assertEquals(1, results2.size());
        assertEquals("会话2的记忆", results2.get(0).entry.content);
    }

    @Test
    @DisplayName("测试删除会话")
    void testDeleteBySession() {
        vectorStore.addText("mem_1", "记忆1", "session_1", "general", null);
        vectorStore.addText("mem_2", "记忆2", "session_1", "general", null);
        vectorStore.addText("mem_3", "记忆3", "session_2", "general", null);

        int deleted = vectorStore.deleteBySession("session_1");

        assertEquals(2, deleted);
        assertTrue(vectorStore.getBySession("session_1").isEmpty());
        assertEquals(1, vectorStore.getBySession("session_2").size());
    }

    // ═══════════════════════════════════════════════════════════
    //  Mock 类
    // ═══════════════════════════════════════════════════════════

    static class MockEmbeddingService implements EmbeddingService {
        @Override
        public String getProviderName() { return "mock"; }

        @Override
        public String getModelName() { return "mock-model"; }

        @Override
        public int getDimension() { return 128; }

        @Override
        public float[] embed(String text) {
            // 简单的模拟向量：基于文本哈希
            float[] vector = new float[128];
            int hash = text.hashCode();
            for (int i = 0; i < 128; i++) {
                vector[i] = (float) Math.sin(hash + i) * 0.1f;
            }
            // 归一化
            float norm = 0;
            for (float v : vector) norm += v * v;
            norm = (float) Math.sqrt(norm);
            for (int i = 0; i < 128; i++) vector[i] /= norm;
            return vector;
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        @Override
        public boolean isAvailable() { return true; }
    }

    static class MockConfig extends com.hermes.agent.config.AgentConfig {
        @Override
        public int getVectorMemoryDimension() { return 128; }

        @Override
        public int getVectorMemoryHnswM() { return 16; }

        @Override
        public int getVectorMemoryHnswEfConstruction() { return 200; }

        @Override
        public int getVectorMemoryHnswEfSearch() { return 50; }

        @Override
        public String getVectorMemoryIndexPath() { 
            return System.getProperty("java.io.tmpdir") + "/hermes_vector_test"; 
        }
    }
}
