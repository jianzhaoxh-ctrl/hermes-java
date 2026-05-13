<template>
  <div class="memory-view">

    <!-- Header: session selector + auto-refresh indicator -->
    <div class="mem-header">
      <div class="mem-session-bar">
        <label class="form-label" style="margin:0;font-size:11px">当前会话：</label>
        <select class="session-select" v-model="selectedSession" @change="onSessionChange">
          <option v-for="s in sessions" :key="s" :value="s">{{ s }}</option>
        </select>
        <input
          v-if="selectedSession === '__custom__'"
          class="form-input"
          style="width:160px"
          v-model="customSession"
          placeholder="输入会话 ID..."
          @keydown.enter="switchToCustom"
        />
        <button v-if="selectedSession === '__custom__'" class="btn btn-ghost btn-sm" @click="switchToCustom">切换</button>
        <span v-if="selectedSession !== '__custom__'" class="mem-auto-tag">自动同步</span>
        <span class="mem-auto-indicator" :class="{on: autoRefresh}" title="自动刷新中">
          <span class="dot-anim"/>
        </span>
      </div>
      <div class="mem-header-right">
        <span class="mem-hint">数据持久化：{{ stats?.persistenceEnabled ? '已启用' : '未配置' }}</span>
        <span class="mem-hint" v-if="stats?.dataDir">路径：{{ stats.dataDir }}</span>
        <button class="btn btn-ghost btn-sm" @click="refreshAll">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
          刷新
        </button>
      </div>
    </div>

    <!-- Tab Bar -->
    <div class="mem-tabs">
      <button class="mem-tab" :class="{active: tab==='stats'}" @click="tab='stats'; loadStats()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 8v4l3 3"/></svg>
        总览
      </button>
      <button class="mem-tab" :class="{active: tab==='summary'}" @click="tab='summary'; loadSummaries()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        会话摘要
      </button>
      <button class="mem-tab" :class="{active: tab==='profile'}" @click="tab='profile'; loadProfiles()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
        用户画像
      </button>
      <button class="mem-tab" :class="{active: tab==='skills'}" @click="tab='skills'; loadSkills()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
        技能库
      </button>
      <button class="mem-tab" :class="{active: tab==='curated'}" @click="tab='curated'; loadCurated()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
        持久记忆
      </button>
      <button class="mem-tab" :class="{active: tab==='search'}" @click="tab='search'">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        会话搜索
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="loading-row">
      <div class="spinner"/>
      <span>加载中…</span>
    </div>

    <!-- ════════════════ Tab: 总览 ════════════════ -->
    <div v-if="tab==='stats' && !loading" class="mem-content">

      <!-- 当前选中会话的快照 -->
      <div class="mem-current-session-card" v-if="currentSessionSummary">
        <div class="mem-current-title">当前会话 · {{ selectedSession }}</div>
        <div class="mem-current-grid">
          <div class="mem-current-item">
            <div class="mem-current-label">消息数</div>
            <div class="mem-current-value">{{ currentSessionSummary.messageCount }}</div>
          </div>
          <div class="mem-current-item">
            <div class="mem-current-label">摘要次数</div>
            <div class="mem-current-value">{{ currentSessionSummary.summaryCount }}</div>
          </div>
          <div class="mem-current-item">
            <div class="mem-current-label">是否已摘要</div>
            <div class="mem-current-value" :style="{color: currentSessionSummary.summaryCount>0?'var(--accent)':'var(--text-muted)'}">
              {{ currentSessionSummary.summaryCount > 0 ? '已压缩' : '未压缩' }}
            </div>
          </div>
          <div class="mem-current-item">
            <div class="mem-current-label">触发阈值</div>
            <div class="mem-current-value">{{ currentSessionSummary.threshold }} 条</div>
          </div>
        </div>
        <div v-if="currentSessionSummary.lastSummary" class="mem-current-summary">
          <span class="mem-current-summary-label">最近摘要：</span>{{ currentSessionSummary.lastSummary }}
        </div>
        <div v-else class="mem-current-summary empty">
          暂无摘要内容（对话数不足或尚未触发摘要）
        </div>
      </div>

      <!-- 全局统计 -->
      <div class="mem-grid-3">
        <div class="mem-card">
          <div class="mem-card-label">历史会话总数</div>
          <div class="mem-card-value accent">{{ stats?.activeSessions ?? 0 }}</div>
          <div class="mem-card-sub">含持久化会话</div>
        </div>
        <div class="mem-card">
          <div class="mem-card-label">历史摘要次数</div>
          <div class="mem-card-value amber">{{ stats?.totalSummaries ?? 0 }}</div>
          <div class="mem-card-sub">节省约 {{ Math.round((stats?.totalSummaries ?? 0) * 0.7 * 50) }} 条消息</div>
        </div>
        <div class="mem-card">
          <div class="mem-card-label">已注册技能</div>
          <div class="mem-card-value blue">{{ stats?.totalSkills ?? 0 }}</div>
          <div class="mem-card-sub">其中 {{ stats?.autoSkills ?? 0 }} 个自动生成</div>
        </div>
      </div>

      <!-- 三大机制 -->
      <div class="mem-section-title">记忆机制</div>
      <div class="mem-mechanisms">
        <div v-for="m in (stats?.memoryTypes || [])" :key="m.type" class="mem-mech-item">
          <div class="mem-mech-header">
            <span class="mem-mech-dot enabled"/>
            <span class="mem-mech-name">{{ m.type }}</span>
            <span class="badge badge-active">已启用</span>
          </div>
          <div class="mem-mech-desc">{{ m.description }}</div>
        </div>
      </div>

      <!-- 记忆 Provider -->
      <div class="mem-section-title">已注册记忆 Provider</div>
      <div class="mem-mechanisms">
        <div v-for="p in (stats?.memoryProviders || [])" :key="p.name" class="mem-mech-item">
          <div class="mem-mech-header">
            <span class="mem-mech-dot" :class="{enabled: p.available}"/>
            <span class="mem-mech-name">{{ p.name }}</span>
            <span class="badge" :class="p.available ? 'badge-active' : 'badge-pending'">
              {{ p.available ? '可用' : '不可用' }}
            </span>
          </div>
        </div>
      </div>

      <!-- 搜索索引统计 -->
      <div v-if="stats?.searchIndex" class="mem-section-title">搜索索引</div>
      <div v-if="stats?.searchIndex" class="mem-grid-3">
        <div class="mem-card">
          <div class="mem-card-label">已索引文档</div>
          <div class="mem-card-value blue">{{ stats.searchIndex.totalDocuments }}</div>
        </div>
        <div class="mem-card">
          <div class="mem-card-label">唯一 Token</div>
          <div class="mem-card-value accent">{{ stats.searchIndex.uniqueTokens }}</div>
        </div>
        <div class="mem-card">
          <div class="mem-card-label">已索引会话</div>
          <div class="mem-card-value amber">{{ stats.searchIndex.indexedSessions }}</div>
        </div>
      </div>

      <!-- 技能列表 -->
      <div class="mem-section-title">已注册技能</div>
      <div class="mem-tags" v-if="(stats?.registeredSkills || []).length">
        <span v-for="s in (stats?.registeredSkills || [])" :key="s" class="mem-tag">{{ s }}</span>
      </div>
      <div class="mem-empty-hint" v-else>暂无已注册技能（需同类任务出现3次以上才会自动生成）</div>
    </div>

    <!-- ════════════════ Tab: 会话摘要 ════════════════ -->
    <div v-if="tab==='summary' && !loading" class="mem-content">
      <div class="mem-toolbar">
        <div class="mem-toolbar-left">
          <div class="mem-pill">{{ summaryList.length }} 个会话</div>
          <div class="mem-pill warn" v-if="summaryList.some(s=>s.needsSummary)">
            ⚠ {{ summaryList.filter(s=>s.needsSummary).length }} 个即将触发摘要
          </div>
        </div>
        <button class="btn btn-ghost btn-sm" @click="loadSummaries()">刷新</button>
      </div>

      <div class="mem-list">
        <div v-if="!summaryList.length" class="mem-empty">暂无会话数据，开始对话即可触发摘要</div>
        <div v-for="s in summaryList" :key="s.sessionId" class="mem-item" :class="{highlighted: s.sessionId===selectedSession}">
          <div class="mem-item-header">
            <span class="mem-item-id">{{ s.sessionId }}</span>
            <div class="mem-item-badges">
              <span class="badge">{{ s.messageCount }} 条消息</span>
              <span class="badge badge-active" v-if="s.summaryCount > 0">已压缩 {{ s.summaryCount }} 次</span>
              <span class="badge badge-pending" v-if="s.needsSummary">即将摘要</span>
            </div>
          </div>
          <div v-if="s.lastSummary" class="mem-item-summary">{{ s.lastSummary }}</div>
          <div class="mem-item-footer">
            <span class="mem-item-meta">阈值: {{ s.threshold }} 条</span>
            <button class="btn btn-ghost btn-xs" @click="viewDetail(s)">查看详情</button>
          </div>
        </div>
      </div>
    </div>

    <!-- ════════════════ Tab: 用户画像 ════════════════ -->
    <div v-if="tab==='profile' && !loading" class="mem-content">
      <div class="mem-toolbar">
        <div class="mem-toolbar-left">
          <div class="mem-pill">{{ profiles.length }} 个画像</div>
          <div class="mem-pill accent">{{ profiles.filter(p=>p.known).length }} 个已识别</div>
        </div>
        <button class="btn btn-ghost btn-sm" @click="loadProfiles()">刷新</button>
      </div>

      <div class="mem-list">
        <div v-if="!profiles.length" class="mem-empty">暂无画像数据，开始对话后自动提取</div>
        <div v-for="p in profiles" :key="p.userId" class="mem-item" :class="{highlighted: p.userId===selectedSession}">
          <div class="mem-item-header">
            <span class="mem-item-id">{{ p.userId }}</span>
            <div class="mem-item-badges">
              <span class="badge">{{ p.conversationCount }} 轮对话</span>
              <span class="badge badge-active" v-if="p.known">已识别</span>
              <span class="badge badge-pending" v-else>未建立</span>
            </div>
          </div>
          <div v-if="p.summary" class="mem-profile-summary">{{ p.summary }}</div>
          <div v-if="p.topics?.length" class="mem-topics">
            <span class="mem-topics-label">话题：</span>
            <span v-for="(t,i) in p.topics" :key="i" class="mem-topic-chip">{{ t }}</span>
          </div>
          <div class="mem-item-footer">
            <span class="mem-item-meta">
              更新: {{ p.lastUpdated ? formatTime(p.lastUpdated) : '—' }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- ════════════════ Tab: 技能库 ════════════════ -->
    <div v-if="tab==='skills' && !loading" class="mem-content">
      <div class="mem-toolbar">
        <div class="mem-toolbar-left">
          <div class="mem-pill">{{ skillList.length }} 个技能</div>
          <div class="mem-pill accent" v-if="autoSkillCount > 0">{{ autoSkillCount }} 个自动生成</div>
        </div>
        <div class="mem-toolbar-right">
          <input class="form-input" style="width:160px" v-model="skillSearch" placeholder="搜索技能…" @input="loadSkills()"/>
        </div>
      </div>

      <div class="mem-list">
        <div v-if="!skillList.length" class="mem-empty">
          {{ skillSearch ? '无匹配结果' : '暂无技能（同类任务出现3次以上才会自动生成）' }}
        </div>
        <div v-for="s in skillList" :key="s.name" class="mem-item">
          <div class="mem-item-header">
            <span class="mem-item-name">{{ s.name }}</span>
            <div class="mem-item-badges">
              <span class="badge badge-active" v-if="s.autoGenerated">自动生成</span>
              <span class="badge" v-if="s.category">{{ s.category }}</span>
            </div>
          </div>
          <div class="mem-item-desc">{{ s.description }}</div>
          <div v-if="s.steps?.length" class="mem-steps">
            <div v-for="(step,i) in s.steps" :key="i" class="mem-step-item">
              <span class="mem-step-num">{{ i+1 }}</span>
              <span>{{ step }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ════════════════ Tab: 持久记忆（MEMORY.md / USER.md） ════════════════ -->
    <div v-if="tab==='curated' && !loading" class="mem-content">
      <div class="mem-toolbar">
        <div class="mem-toolbar-left">
          <div class="mem-pill">MEMORY.md · USER.md</div>
          <div class="mem-pill accent">跨会话持久化</div>
        </div>
        <div class="mem-toolbar-right">
          <button class="btn btn-ghost btn-sm" @click="loadCurated()">刷新</button>
        </div>
      </div>

      <!-- MEMORY.md -->
      <div class="mem-curated-section">
        <div class="mem-curated-header">
          <span class="mem-curated-title">🧠 MEMORY — Agent 个人笔记</span>
          <span class="mem-curated-usage">{{ memoryEntries.usage }}</span>
        </div>
        <div class="mem-curated-desc">环境事实、项目约定、工具用法、经验教训。会话间持久保存，系统提示注入。</div>
        <div v-if="memoryEntries.entries?.length" class="mem-curated-entries">
          <div v-for="(e, i) in memoryEntries.entries" :key="i" class="mem-curated-entry">
            <span class="mem-curated-idx">{{ i + 1 }}</span>
            <span class="mem-curated-text">{{ e }}</span>
            <button class="btn btn-ghost btn-xs" @click="removeCuratedEntry('memory', e)" title="删除">×</button>
          </div>
        </div>
        <div v-else class="mem-empty">暂无记忆条目（Agent 会主动保存重要信息）</div>
        <div class="mem-curated-add">
          <input class="form-input" v-model="newMemoryEntry" placeholder="添加新记忆条目…" @keydown.enter="addCuratedEntry('memory')"/>
          <button class="btn btn-accent btn-sm" @click="addCuratedEntry('memory')">添加</button>
        </div>
      </div>

      <!-- USER.md -->
      <div class="mem-curated-section">
        <div class="mem-curated-header">
          <span class="mem-curated-title">👤 USER — 用户画像</span>
          <span class="mem-curated-usage">{{ userEntries.usage }}</span>
        </div>
        <div class="mem-curated-desc">用户姓名、偏好、沟通风格、工作习惯。会话间持久保存，系统提示注入。</div>
        <div v-if="userEntries.entries?.length" class="mem-curated-entries">
          <div v-for="(e, i) in userEntries.entries" :key="i" class="mem-curated-entry">
            <span class="mem-curated-idx">{{ i + 1 }}</span>
            <span class="mem-curated-text">{{ e }}</span>
            <button class="btn btn-ghost btn-xs" @click="removeCuratedEntry('user', e)" title="删除">×</button>
          </div>
        </div>
        <div v-else class="mem-empty">暂无用户画像条目（Agent 会主动保存用户偏好）</div>
        <div class="mem-curated-add">
          <input class="form-input" v-model="newUserEntry" placeholder="添加用户画像条目…" @keydown.enter="addCuratedEntry('user')"/>
          <button class="btn btn-accent btn-sm" @click="addCuratedEntry('user')">添加</button>
        </div>
      </div>
    </div>

    <!-- ════════════════ Tab: 会话搜索 ════════════════ -->
    <div v-if="tab==='search' && !loading" class="mem-content">
      <div class="mem-toolbar">
        <div class="mem-toolbar-left">
          <div class="mem-pill">全文搜索</div>
          <div class="mem-pill accent" v-if="searchStats">{{ searchStats.totalDocuments }} 篇文档已索引</div>
        </div>
        <div class="mem-toolbar-right">
          <select class="session-select" v-model="searchSessionFilter" style="width:140px">
            <option value="">所有会话</option>
            <option v-for="s in sessions" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
      </div>

      <div class="mem-search-bar">
        <input class="form-input" style="flex:1" v-model="searchQuery" placeholder="搜索历史对话（支持中文/英文关键词）…" @keydown.enter="doSearch()"/>
        <button class="btn btn-accent btn-sm" @click="doSearch()">搜索</button>
      </div>

      <div v-if="searchResults.length" class="mem-search-info">
        找到 {{ searchResults.length }} 条结果
      </div>

      <div class="mem-list">
        <div v-if="searchQuery && !searchResults.length && searchDone" class="mem-empty">未找到相关结果</div>
        <div v-for="(r, i) in searchResults" :key="i" class="mem-item">
          <div class="mem-item-header">
            <span class="mem-item-name">{{ r.role === 'user' ? '👤 用户' : '🤖 助手' }}</span>
            <div class="mem-item-badges">
              <span class="badge">{{ r.sessionId }}</span>
              <span class="badge badge-active">相关度 {{ r.relevanceScore }}</span>
            </div>
          </div>
          <div class="mem-item-summary">{{ r.content }}</div>
        </div>
      </div>

      <div v-if="!searchQuery" class="mem-search-hint">
        <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="rgba(255,255,255,0.15)" stroke-width="1.5"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <div>输入关键词搜索所有历史对话</div>
        <div style="font-size:11px;color:rgba(255,255,255,0.25)">支持中英文混合查询，结果按相关度排序</div>
      </div>
    </div>

    <!-- Detail Modal -->
    <div v-if="detailItem" class="modal-overlay" @click.self="detailItem=null">
      <div class="modal" style="width:600px">
        <div class="modal-header">
          <div class="modal-title">会话详情: {{ detailItem.sessionId }}</div>
          <button class="modal-close" @click="detailItem=null">×</button>
        </div>
        <div class="modal-body">
          <div class="detail-row"><span>消息数</span><span>{{ detailItem.messageCount }}</span></div>
          <div class="detail-row"><span>压缩次数</span><span>{{ detailItem.summaryCount }}</span></div>
          <div class="detail-row"><span>触发阈值</span><span>{{ detailItem.threshold }} 条</span></div>
          <div class="detail-row"><span>是否即将摘要</span><span>{{ detailItem.needsSummary ? '是' : '否' }}</span></div>
          <div class="detail-row full"><span>最近摘要内容</span></div>
          <div class="detail-summary">{{ detailItem.lastSummary || '暂无' }}</div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost btn-sm" @click="detailItem=null">关闭</button>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import * as api from '../api'
import type { SummaryStatus, UserProfile, Skill, MemoryStats, CuratedMemoryEntry, SearchResult } from '../api'
import { sessionStore } from '../sessionStore'

const props = defineProps<{
  sessions: string[]
  currentSessionId: string
}>()

const tab = ref('stats')
const loading = ref(false)
const autoRefresh = ref(true)

const stats = ref<MemoryStats | null>(null)
const summaryList = ref<SummaryStatus[]>([])
const profiles = ref<UserProfile[]>([])
const skillList = ref<Skill[]>([])
const skillSearch = ref('')
const detailItem = ref<SummaryStatus | null>(null)

const selectedSession = ref('default')
const customSession = ref('')

// ── 持久记忆状态 ──
const memoryEntries = ref<CuratedMemoryEntry>({ success: true, target: 'memory', entries: [], usage: '0/2200 chars', entry_count: 0 })
const userEntries = ref<CuratedMemoryEntry>({ success: true, target: 'user', entries: [], usage: '0/1375 chars', entry_count: 0 })
const newMemoryEntry = ref('')
const newUserEntry = ref('')

// ── 搜索状态 ──
const searchQuery = ref('')
const searchResults = ref<SearchResult[]>([])
const searchDone = ref(false)
const searchSessionFilter = ref('')
const searchStats = ref<{ totalDocuments: number; uniqueTokens: number; indexedSessions: number } | null>(null)

const autoSkillCount = computed(() => skillList.value.filter(s => s.autoGenerated).length)

// 当前选中会话的摘要快照
const currentSessionSummary = computed(() =>
  summaryList.value.find(s => s.sessionId === selectedSession.value) || null
)

let refreshTimer: ReturnType<typeof setInterval>

onMounted(() => {
  // 从 URL 或传入的 currentSessionId 初始化
  const urlHash = window.location.hash.replace('#', '').trim()
  if (urlHash) selectedSession.value = urlHash
  else if (props.currentSessionId) selectedSession.value = props.currentSessionId
  else if (props.sessions.length) selectedSession.value = props.sessions[0]

  // 同步到全局 store
  sessionStore.currentSessionId = selectedSession.value
  sessionStore.allSessions = props.sessions

  loadAll()
  startAutoRefresh()
})

onUnmounted(() => stopAutoRefresh())

function startAutoRefresh() {
  stopAutoRefresh()
  refreshTimer = setInterval(() => {
    if (!autoRefresh.value) return
    if (tab.value === 'stats') loadStats()
    else if (tab.value === 'summary') loadSummaries()
    else if (tab.value === 'profile') loadProfiles()
    else if (tab.value === 'skills') loadSkills()
  }, 5000)
}

function stopAutoRefresh() {
  if (refreshTimer) clearInterval(refreshTimer)
}

function onSessionChange() {
  if (selectedSession.value !== '__custom__') {
    sessionStore.currentSessionId = selectedSession.value
    window.location.hash = selectedSession.value
    loadAll()
  }
}

function switchToCustom() {
  if (!customSession.value.trim()) return
  selectedSession.value = customSession.value.trim()
  customSession.value = ''
  sessionStore.currentSessionId = selectedSession.value
  window.location.hash = selectedSession.value
  loadAll()
}

async function loadAll() {
  await Promise.all([loadStats(), loadSummaries(), loadProfiles()])
}

async function loadStats() {
  stats.value = await api.getMemoryStats().catch(() => null)
}

async function loadSummaries() {
  loading.value = true
  try {
    const data = await api.getAllSummaryStatuses()
    summaryList.value = data.sessions || []
  } finally { loading.value = false }
}

async function loadProfiles() {
  loading.value = true
  try {
    const data = await api.getAllProfiles()
    profiles.value = data.profiles || []
  } finally { loading.value = false }
}

async function loadSkills() {
  loading.value = true
  try {
    const data = await api.getSkills(skillSearch.value || undefined)
    skillList.value = data.skills || []
  } finally { loading.value = false }
}

async function refreshAll() {
  await loadAll()
  await loadSkills()
}

async function loadCurated() {
  const [mem, usr] = await Promise.all([
    api.getCuratedMemory('memory').catch(() => memoryEntries.value),
    api.getCuratedMemory('user').catch(() => userEntries.value),
  ])
  memoryEntries.value = mem
  userEntries.value = usr
  // 也加载搜索统计
  searchStats.value = await api.getSearchStats().catch(() => null)
}

async function addCuratedEntry(target: 'memory' | 'user') {
  const content = target === 'memory' ? newMemoryEntry.value : newUserEntry.value
  if (!content.trim()) return
  await api.curatedMemoryAction('add', target, { content: content.trim() })
  if (target === 'memory') newMemoryEntry.value = ''
  else newUserEntry.value = ''
  await loadCurated()
}

async function removeCuratedEntry(target: 'memory' | 'user', entry: string) {
  await api.curatedMemoryAction('remove', target, { old_text: entry })
  await loadCurated()
}

async function doSearch() {
  if (!searchQuery.value.trim()) return
  searchDone.value = false
  try {
    const data = await api.searchHistory(
      searchQuery.value.trim(),
      searchSessionFilter.value || undefined,
      20
    )
    searchResults.value = data.results || []
    searchDone.value = true
  } catch {
    searchResults.value = []
    searchDone.value = true
  }
}

function viewDetail(s: SummaryStatus) {
  detailItem.value = s
}

function formatTime(v: string | number): string {
  try {
    const d = new Date(Number(v))
    if (isNaN(d.getTime())) return String(v)
    return d.toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch { return String(v) }
}
</script>

<style scoped>
.memory-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow: hidden;
}

.mem-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px 16px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.07);
  border-radius: 10px;
}
.mem-session-bar { display: flex; align-items: center; gap: 8px; }
.mem-auto-tag {
  font-size: 11px;
  color: var(--accent);
  background: var(--accent-dim);
  padding: 2px 8px;
  border-radius: 10px;
  font-family: monospace;
}
.mem-auto-indicator { display: flex; align-items: center; }
.dot-anim {
  width: 7px; height: 7px; border-radius: 50%;
  background: rgba(61,220,151,0.3);
  transition: background 0.3s;
}
.dot-anim.on { background: var(--accent); animation: pulse 1.5s ease-in-out infinite; }
@keyframes pulse { 0%,100%{opacity:1;transform:scale(1)} 50%{opacity:0.5;transform:scale(0.8)} }
.mem-header-right { display: flex; align-items: center; gap: 12px; }
.mem-hint { font-size: 11px; color: var(--text-muted); font-family: monospace; }

.mem-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
  padding-bottom: 4px;
}
.mem-tab {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 16px; background: none; border: none;
  border-radius: 8px; color: rgba(255,255,255,0.45);
  cursor: pointer; font-size: 13px; font-family: inherit;
  transition: all 0.2s;
}
.mem-tab svg { width: 14px; height: 14px; }
.mem-tab:hover { color: rgba(255,255,255,0.8); background: rgba(255,255,255,0.05); }
.mem-tab.active { color: var(--accent); background: rgba(61,220,151,0.12); font-weight: 600; }

.mem-content {
  flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; padding-right: 4px;
}

/* Current session card */
.mem-current-session-card {
  background: rgba(61,220,151,0.06);
  border: 1px solid rgba(61,220,151,0.2);
  border-radius: 12px; padding: 16px 20px;
}
.mem-current-title { font-size: 13px; font-weight: 600; color: var(--accent); margin-bottom: 12px; }
.mem-current-grid { display: grid; grid-template-columns: repeat(4,1fr); gap: 12px; margin-bottom: 12px; }
.mem-current-item { background: rgba(0,0,0,0.2); border-radius: 8px; padding: 10px 12px; }
.mem-current-label { font-size: 11px; color: var(--text-muted); margin-bottom: 4px; }
.mem-current-value { font-size: 18px; font-weight: 700; color: var(--text-primary); }
.mem-current-summary {
  font-size: 12px; color: rgba(255,255,255,0.6); line-height: 1.5;
  background: rgba(0,0,0,0.2); border-radius: 6px; padding: 10px 12px;
}
.mem-current-summary.empty { color: var(--text-muted); }

.mem-grid-3 { display: grid; grid-template-columns: repeat(3,1fr); gap: 12px; }
.mem-card {
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px; padding: 18px 20px;
}
.mem-card-label { font-size: 12px; color: rgba(255,255,255,0.45); margin-bottom: 8px; }
.mem-card-value { font-size: 30px; font-weight: 700; line-height: 1; }
.mem-card-value.accent { color: var(--accent); }
.mem-card-value.amber { color: var(--accent-amber); }
.mem-card-value.blue { color: var(--accent-blue); }
.mem-card-sub { font-size: 11px; color: rgba(255,255,255,0.35); margin-top: 6px; }

.mem-section-title {
  font-size: 12px; font-weight: 600; color: rgba(255,255,255,0.4);
  text-transform: uppercase; letter-spacing: 0.08em;
}
.mem-mechanisms { display: flex; flex-direction: column; gap: 8px; }
.mem-mech-item {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.07);
  border-radius: 10px; padding: 12px 16px;
}
.mem-mech-header { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.mem-mech-dot { width: 8px; height: 8px; border-radius: 50%; }
.mem-mech-dot.enabled { background: var(--accent); box-shadow: 0 0 6px var(--accent-glow); }
.mem-mech-name { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.mem-mech-desc { font-size: 12px; color: rgba(255,255,255,0.45); }

.mem-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.mem-tag {
  padding: 3px 10px; background: rgba(61,220,151,0.1);
  border: 1px solid rgba(61,220,151,0.2); border-radius: 20px;
  font-size: 12px; color: var(--accent);
}
.mem-empty-hint { font-size: 12px; color: var(--text-muted); padding: 8px 0; }

.mem-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.mem-toolbar-left, .mem-toolbar-right { display: flex; align-items: center; gap: 8px; }
.mem-pill {
  padding: 3px 10px; background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1); border-radius: 20px;
  font-size: 12px; color: rgba(255,255,255,0.6);
}
.mem-pill.warn { border-color: rgba(245,166,35,0.3); color: var(--accent-amber); }
.mem-pill.accent { border-color: rgba(61,220,151,0.3); color: var(--accent); }

.badge {
  padding: 2px 8px; background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1); border-radius: 20px;
  font-size: 11px; color: rgba(255,255,255,0.5);
}
.badge-active { border-color: rgba(61,220,151,0.3); color: var(--accent); }
.badge-pending { border-color: rgba(245,166,35,0.3); color: var(--accent-amber); }

.mem-list { display: flex; flex-direction: column; gap: 8px; }
.mem-empty { font-size: 13px; color: var(--text-muted); padding: 24px; text-align: center; }
.mem-item {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.07);
  border-radius: 10px; padding: 14px 16px;
  display: flex; flex-direction: column; gap: 8px;
  transition: border-color 0.2s;
}
.mem-item.highlighted { border-color: rgba(61,220,151,0.25); background: rgba(61,220,151,0.04); }
.mem-item-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.mem-item-id { font-size: 13px; font-weight: 600; color: rgba(255,255,255,0.8); font-family: monospace; }
.mem-item-name { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.mem-item-desc { font-size: 12px; color: rgba(255,255,255,0.5); }
.mem-item-badges { display: flex; gap: 4px; flex-wrap: wrap; }
.mem-item-summary {
  font-size: 12px; color: rgba(255,255,255,0.55);
  background: rgba(0,0,0,0.2); border-radius: 6px;
  padding: 8px 12px; line-height: 1.5; max-height: 72px; overflow: hidden;
}
.mem-profile-summary { font-size: 12px; color: rgba(255,255,255,0.6); line-height: 1.5; }
.mem-topics { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 12px; }
.mem-topics-label { color: rgba(255,255,255,0.4); }
.mem-topic-chip { color: rgba(255,255,255,0.5); background: rgba(255,255,255,0.05); padding: 2px 8px; border-radius: 12px; }
.mem-item-footer { display: flex; align-items: center; justify-content: space-between; }
.mem-item-meta { font-size: 11px; color: rgba(255,255,255,0.3); }
.mem-steps { display: flex; flex-direction: column; gap: 4px; }
.mem-step-item { display: flex; gap: 8px; font-size: 12px; color: rgba(255,255,255,0.5); }
.mem-step-num {
  width: 18px; height: 18px; background: rgba(255,255,255,0.08);
  border-radius: 50%; display: flex; align-items: center; justify-content: center;
  font-size: 10px; color: var(--accent); flex-shrink: 0; font-weight: 700;
}

.loading-row {
  display: flex; align-items: center; gap: 10px; padding: 32px;
  color: rgba(255,255,255,0.4); font-size: 13px; justify-content: center;
}
.spinner { width: 16px; height: 16px; border: 2px solid rgba(255,255,255,0.1); border-top-color: var(--accent); border-radius: 50%; animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 100; backdrop-filter: blur(4px); }
.modal { background: var(--bg-elevated); border: 1px solid rgba(255,255,255,0.12); border-radius: 16px; width: 600px; max-width: 90vw; max-height: 80vh; overflow: hidden; display: flex; flex-direction: column; }
.modal-header { padding: 20px 24px 16px; font-size: 15px; font-weight: 600; border-bottom: 1px solid rgba(255,255,255,0.08); display: flex; align-items: center; justify-content: space-between; }
.modal-title { color: var(--text-primary); }
.modal-close { width: 28px; height: 28px; border-radius: 50%; background: var(--bg-surface); border: 1px solid var(--border-dim); color: var(--text-muted); cursor: pointer; font-size: 16px; display: flex; align-items: center; justify-content: center; transition: all 0.2s; }
.modal-close:hover { background: rgba(255,107,107,0.1); color: var(--accent-red); }
.modal-body { padding: 20px 24px; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.modal-footer { padding: 16px 24px; border-top: 1px solid rgba(255,255,255,0.08); display: flex; justify-content: flex-end; }
.detail-row { display: flex; gap: 16px; font-size: 13px; }
.detail-row span:first-child { color: rgba(255,255,255,0.45); min-width: 80px; }
.detail-row span:last-child { color: rgba(255,255,255,0.8); }
.detail-row.full span:first-child { margin-bottom: 6px; }
.detail-summary { font-size: 12px; color: rgba(255,255,255,0.6); line-height: 1.6; background: rgba(0,0,0,0.2); border-radius: 6px; padding: 12px; }

.session-select {
  background: var(--bg-surface); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm); color: var(--text-primary);
  font-family: var(--font-mono); font-size: 12px; padding: 5px 10px; outline: none;
}
.session-select:focus { border-color: var(--accent); }

/* Curated Memory */
.mem-curated-section {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.07);
  border-radius: 12px; padding: 16px 20px;
  display: flex; flex-direction: column; gap: 10px;
}
.mem-curated-header { display: flex; align-items: center; justify-content: space-between; }
.mem-curated-title { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.mem-curated-usage { font-size: 11px; color: var(--text-muted); font-family: monospace; }
.mem-curated-desc { font-size: 12px; color: rgba(255,255,255,0.4); }
.mem-curated-entries { display: flex; flex-direction: column; gap: 6px; }
.mem-curated-entry {
  display: flex; align-items: flex-start; gap: 8px; padding: 8px 12px;
  background: rgba(0,0,0,0.2); border-radius: 8px; font-size: 13px;
  color: rgba(255,255,255,0.7); line-height: 1.5;
}
.mem-curated-idx {
  min-width: 20px; height: 20px; background: rgba(61,220,151,0.15);
  border-radius: 50%; display: flex; align-items: center; justify-content: center;
  font-size: 10px; color: var(--accent); flex-shrink: 0; font-weight: 700;
}
.mem-curated-text { flex: 1; word-break: break-word; }
.mem-curated-add { display: flex; gap: 8px; margin-top: 4px; }

/* Session Search */
.mem-search-bar { display: flex; gap: 8px; margin-bottom: 8px; }
.mem-search-info { font-size: 12px; color: var(--accent); margin-bottom: 8px; }
.mem-search-hint {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 48px 24px; color: rgba(255,255,255,0.3); font-size: 14px; text-align: center;
}
</style>
