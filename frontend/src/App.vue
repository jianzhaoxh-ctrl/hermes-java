<template>
  <div class="app-layout">
    <aside class="sidebar">
      <div class="sidebar-logo">
        <svg viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M32 8 L52 20 L52 44 L32 56 L12 44 L12 20 Z" stroke="#3ddc97" stroke-width="2.5" fill="none"/>
          <circle cx="32" cy="32" r="8" fill="#3ddc97" opacity="0.9"/>
          <circle cx="32" cy="32" r="4" fill="#050810"/>
        </svg>
        <div>
          <div class="sidebar-logo-text">Hermes</div>
          <div class="sidebar-logo-sub">Agent 管理平台</div>
        </div>
      </div>

      <nav class="sidebar-nav">
        <a class="nav-item" :class="{active: page==='dashboard'}" @click="page='dashboard'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>
          <span>仪表盘</span>
        </a>
        <a class="nav-item" :class="{active: page==='chat'}" @click="page='chat'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          <span>对话</span>
        </a>
        <a class="nav-item" :class="{active: page==='sessions'}" @click="page='sessions'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
          <span>会话</span>
          <span class="nav-badge" v-if="sessionCount > 0">{{ sessionCount }}</span>
        </a>
        <div class="nav-divider"/>
        <a class="nav-item" :class="{active: page==='memory'}" @click="page='memory'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
          <span>记忆管理</span>
        </a>
        <a class="nav-item" :class="{active: page==='profiles'}" @click="page='profiles'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
          <span>用户画像</span>
        </a>
        <a class="nav-item" :class="{active: page==='tools'}" @click="page='tools'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>
          <span>工具</span>
        </a>
        <div class="nav-divider"/>
        <a class="nav-item" :class="{active: page==='skills'}" @click="page='skills'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
          <span>技能管理</span>
          <span class="nav-badge" v-if="skillCount > 0">{{ skillCount }}</span>
        </a>
        <a class="nav-item" :class="{active: page==='scheduler'}" @click="page='scheduler'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
          <span>定时任务</span>
          <span class="nav-badge" v-if="jobCount > 0">{{ jobCount }}</span>
        </a>
        <a class="nav-item" :class="{active: page==='subagents'}" @click="page='subagents'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>
          <span>子 Agent</span>
          <span class="nav-badge" v-if="subagentCount > 0">{{ subagentCount }}</span>
        </a>
        <a class="nav-item" :class="{active: page==='rl'}" @click="page='rl'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          <span>RL 训练</span>
        </a>
        <div class="nav-divider"/>
        <a class="nav-item" :class="{active: page==='settings'}" @click="page='settings'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
          <span>设置</span>
        </a>
      </nav>

      <div class="sidebar-footer">
        <div>API: {{ baseUrl }}</div>
        <div style="margin-top:2px">v1.0.0</div>
      </div>
    </aside>

    <!-- Main -->
    <div class="main-content">
      <header class="top-header">
        <div>
          <div class="header-title">{{ pageTitles[page] }}</div>
          <div class="header-subtitle">{{ pageSubtitles[page] }}</div>
        </div>
        <div class="header-spacer"/>
        <div class="header-status">
          <div class="status-dot" :class="{offline: !isOnline}"/>
          <span>{{ isOnline ? '已连接' : '离线' }}</span>
        </div>
        <button class="btn btn-ghost btn-sm" @click="refreshAll">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
          刷新
        </button>
      </header>

      <div class="content-area">
        <Dashboard v-if="page==='dashboard'" :stats="stats" :sessions="sessions" :jobs="jobs" :subagents="subagents" @navigate="page=$event" />
        <ChatView v-if="page==='chat'" :sessions="sessions" :initialSessionId="targetSessionId" />
        <SessionsView v-if="page==='sessions'" :sessions="sessions" @refresh="loadAll" @chat="switchToChat" />
        <MemoryView v-if="page==='memory'" :sessions="sessions" :currentSessionId="sessionStore.currentSessionId" />
        <SkillsView v-if="page==='skills'" />
        <SchedulerView v-if="page==='scheduler'" :jobs="jobs" @refresh="loadJobs" />
        <SubAgentsView v-if="page==='subagents'" :subagents="subagents" @refresh="loadSubAgents" />
        <ProfilesView v-if="page==='profiles'" />
        <ToolsView v-if="page==='tools'" />
        <SettingsView v-if="page==='settings'" />
        <RLTrainingView v-if="page==='rl'" />
      </div>
    </div>

    <!-- Toast -->
    <div v-if="toast" class="toast" :class="toast.type">
      {{ toast.msg }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import * as api from './api'
import { sessionStore } from './sessionStore'
import Dashboard from './views/Dashboard.vue'
import ChatView from './views/Chat.vue'
import SessionsView from './views/Sessions.vue'
import MemoryView from './views/Memory.vue'
import SkillsView from './views/Skills.vue'
import SchedulerView from './views/Scheduler.vue'
import SubAgentsView from './views/SubAgents.vue'
import SettingsView from './views/Settings.vue'
import RLTrainingView from './views/RLTraining.vue'
import ProfilesView from './views/Profiles.vue'
import ToolsView from './views/Tools.vue'

const page = ref('dashboard')
const pageTitles: Record<string, string> = {
  dashboard: '仪表盘', chat: '对话', sessions: '会话', memory: '记忆管理',
  profiles: '用户画像', tools: '工具浏览器',
  skills: '技能管理', scheduler: '定时任务', subagents: '子 Agent',
  settings: '设置', rl: 'RL 训练'
}
const pageSubtitles: Record<string, string> = {
  dashboard: '系统概览与指标数据',
  chat: '与 Hermes Agent 实时对话',
  sessions: '管理活跃的会话',
  memory: '会话摘要 · 用户画像 · 技能管理',
  profiles: 'Honcho 用户画像 · Conclusions · Liveness · 语义搜索',
  tools: '浏览和调用 Honcho 工具',
  skills: '管理 Agent 的内置与自动生成技能',
  scheduler: '创建和管理定时任务',
  subagents: '监控和调度子 Agent',
  settings: '配置信息与系统状态',
  rl: 'Tinker-Atropos RL 训练 · 环境管理 · 轨迹压缩'
}

const isOnline = ref(false)
const baseUrl = computed(() => {
  try { return new URL((api as any).BASE || 'http://localhost:8993/api').origin }
  catch { return 'localhost' }
})
const sessions = ref<string[]>([])
const jobs = ref<any[]>([])
const subagents = ref<any[]>([])
const skills = ref<any[]>([])
const toast = ref<{msg: string; type: string} | null>(null)
const targetSessionId = ref<string | null>(null)

const stats = computed(() => ({
  sessionCount: sessions.value.length,
  jobCount: jobs.value.length,
  subagentCount: subagents.value.filter((a: any) => a.status === 'running').length,
  modelName: 'qwen-plus'
}))

const sessionCount = computed(() => sessions.value.length)
const jobCount = computed(() => jobs.value.length)
const subagentCount = computed(() => subagents.value.filter((a: any) => a.status === 'running').length)
const skillCount = computed(() => skills.value.length)

let refreshTimer: number

function showToast(msg: string, type = 'success') {
  toast.value = { msg, type }
  setTimeout(() => { toast.value = null }, 2800)
}

async function loadSessions() {
  try {
    const data = await api.getSessions()
    sessions.value = data.activeSessions || []
    isOnline.value = true
  } catch { isOnline.value = false }
}

async function loadJobs() {
  try { jobs.value = await api.getJobs() } catch { jobs.value = [] }
}

async function loadSubAgents() {
  try { subagents.value = await api.getSubAgents() } catch { subagents.value = [] }
}

async function loadSkills() {
  try {
    const data = await api.getSkills()
    skills.value = data.skills || []
  } catch { skills.value = [] }
}

async function loadAll() {
  await Promise.all([loadSessions(), loadJobs(), loadSubAgents(), loadSkills()])
}

function refreshAll() { loadAll(); showToast('已刷新') }

function switchToChat(sessionId?: string) {
  if (sessionId) {
    targetSessionId.value = sessionId
    sessionStore.currentSessionId = sessionId
  }
  page.value = 'chat'
}

onMounted(() => {
  loadAll()
  refreshTimer = window.setInterval(loadAll, 15000)
})

onUnmounted(() => clearInterval(refreshTimer))
</script>