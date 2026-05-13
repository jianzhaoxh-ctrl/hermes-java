package com.hermes.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hermes")
public class AgentConfig {
    private String defaultModel = "qwen-plus";
    private String defaultProvider = "dashscope";
    private int maxHistory = 50;
    private int maxTokens = 4096;
    private double temperature = 0.7;
    private boolean streamEnabled = true;
    private String apiBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String apiKey;

    // ─────────────────────────── Agent Loop Configuration ───────────────────────────

    /** Maximum iterations for multi-turn tool calling (default 90, same as Python) */
    private int maxIterations = 90;

    /** Timeout for individual tool execution in milliseconds */
    private long toolTimeoutMs = 35000;

    /** Enable parallel tool execution for safe tools */
    private boolean parallelToolExecution = false;

    /** Quiet mode - suppress verbose output */
    private boolean quietMode = false;

    // ─────────────────────────── Data Configuration ───────────────────────────
    /** 数据持久化目录，默认 {user.home}/.hermes/data */
    private String dataDir;
    private RedisConfig redis = new RedisConfig();
    private MemoryConfig memory = new MemoryConfig();
    private VectorMemoryConfig vectorMemory = new VectorMemoryConfig();
    private SkillsConfig skills = new SkillsConfig();

    /** 内存/持久化配置 */
    public static class MemoryConfig {
        /** 后端类型: sqlite, jsonfile, redis */
        private String backend = "sqlite";
        /** SQLite 数据库路径 */
        private String dbPath;
        /** SQLite 连接池大小 */
        private int poolSize = 5;
        /** 是否启用 FTS5 全文搜索 */
        private boolean ftsEnabled = true;

        public String getBackend() { return backend; }
        public void setBackend(String backend) { this.backend = backend; }
        public String getDbPath() { return dbPath; }
        public void setDbPath(String dbPath) { this.dbPath = dbPath; }
        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
        public boolean isFtsEnabled() { return ftsEnabled; }
        public void setFtsEnabled(boolean ftsEnabled) { this.ftsEnabled = ftsEnabled; }
    }

    /** 向量记忆配置 */
    public static class VectorMemoryConfig {
        /** 是否启用向量记忆 */
        private boolean enabled = true;
        /** Embedding 提供商: dashscope, openai, local */
        private String embeddingProvider = "dashscope";
        /** Embedding 模型 */
        private String embeddingModel = "text-embedding-v3";
        /** 向量维度 */
        private int vectorDimension = 1024;
        /** 相似度阈值 */
        private float similarityThreshold = 0.75f;
        /** 返回结果数 */
        private int topK = 10;
        /** HNSW 索引参数 */
        private HnswConfig hnsw = new HnswConfig();
        /** 持久化路径 */
        private String indexPath;
        /** API Key (优先从环境变量读取) */
        private String apiKey;

        public static class HnswConfig {
            private int m = 16;
            private int efConstruction = 200;
            private int efSearch = 50;

            public int getM() { return m; }
            public void setM(int m) { this.m = m; }
            public int getEfConstruction() { return efConstruction; }
            public void setEfConstruction(int efConstruction) { this.efConstruction = efConstruction; }
            public int getEfSearch() { return efSearch; }
            public void setEfSearch(int efSearch) { this.efSearch = efSearch; }
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEmbeddingProvider() { return embeddingProvider; }
        public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public int getVectorDimension() { return vectorDimension; }
        public void setVectorDimension(int vectorDimension) { this.vectorDimension = vectorDimension; }
        public float getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(float similarityThreshold) { this.similarityThreshold = similarityThreshold; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public HnswConfig getHnsw() { return hnsw; }
        public void setHnsw(HnswConfig hnsw) { this.hnsw = hnsw; }
        public String getIndexPath() { return indexPath; }
        public void setIndexPath(String indexPath) { this.indexPath = indexPath; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    public static class RedisConfig {
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private int database = 0;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(String port) { this.port = Integer.parseInt(port); }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
    }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public int getMaxHistory() { return maxHistory; }
    public void setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public boolean isStreamEnabled() { return streamEnabled; }
    public void setStreamEnabled(boolean streamEnabled) { this.streamEnabled = streamEnabled; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public long getToolTimeoutMs() { return toolTimeoutMs; }
    public void setToolTimeoutMs(long toolTimeoutMs) { this.toolTimeoutMs = toolTimeoutMs; }
    public boolean isParallelToolExecution() { return parallelToolExecution; }
    public void setParallelToolExecution(boolean parallelToolExecution) { this.parallelToolExecution = parallelToolExecution; }
    public boolean isQuietMode() { return quietMode; }
    public void setQuietMode(boolean quietMode) { this.quietMode = quietMode; }
    public RedisConfig getRedis() { return redis; }
    public void setRedis(RedisConfig redis) { this.redis = redis; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String resolveDataDir() {
        if (dataDir != null && !dataDir.isBlank()) return dataDir;
        return System.getProperty("user.home") + "/.hermes/data";
    }

    public String getMemoryBackend() {
        return memory != null ? memory.getBackend() : "sqlite";
    }

    public String getSqlitePath() {
        if (memory != null && memory.getDbPath() != null && !memory.getDbPath().isBlank()) {
            return memory.getDbPath();
        }
        return System.getProperty("user.home") + "/.hermes/hermes.db";
    }

    public int getSqlitePoolSize() {
        return memory != null ? memory.getPoolSize() : 5;
    }

    public MemoryConfig getMemory() { return memory; }
    public void setMemory(MemoryConfig memory) { this.memory = memory; }

    // ═══════════════════════════════════════════════════════════
    //  向量记忆配置
    // ═══════════════════════════════════════════════════════════

    public boolean isVectorMemoryEnabled() {
        return vectorMemory != null && vectorMemory.isEnabled();
    }

    public String getVectorMemoryEmbeddingModel() {
        return vectorMemory != null ? vectorMemory.getEmbeddingModel() : "text-embedding-v3";
    }

    public int getVectorMemoryDimension() {
        return vectorMemory != null ? vectorMemory.getVectorDimension() : 1024;
    }

    public float getVectorMemorySimilarityThreshold() {
        return vectorMemory != null ? vectorMemory.getSimilarityThreshold() : 0.75f;
    }

    public int getVectorMemoryTopK() {
        return vectorMemory != null ? vectorMemory.getTopK() : 10;
    }

    public int getVectorMemoryHnswM() {
        return vectorMemory != null && vectorMemory.getHnsw() != null ? vectorMemory.getHnsw().getM() : 16;
    }

    public int getVectorMemoryHnswEfConstruction() {
        return vectorMemory != null && vectorMemory.getHnsw() != null ? vectorMemory.getHnsw().getEfConstruction() : 200;
    }

    public int getVectorMemoryHnswEfSearch() {
        return vectorMemory != null && vectorMemory.getHnsw() != null ? vectorMemory.getHnsw().getEfSearch() : 50;
    }

    public String getVectorMemoryIndexPath() {
        if (vectorMemory != null && vectorMemory.getIndexPath() != null && !vectorMemory.getIndexPath().isBlank()) {
            return vectorMemory.getIndexPath();
        }
        return System.getProperty("user.home") + "/.hermes/vector_index";
    }

    public String getVectorMemoryApiKey() {
        if (vectorMemory != null && vectorMemory.getApiKey() != null && !vectorMemory.getApiKey().isBlank()) {
            return vectorMemory.getApiKey();
        }
        // 回退到主 API Key 或环境变量
        return apiKey;
    }

    public VectorMemoryConfig getVectorMemory() { return vectorMemory; }
    public void setVectorMemory(VectorMemoryConfig vectorMemory) { this.vectorMemory = vectorMemory; }

    // ═══════════════════════════════════════════════════════════
    //  技能系统配置
    // ═══════════════════════════════════════════════════════════

    /** 技能系统配置 */
    public static class SkillsConfig {
        /** 技能根目录，默认 ~/.hermes/skills */
        private String home;
        
        /** Hub 配置 */
        private HubConfig hub = new HubConfig();
        
        /** 自动生成配置 */
        private AutoGenerateConfig autoGenerate = new AutoGenerateConfig();
        
        public static class HubConfig {
            /** 是否启用 Hub 集成 */
            private boolean enabled = true;
            /** Hub API URL */
            private String apiUrl = "https://api.hermes-agent.com/v1/skills";
            /** Hub 索引 URL */
            private String indexUrl = "https://raw.githubusercontent.com/NousResearch/hermes-agent/main/skills/index.json";
            /** 索引缓存 TTL（毫秒） */
            private long cacheTtlMs = 3600000;  // 1 hour
            
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getApiUrl() { return apiUrl; }
            public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
            public String getIndexUrl() { return indexUrl; }
            public void setIndexUrl(String indexUrl) { this.indexUrl = indexUrl; }
            public long getCacheTtlMs() { return cacheTtlMs; }
            public void setCacheTtlMs(long cacheTtlMs) { this.cacheTtlMs = cacheTtlMs; }
        }
        
        public static class AutoGenerateConfig {
            /** 是否启用自动生成 */
            private boolean enabled = true;
            /** 最小任务重复次数才触发 */
            private int minRepeats = 2;
            /** 每天最大生成数 */
            private int maxPerDay = 5;
            
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public int getMinRepeats() { return minRepeats; }
            public void setMinRepeats(int minRepeats) { this.minRepeats = minRepeats; }
            public int getMaxPerDay() { return maxPerDay; }
            public void setMaxPerDay(int maxPerDay) { this.maxPerDay = maxPerDay; }
        }
        
        public String getHome() { return home; }
        public void setHome(String home) { this.home = home; }
        public HubConfig getHub() { return hub; }
        public void setHub(HubConfig hub) { this.hub = hub; }
        public AutoGenerateConfig getAutoGenerate() { return autoGenerate; }
        public void setAutoGenerate(AutoGenerateConfig autoGenerate) { this.autoGenerate = autoGenerate; }
    }

    /** 获取技能根目录 */
    public String getSkillsHome() {
        if (skills != null && skills.getHome() != null && !skills.getHome().isBlank()) {
            return skills.getHome();
        }
        return System.getProperty("user.home") + "/.hermes/skills";
    }

    public SkillsConfig getSkills() { return skills; }
    public void setSkills(SkillsConfig skills) { this.skills = skills; }
}
