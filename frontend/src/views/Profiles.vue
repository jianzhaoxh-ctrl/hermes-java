<template>
  <div class="profiles-page">
    <header class="page-header">
      <h1>👤 用户画像</h1>
      <div class="header-actions">
        <input v-model="searchSession" class="search-input" placeholder="搜索会话..." @input="onSearch" />
        <button class="btn-primary" @click="refreshAll" :disabled="loading">
          {{ loading ? '加载中...' : '🔄 刷新' }}
        </button>
      </div>
    </header>

    <div class="profiles-layout">
      <!-- 左侧：会话列表 -->
      <aside class="session-list">
        <h3>会话列表</h3>
        <div v-if="sessions.length === 0" class="empty-state">暂无会话</div>
        <div
          v-for="sid in filteredSessions"
          :key="sid"
          class="session-item"
          :class="{ active: sid === currentSessionId }"
          @click="selectSession(sid)"
        >
          <span class="session-name">{{ sid }}</span>
          <span v-if="sid === currentSessionId" class="active-badge">✓</span>
        </div>
      </aside>

      <!-- 右侧：画像详情 -->
      <main class="profile-detail">
        <div v-if="!currentSessionId" class="empty-state">
          <p>👈 请从左侧选择一个会话</p>
        </div>

        <template v-else>
          <!-- 系统状态卡片 -->
          <section class="status-cards">
            <div class="status-card">
              <span class="card-label">观察数</span>
              <span class="card-value">{{ profile?.observation_count ?? '-' }}</span>
            </div>
            <div class="status-card">
              <span class="card-label">结论数</span>
              <span class="card-value">{{ profile?.conclusion_count ?? '-' }}</span>
            </div>
            <div class="status-card">
              <span class="card-label">画像版本</span>
              <span class="card-value">{{ profile?.card_version ?? '-' }}</span>
            </div>
            <div class="status-card" :class="{ green: liveness?.dialectic_active }">
              <span class="card-label">辩证引擎</span>
              <span class="card-value">{{ liveness?.dialectic_active ? '🟢 活跃' : '⚪ 待机' }}</span>
            </div>
          </section>

          <!-- Tab 切换 -->
          <div class="tab-nav">
            <button :class="{ active: activeTab === 'card' }" @click="activeTab = 'card'">📋 Card</button>
            <button :class="{ active: activeTab === 'conclusions' }" @click="activeTab = 'conclusions'">
              💡 Conclusions ({{ conclusions.length }})
            </button>
            <button :class="{ active: activeTab === 'context' }" @click="activeTab = 'context'">📝 Context</button>
            <button :class="{ active: activeTab === 'liveness' }" @click="activeTab = 'liveness'">📊 Liveness</button>
          </div>

          <!-- Tab: Card -->
          <div v-if="activeTab === 'card'" class="tab-content">
            <div class="section-header">
              <h3>Representation（AI对用户的理解）</h3>
              <button class="btn-secondary" @click="extractProfile(false)" :disabled="extracting">
                {{ extracting ? '提取中...' : '🔄 重新提取' }}
              </button>
            </div>
            <div class="representation-box">
              {{ profile?.representation || '暂无 representation' }}
            </div>

            <h3>Card Facts</h3>
            <div v-if="!profile?.card || Object.keys(profile.card).length === 0" class="empty-state">
              暂无 Card Facts
            </div>
            <div v-else class="card-facts">
              <div v-for="(value, key) in profile.card" :key="key" class="fact-item">
                <span class="fact-key">{{ key }}</span>
                <span class="fact-value">{{ typeof value === 'object' ? JSON.stringify(value) : value }}</span>
              </div>
            </div>

            <!-- Seed -->
            <div class="seed-section">
              <h3>🔧 Seed（预设身份）</h3>
              <div class="seed-form">
                <select v-model="seedType" class="seed-select">
                  <option value="user">User</option>
                  <option value="ai">AI</option>
                </select>
                <textarea v-model="seedContent" class="seed-textarea" placeholder="输入预设身份内容..." rows="3" />
                <button class="btn-primary" @click="submitSeed" :disabled="!seedContent.trim()">💾 保存 Seed</button>
              </div>
            </div>
          </div>

          <!-- Tab: Conclusions -->
          <div v-if="activeTab === 'conclusions'" class="tab-content">
            <div class="add-conclusion">
              <input v-model="newFact" class="conclusion-input" placeholder="添加新结论..." @keyup.enter="addConclusion" />
              <button class="btn-primary" @click="addConclusion" :disabled="!newFact.trim()">➕ 添加</button>
            </div>
            <div v-if="conclusions.length === 0" class="empty-state">暂无结论</div>
            <div v-else class="conclusions-list">
              <div v-for="c in conclusions" :key="c.id" class="conclusion-item">
                <div class="conclusion-main">
                  <span class="conclusion-fact">💡 {{ c.fact }}</span>
                  <button class="btn-delete" @click="removeConclusion(c.id)" title="删除">🗑️</button>
                </div>
                <div class="conclusion-meta">
                  来源: {{ c.source }} · {{ formatTime(c.timestamp) }}
                </div>
              </div>
            </div>
          </div>

          <!-- Tab: Context -->
          <div v-if="activeTab === 'context'" class="tab-content">
            <div class="context-controls">
              <label>上下文长度: <input v-model.number="contextLimit" type="number" min="500" max="10000" step="500" class="num-input" @change="loadContext" /></label>
              <button class="btn-secondary" @click="loadContext">🔄 刷新</button>
            </div>
            <div class="context-box">
              {{ profileContext || '加载中...' }}
            </div>
          </div>

          <!-- Tab: Liveness -->
          <div v-if="activeTab === 'liveness'" class="tab-content">
            <div v-if="liveness" class="liveness-grid">
              <div class="live-item"><span>会话ID</span><span>{{ liveness.session_id }}</span></div>
              <div class="live-item"><span>观察数</span><span>{{ liveness.observation_count }}</span></div>
              <div class="live-item"><span>结论数</span><span>{{ liveness.conclusion_count }}</span></div>
              <div class="live-item"><span>最后观察</span><span>{{ formatTime(liveness.last_observation) }}</span></div>
              <div class="live-item"><span>最后结论</span><span>{{ formatTime(liveness.last_conclusion) }}</span></div>
              <div class="live-item"><span>上下文字符</span><span>{{ liveness.context_chars }}</span></div>
              <div class="live-item"><span>辩证活跃</span><span>{{ liveness.dialectic_active ? '🟢 是' : '⚪ 否' }}</span></div>
            </div>
            <div v-else class="empty-state">暂无 Liveness 数据</div>
          </div>

          <!-- 语义搜索 -->
          <div class="semantic-search">
            <h3>🔍 语义搜索（向量记忆）</h3>
            <div class="search-row">
              <input v-model="searchQuery" class="search-input" placeholder="用自然语言搜索记忆..." @keyup.enter="doSemanticSearch" />
              <button class="btn-primary" @click="doSemanticSearch" :disabled="!searchQuery.trim() || searching">
                {{ searching ? '搜索中...' : '🔍 搜索' }}
              </button>
            </div>
            <div v-if="searchResult" class="search-result">{{ searchResult }}</div>
          </div>
        </template>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  getSessions,
  initHonchoSession,
  getHonchoProfile,
  getHonchoContext,
  getHonchoConclusions,
  addHonchoConclusion,
  deleteHonchoConclusion,
  seedHonchoIdentity,
  extractHonchoProfile,
  getHonchoLiveness,
  honchoSearch,
  type HonchoProfile,
  type HonchoConclusion,
  type HonchoLiveness,
} from '../api'

// State
const sessions = ref<string[]>([])
const currentSessionId = ref<string | null>(null)
const searchSession = ref('')
const loading = ref(false)
const extracting = ref(false)
const activeTab = ref('card')

// Profile
const profile = ref<HonchoProfile | null>(null)
const profileContext = ref('')
const contextLimit = ref(2000)
const conclusions = ref<HonchoConclusion[]>([])
const liveness = ref<HonchoLiveness | null>(null)

// Form
const newFact = ref('')
const seedType = ref<'user' | 'ai'>('user')
const seedContent = ref('')

// Search
const searchQuery = ref('')
const searchResult = ref('')
const searching = ref(false)

// Computed
const filteredSessions = computed(() => {
  if (!searchSession.value) return sessions.value
  const q = searchSession.value.toLowerCase()
  return sessions.value.filter(sid => sid.toLowerCase().includes(q))
})

// Methods
async function refreshAll() {
  loading.value = true
  try {
    const data = await getSessions()
    sessions.value = data.activeSessions || []
    if (sessions.value.length > 0 && !currentSessionId.value) {
      selectSession(sessions.value[0])
    }
  } catch (e) {
    console.error('Failed to load sessions:', e)
  } finally {
    loading.value = false
  }
}

async function selectSession(sid: string) {
  currentSessionId.value = sid
  activeTab.value = 'card'
  searchResult.value = ''
  await initAndLoad(sid)
}

async function initAndLoad(sid: string) {
  // 初始化会话
  await initHonchoSession(sid).catch(() => {})
  // 加载画像
  profile.value = await getHonchoProfile(sid).catch(() => null)
  // 加载结论
  const cData = await getHonchoConclusions(sid).catch(() => ({ conclusions: [] }))
  conclusions.value = cData.conclusions || []
  // 加载 liveness
  liveness.value = await getHonchoLiveness(sid).catch(() => null)
}

async function loadContext() {
  if (!currentSessionId.value) return
  profileContext.value = '加载中...'
  const data = await getHonchoContext(currentSessionId.value, contextLimit.value).catch(() => ({ context: '加载失败' }))
  profileContext.value = data.context || '无上下文'
}

async function addConclusion() {
  if (!currentSessionId.value || !newFact.value.trim()) return
  await addHonchoConclusion(currentSessionId.value, newFact.value.trim())
  newFact.value = ''
  const cData = await getHonchoConclusions(currentSessionId.value).catch(() => ({ conclusions: [] }))
  conclusions.value = cData.conclusions || []
}

async function removeConclusion(id: string) {
  if (!currentSessionId.value) return
  await deleteHonchoConclusion(currentSessionId.value, id)
  const cData = await getHonchoConclusions(currentSessionId.value).catch(() => ({ conclusions: [] }))
  conclusions.value = cData.conclusions || []
}

async function submitSeed() {
  if (!currentSessionId.value || !seedContent.value.trim()) return
  await seedHonchoIdentity(currentSessionId.value, seedType.value, seedContent.value.trim())
  seedContent.value = ''
  alert('Seed 保存成功')
}

async function extractProfile(deep: boolean) {
  if (!currentSessionId.value) return
  extracting.value = true
  try {
    await extractHonchoProfile(currentSessionId.value, deep)
    await initAndLoad(currentSessionId.value)
  } finally {
    extracting.value = false
  }
}

async function doSemanticSearch() {
  if (!currentSessionId.value || !searchQuery.value.trim()) return
  searching.value = true
  searchResult.value = ''
  try {
    const data = await honchoSearch(searchQuery.value.trim(), currentSessionId.value)
    searchResult.value = data.result || '无结果'
  } catch (e) {
    searchResult.value = '搜索失败: ' + String(e)
  } finally {
    searching.value = false
  }
}

function onSearch() {}

function formatTime(ts: number): string {
  if (!ts || ts === 0) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

onMounted(() => {
  refreshAll()
})
</script>

<style scoped>
.profiles-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 1rem;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-header h1 {
  font-size: 1.3rem;
  color: var(--text-primary, #fff);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 0.5rem;
}

.profiles-layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 1rem;
  flex: 1;
  overflow: hidden;
}

/* Session list */
.session-list {
  background: var(--bg-secondary, #0d1117);
  border-radius: 8px;
  padding: 0.75rem;
  overflow-y: auto;
}

.session-list h3 {
  font-size: 0.85rem;
  color: var(--text-secondary, #8b949e);
  margin: 0 0 0.5rem 0;
}

.session-item {
  padding: 0.5rem 0.6rem;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.85rem;
  color: var(--text-primary, #fff);
}

.session-item:hover {
  background: var(--bg-hover, #161b22);
}

.session-item.active {
  background: var(--accent, #3ddc97);
  color: #000;
}

.active-badge {
  font-size: 0.75rem;
}

/* Profile detail */
.profile-detail {
  background: var(--bg-secondary, #0d1117);
  border-radius: 8px;
  padding: 1rem;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

/* Status cards */
.status-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 0.5rem;
}

.status-card {
  background: var(--bg-tertiary, #161b22);
  border-radius: 8px;
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.status-card.green {
  border: 1px solid var(--accent, #3ddc97);
}

.card-label {
  font-size: 0.75rem;
  color: var(--text-secondary, #8b949e);
}

.card-value {
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text-primary, #fff);
}

/* Tabs */
.tab-nav {
  display: flex;
  gap: 0.25rem;
  border-bottom: 1px solid var(--border-color, #30363d);
  padding-bottom: 0.5rem;
}

.tab-nav button {
  background: none;
  border: none;
  padding: 0.4rem 0.75rem;
  border-radius: 6px;
  cursor: pointer;
  color: var(--text-secondary, #8b949e);
  font-size: 0.85rem;
}

.tab-nav button:hover {
  background: var(--bg-hover, #161b22);
}

.tab-nav button.active {
  background: var(--accent, #3ddc97);
  color: #000;
  font-weight: 600;
}

.tab-content {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

h3 {
  font-size: 0.9rem;
  color: var(--text-secondary, #8b949e);
  margin: 0;
}

.representation-box,
.context-box {
  background: var(--bg-tertiary, #161b22);
  border-radius: 8px;
  padding: 0.75rem;
  font-size: 0.85rem;
  color: var(--text-primary, #fff);
  white-space: pre-wrap;
  line-height: 1.6;
}

.card-facts {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.fact-item {
  background: var(--bg-tertiary, #161b22);
  border-radius: 6px;
  padding: 0.5rem 0.75rem;
  display: grid;
  grid-template-columns: 140px 1fr;
  gap: 0.5rem;
  font-size: 0.85rem;
}

.fact-key {
  color: var(--accent, #3ddc97);
  font-weight: 600;
}

.fact-value {
  color: var(--text-primary, #fff);
}

/* Seed */
.seed-section {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.seed-form {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.seed-select,
.conclusion-input,
.search-input,
.num-input {
  background: var(--bg-tertiary, #161b22);
  border: 1px solid var(--border-color, #30363d);
  border-radius: 6px;
  padding: 0.5rem;
  color: var(--text-primary, #fff);
  font-size: 0.85rem;
}

.seed-textarea {
  background: var(--bg-tertiary, #161b22);
  border: 1px solid var(--border-color, #30363d);
  border-radius: 6px;
  padding: 0.5rem;
  color: var(--text-primary, #fff);
  font-size: 0.85rem;
  resize: vertical;
}

/* Conclusions */
.add-conclusion {
  display: flex;
  gap: 0.5rem;
}

.conclusion-item {
  background: var(--bg-tertiary, #161b22);
  border-radius: 6px;
  padding: 0.6rem 0.75rem;
}

.conclusion-main {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 0.5rem;
}

.conclusion-fact {
  font-size: 0.85rem;
  color: var(--text-primary, #fff);
  flex: 1;
}

.btn-delete {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 0.8rem;
  opacity: 0.5;
}

.btn-delete:hover {
  opacity: 1;
}

.conclusion-meta {
  font-size: 0.75rem;
  color: var(--text-secondary, #8b949e);
  margin-top: 0.25rem;
}

/* Context */
.context-controls {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
  color: var(--text-secondary, #8b949e);
}

/* Liveness */
.liveness-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 0.5rem;
}

.live-item {
  background: var(--bg-tertiary, #161b22);
  border-radius: 6px;
  padding: 0.6rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.8rem;
}

.live-item span:first-child {
  color: var(--text-secondary, #8b949e);
}

.live-item span:last-child {
  color: var(--text-primary, #fff);
  font-weight: 600;
}

/* Semantic search */
.semantic-search {
  border-top: 1px solid var(--border-color, #30363d);
  padding-top: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.search-row {
  display: flex;
  gap: 0.5rem;
}

.search-result {
  background: var(--bg-tertiary, #161b22);
  border-radius: 8px;
  padding: 0.75rem;
  font-size: 0.85rem;
  color: var(--text-primary, #fff);
  white-space: pre-wrap;
  line-height: 1.6;
}

/* Common */
.btn-primary {
  background: var(--accent, #3ddc97);
  color: #000;
  border: none;
  border-radius: 6px;
  padding: 0.5rem 1rem;
  cursor: pointer;
  font-size: 0.85rem;
  font-weight: 600;
  white-space: nowrap;
}

.btn-primary:hover {
  opacity: 0.9;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-secondary {
  background: var(--bg-tertiary, #161b22);
  color: var(--text-primary, #fff);
  border: 1px solid var(--border-color, #30363d);
  border-radius: 6px;
  padding: 0.5rem 1rem;
  cursor: pointer;
  font-size: 0.85rem;
}

.btn-secondary:hover {
  background: var(--bg-hover, #161b22);
}

.btn-secondary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.empty-state {
  text-align: center;
  color: var(--text-secondary, #8b949e);
  padding: 2rem;
  font-size: 0.9rem;
}
</style>
