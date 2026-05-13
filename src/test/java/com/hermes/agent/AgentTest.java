package com.hermes.agent;

import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.llm.LLMService;
import com.hermes.agent.memory.MemoryManager;
import com.hermes.agent.memory.MemoryOrchestrator;
import com.hermes.agent.memory.SessionSearchService;
import com.hermes.agent.memory.SessionSummaryManager;
import com.hermes.agent.memory.longterm.LongTermMemoryManager;
import com.hermes.agent.memory.longterm.UserProfileExtService;
import com.hermes.agent.model.Message;
import com.hermes.agent.persistence.PersistenceBackendFactory;
import com.hermes.agent.skills.SkillSystem;
import com.hermes.agent.skills.loader.SkillLoader;
import com.hermes.agent.skills.loader.SkillWriter;
import com.hermes.agent.skills.hub.SkillsHubService;
import com.hermes.agent.subagent.SubAgentService;
import com.hermes.agent.tools.ToolRegistry;
import com.hermes.agent.userprofile.UserProfileManager;
import com.hermes.agent.autonomous.SkillGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTest {

    private Agent agent;
    private LLMService llmService;
    private MemoryManager memoryManager;
    private SessionSummaryManager summaryManager;
    private UserProfileManager userProfileManager;
    private SkillSystem skillSystem;
    private ToolRegistry toolRegistry;
    private SubAgentService subAgentService;
    private SkillGenerator skillGenerator;
    private PersistenceBackendFactory backendFactory;
    private MemoryOrchestrator memoryOrchestrator;
    private SessionSearchService sessionSearchService;
    private LongTermMemoryManager longTermMemoryManager;
    private UserProfileExtService userProfileExtService;
    private AgentConfig config;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        config = new AgentConfig();
        
        // 设置技能目录为临时目录
        try {
            var field = AgentConfig.class.getDeclaredField("skills");
            field.setAccessible(true);
            var skillsConfig = new AgentConfig.SkillsConfig();
            skillsConfig.setHome(tempDir.toString());
            field.set(config, skillsConfig);
        } catch (Exception e) {
            // 如果反射失败，使用默认配置
        }
        
        llmService = mock(LLMService.class);
        
        // Mock backend factory with a properly configured backend
        com.hermes.agent.persistence.PersistenceBackend mockBackend = mock(com.hermes.agent.persistence.PersistenceBackend.class);
        when(mockBackend.name()).thenReturn("mock-backend");
        when(mockBackend.isAvailable()).thenReturn(true);
        when(mockBackend.getAllSessionIds()).thenReturn(Collections.emptyList());
        when(mockBackend.getAllUserProfiles()).thenReturn(Collections.emptyMap());
        when(mockBackend.getAllSkillMemories()).thenReturn(Collections.emptyMap());
        doNothing().when(mockBackend).saveSkillMemory(anyString(), anyString());
        
        backendFactory = mock(PersistenceBackendFactory.class);
        when(backendFactory.getDefaultBackend()).thenReturn(mockBackend);
        
        memoryManager = new MemoryManager(backendFactory);
        memoryManager.loadFromBackend();
        
        // 创建 SkillSystem 相关的 mock
        SkillLoader skillLoader = new SkillLoader(config);
        SkillWriter skillWriter = new SkillWriter(skillLoader);
        SkillsHubService hubService = mock(SkillsHubService.class);
        
        skillSystem = new SkillSystem(skillLoader, skillWriter, hubService);
        
        toolRegistry = new ToolRegistry();
        summaryManager = mock(SessionSummaryManager.class);
        userProfileManager = mock(UserProfileManager.class);
        when(userProfileManager.getContextPrompt(anyString())).thenReturn("");
        skillGenerator = mock(SkillGenerator.class);
        subAgentService = mock(SubAgentService.class);
        memoryOrchestrator = mock(MemoryOrchestrator.class);
        when(memoryOrchestrator.prefetchAll(anyString(), anyString())).thenReturn("");
        when(memoryOrchestrator.buildSystemPrompt()).thenReturn("");
        sessionSearchService = mock(SessionSearchService.class);
        longTermMemoryManager = mock(LongTermMemoryManager.class);
        when(longTermMemoryManager.buildPrefetchContext(anyString(), anyString(), anyString())).thenReturn("");
        userProfileExtService = mock(UserProfileExtService.class);
        when(userProfileExtService.buildEnhancedContext(anyString())).thenReturn("");

        agent = new Agent(
                llmService, memoryManager, summaryManager,
                userProfileManager, skillSystem, toolRegistry,
                subAgentService, skillGenerator, config,
                memoryOrchestrator, sessionSearchService,
                longTermMemoryManager, userProfileExtService);
    }

    @Test
    void testChatReturnsResponse() {
        when(llmService.chatWithTools(any(), anyString()))
                .thenReturn(Mono.just(new Message("assistant", "Hello from test!", Instant.now())));

        String result = agent.chat("test-session", "Hello").block();
        assertNotNull(result);
        assertEquals("Hello from test!", result);
    }

    @Test
    void testMultipleSessions() {
        when(llmService.chatWithTools(any(), anyString()))
                .thenReturn(Mono.just(new Message("assistant", "response", Instant.now())));

        agent.chat("session1", "msg1").block();
        agent.chat("session2", "msg2").block();

        assertEquals(2, agent.getActiveSessions().size());
        assertTrue(agent.getActiveSessions().contains("session1"));
        assertTrue(agent.getActiveSessions().contains("session2"));
    }

    @Test
    void testClearSession() {
        when(llmService.chatWithTools(any(), anyString()))
                .thenReturn(Mono.just(new Message("assistant", "response", Instant.now())));

        agent.chat("session1", "msg1").block();
        assertTrue(agent.getActiveSessions().contains("session1"));

        agent.clearSession("session1");
        assertFalse(agent.getActiveSessions().contains("session1"));
    }
}
