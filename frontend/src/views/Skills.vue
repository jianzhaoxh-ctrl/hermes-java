<template>
  <div class="skills-view">
    <div class="skills-header">
      <div class="skills-title">
        <h2>技能管理</h2>
        <p>管理本地技能、Hub 技能和自动生成的技能</p>
      </div>
      <div class="skills-actions">
        <button class="btn btn-ghost btn-sm" @click="refreshSkills" title="刷新">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23 4 23 10 17 10"/>
            <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
          </svg>
          刷新
        </button>
        <button class="btn btn-ghost btn-sm" @click="openHubTab" title="技能市场">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
          </svg>
          Hub 市场
        </button>
      </div>
    </div>

    <!-- Stats Cards -->
    <div class="skills-stats">
      <div class="stat-card">
        <div class="stat-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ skills.length }}</div>
          <div class="stat-label">总技能数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon hub-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ hubSkillsCount }}</div>
          <div class="stat-label">Hub 安装</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon auto-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value" :style="{color: autoSkillsCount > 0 ? 'var(--accent)' : 'var(--text-muted)'}">
            {{ autoSkillsCount }}
          </div>
          <div class="stat-label">自动生成</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon custom-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ customSkillsCount }}</div>
          <div class="stat-label">自定义</div>
        </div>
      </div>
    </div>

    <!-- Tab Navigation -->
    <div class="tab-nav">
      <button :class="['tab-btn', { active: activeTab === 'local' }]" @click="activeTab = 'local'">
        本地技能
      </button>
      <button :class="['tab-btn', { active: activeTab === 'hub' }]" @click="activeTab = 'hub'">
        Hub 市场
      </button>
    </div>

    <!-- ============ Local Skills Tab ============ -->
    <div v-if="activeTab === 'local'" class="tab-content">
      <!-- Search and Filter -->
      <div class="skills-toolbar">
        <div class="search-box">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/>
            <path d="m21 21-4.35-4.35"/>
          </svg>
          <input
            v-model="searchQuery"
            class="form-input"
            placeholder="搜索技能名称或描述..."
            @input="debouncedSearch"
          />
        </div>
        <div class="filters">
          <select v-model="filterSource" class="form-select" @change="applyFilters">
            <option value="">全部来源</option>
            <option value="hub">Hub 安装</option>
            <option value="custom">自定义</option>
            <option value="auto-generated">自动生成</option>
          </select>
        </div>
      </div>

      <!-- Skills List -->
      <div class="skills-list">
        <div v-if="loading" class="loading-state">
          <div class="spinner" style="width:24px;height:24px;border-width:3px"/>
          <span>加载中...</span>
        </div>
        <div v-else-if="filteredSkills.length === 0" class="empty-state">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>
          </svg>
          <h3>暂无技能</h3>
          <p v-if="!searchQuery && filterSource === ''">开始同类任务后，系统将自动生成对应技能</p>
          <p v-else>未找到匹配的技能</p>
        </div>
        <div v-else class="skills-grid">
          <div v-for="skill in filteredSkills" :key="skill.name" class="skill-card" @click="viewSkillDetails(skill)">
            <div class="skill-header">
              <div class="skill-name">
                <span class="skill-name-text">{{ skill.name }}</span>
                <span :class="['badge', sourceBadgeClass(skill.source)]">{{ sourceLabel(skill.source) }}</span>
              </div>
              <div class="skill-version" v-if="skill.version">v{{ skill.version }}</div>
            </div>
            
            <div class="skill-description">{{ skill.description }}</div>

            <div class="skill-footer">
              <!-- Platforms -->
              <div class="skill-platforms" v-if="skill.platforms?.length">
                <span v-for="p in skill.platforms" :key="p" class="platform-tag">{{ p }}</span>
              </div>
              <!-- Tags -->
              <div class="skill-tags" v-if="skill.tags?.length">
                <span v-for="tag in skill.tags.slice(0, 3)" :key="tag" class="tag">{{ tag }}</span>
                <span v-if="skill.tags.length > 3" class="tag tag-more">+{{ skill.tags.length - 3 }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ Hub Tab ============ -->
    <div v-if="activeTab === 'hub'" class="tab-content">
      <div class="skills-toolbar">
        <div class="search-box">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/>
            <path d="m21 21-4.35-4.35"/>
          </svg>
          <input
            v-model="hubSearchQuery"
            class="form-input"
            placeholder="搜索 Hub 技能..."
            @keyup.enter="searchHub"
          />
        </div>
        <button class="btn btn-primary btn-sm" @click="searchHub" :disabled="hubSearching">
          {{ hubSearching ? '搜索中...' : '搜索' }}
        </button>
        <button class="btn btn-ghost btn-sm" @click="checkHubUpdates" :disabled="hubCheckingUpdates">
          {{ hubCheckingUpdates ? '检查中...' : '检查更新' }}
        </button>
      </div>

      <!-- Hub Updates -->
      <div v-if="hubUpdates.length > 0" class="hub-updates">
        <h4>可用更新 ({{ hubUpdates.length }})</h4>
        <div class="update-list">
          <div v-for="u in hubUpdates" :key="u.name" class="update-item">
            <span class="update-name">{{ u.name }}</span>
            <span class="update-versions">{{ u.currentVersion }} → {{ u.latestVersion }}</span>
            <button class="btn btn-ghost btn-xs" @click="hubUpdateOne(u.name)">更新</button>
          </div>
        </div>
      </div>

      <!-- Hub Search Results -->
      <div class="skills-list">
        <div v-if="hubSearchResults.length === 0 && !hubSearching" class="empty-state">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
          </svg>
          <h3>搜索 Hub 技能</h3>
          <p>输入关键词搜索远程技能仓库</p>
        </div>
        <div v-else class="skills-grid">
          <div v-for="hubSkill in hubSearchResults" :key="hubSkill.id" class="skill-card hub-card">
            <div class="skill-header">
              <div class="skill-name">
                <span class="skill-name-text">{{ hubSkill.name }}</span>
                <span class="badge badge-hub">Hub</span>
              </div>
              <div class="skill-version">v{{ hubSkill.version }}</div>
            </div>
            <div class="skill-description">{{ hubSkill.description }}</div>
            <div class="skill-footer">
              <div class="skill-meta" v-if="hubSkill.author">
                <span class="meta-label">作者:</span> {{ hubSkill.author }}
              </div>
              <div class="skill-tags" v-if="hubSkill.tags?.length">
                <span v-for="tag in hubSkill.tags.slice(0, 4)" :key="tag" class="tag">{{ tag }}</span>
              </div>
            </div>
            <div class="skill-install">
              <button
                class="btn btn-primary btn-xs"
                @click="installHubSkill(hubSkill.id)"
                :disabled="installing === hubSkill.id"
              >
                {{ installing === hubSkill.id ? '安装中...' : '安装' }}
              </button>
              <button class="btn btn-ghost btn-xs" @click="previewHubSkill(hubSkill.id)">
                预览
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ Skill Detail Modal ============ -->
    <div v-if="showDetailModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal modal-lg">
        <div class="modal-header">
          <div class="modal-title">
            <span>{{ selectedSkill?.name }}</span>
            <span :class="['badge', sourceBadgeClass(selectedSkill?.source)]">
              {{ sourceLabel(selectedSkill?.source) }}
            </span>
            <span v-if="selectedSkill?.version" class="version-badge">v{{ selectedSkill.version }}</span>
          </div>
          <button class="modal-close" @click="closeModal">×</button>
        </div>
        <div class="modal-body">
          <div class="detail-section">
            <h4>描述</h4>
            <p>{{ selectedSkill?.description }}</p>
          </div>

          <!-- Author / License -->
          <div class="detail-section" v-if="selectedSkill?.author || selectedSkill?.license">
            <h4>元信息</h4>
            <div class="detail-meta-grid">
              <div v-if="selectedSkill?.author" class="meta-item">
                <span class="meta-label">作者</span>
                <span>{{ selectedSkill.author }}</span>
              </div>
              <div v-if="selectedSkill?.license" class="meta-item">
                <span class="meta-label">许可证</span>
                <span>{{ selectedSkill.license }}</span>
              </div>
              <div v-if="selectedSkill?.source" class="meta-item">
                <span class="meta-label">来源</span>
                <span>{{ sourceLabel(selectedSkill.source) }}</span>
              </div>
            </div>
          </div>

          <!-- Platforms -->
          <div class="detail-section" v-if="selectedSkill?.platforms?.length">
            <h4>支持平台</h4>
            <div class="tags-list">
              <span v-for="p in selectedSkill.platforms" :key="p" class="platform-tag">{{ p }}</span>
            </div>
          </div>

          <!-- Prerequisites -->
          <div class="detail-section" v-if="hasPrerequisites(selectedSkill)">
            <h4>依赖条件</h4>
            <div class="prereq-grid">
              <div v-if="selectedSkill?.prerequisites?.commands?.length" class="prereq-group">
                <div class="prereq-label">命令行工具:</div>
                <code v-for="cmd in selectedSkill.prerequisites.commands" :key="cmd" class="prereq-code">{{ cmd }}</code>
              </div>
              <div v-if="selectedSkill?.prerequisites?.envVars?.length" class="prereq-group">
                <div class="prereq-label">环境变量:</div>
                <code v-for="ev in selectedSkill.prerequisites.envVars" :key="ev" class="prereq-code">{{ ev }}</code>
              </div>
              <div v-if="selectedSkill?.prerequisites?.files?.length" class="prereq-group">
                <div class="prereq-label">文件:</div>
                <code v-for="f in selectedSkill.prerequisites.files" :key="f" class="prereq-code">{{ f }}</code>
              </div>
            </div>
          </div>

          <!-- Tags -->
          <div class="detail-section" v-if="selectedSkill?.tags?.length">
            <h4>标签</h4>
            <div class="tags-list">
              <span v-for="tag in selectedSkill.tags" :key="tag" class="tag">{{ tag }}</span>
            </div>
          </div>

          <!-- Content (Markdown body) -->
          <div class="detail-section" v-if="selectedSkill?.content">
            <h4>技能内容</h4>
            <pre class="skill-content-pre">{{ selectedSkill.content }}</pre>
          </div>

          <!-- Timestamps -->
          <div class="detail-section" v-if="selectedSkill?.createdAt || selectedSkill?.updatedAt">
            <h4>时间信息</h4>
            <div class="detail-meta-grid">
              <div v-if="selectedSkill?.createdAt" class="meta-item">
                <span class="meta-label">创建时间</span>
                <span>{{ formatDate(selectedSkill.createdAt) }}</span>
              </div>
              <div v-if="selectedSkill?.updatedAt" class="meta-item">
                <span class="meta-label">更新时间</span>
                <span>{{ formatDate(selectedSkill.updatedAt) }}</span>
              </div>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button
            v-if="selectedSkill?.source !== 'hub'"
            class="btn btn-danger btn-sm"
            @click="deleteSkill(selectedSkill!.name)"
          >
            删除
          </button>
          <span v-else class="hint-text">Hub 技能请通过 Hub 管理卸载</span>
          <button class="btn btn-ghost btn-sm" @click="closeModal">关闭</button>
        </div>
      </div>
    </div>

    <!-- ============ Hub Preview Modal ============ -->
    <div v-if="showPreviewModal" class="modal-overlay" @click.self="closePreviewModal">
      <div class="modal modal-lg">
        <div class="modal-header">
          <div class="modal-title">技能预览</div>
          <button class="modal-close" @click="closePreviewModal">×</button>
        </div>
        <div class="modal-body">
          <pre class="skill-content-pre">{{ previewContent }}</pre>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost btn-sm" @click="closePreviewModal">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import * as api from '../api'
import type { Skill } from '../api'
import type { HubSkill, UpdateInfo } from '../api'

const skills = ref<Skill[]>([])
const loading = ref(false)
const searchQuery = ref('')
const filterSource = ref<'' | 'hub' | 'custom' | 'auto-generated'>('')
const showDetailModal = ref(false)
const selectedSkill = ref<Skill | null>(null)

// Tab
const activeTab = ref<'local' | 'hub'>('local')

// Hub state
const hubSearchQuery = ref('')
const hubSearchResults = ref<HubSkill[]>([])
const hubSearching = ref(false)
const hubUpdates = ref<UpdateInfo[]>([])
const hubCheckingUpdates = ref(false)
const installing = ref<string | null>(null)
const showPreviewModal = ref(false)
const previewContent = ref('')

// 计算属性
const hubSkillsCount = computed(() => skills.value.filter(s => s.source === 'hub').length)
const autoSkillsCount = computed(() => skills.value.filter(s => s.source === 'auto-generated').length)
const customSkillsCount = computed(() => skills.value.filter(s => s.source === 'custom').length)

const filteredSkills = computed(() => {
  let result = skills.value
  
  if (searchQuery.value) {
    const query = searchQuery.value.toLowerCase()
    result = result.filter(skill => 
      skill.name.toLowerCase().includes(query) ||
      skill.description?.toLowerCase().includes(query) ||
      skill.tags?.some(t => t.toLowerCase().includes(query))
    )
  }
  
  if (filterSource.value) {
    result = result.filter(skill => skill.source === filterSource.value)
  }
  
  return result
})

// Source helpers
function sourceLabel(source?: string): string {
  switch (source) {
    case 'hub': return 'Hub'
    case 'auto-generated': return '自动生成'
    case 'custom': return '自定义'
    default: return '本地'
  }
}

function sourceBadgeClass(source?: string): string {
  switch (source) {
    case 'hub': return 'badge-hub'
    case 'auto-generated': return 'badge-auto'
    case 'custom': return 'badge-manual'
    default: return 'badge-manual'
  }
}

function hasPrerequisites(skill?: Skill | null): boolean {
  if (!skill?.prerequisites) return false
  const p = skill.prerequisites
  return (p.commands?.length ?? 0) > 0 || (p.envVars?.length ?? 0) > 0 || (p.files?.length ?? 0) > 0
}

// 防抖搜索
let searchTimer: number | null = null
function debouncedSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => { applyFilters() }, 300)
}

function applyFilters() {
  // computed handles it
}

async function loadSkills() {
  loading.value = true
  try {
    const data = await api.getSkills()
    skills.value = (data.skills || []).map(s => ({
      ...s,
      // backward compat: source → autoGenerated
      autoGenerated: s.autoGenerated ?? s.source === 'auto-generated',
      tags: s.tags ?? [],
    }))
  } catch (error) {
    console.error('Failed to load skills:', error)
  } finally {
    loading.value = false
  }
}

function refreshSkills() {
  loadSkills()
}

function openHubTab() {
  activeTab.value = 'hub'
}

function viewSkillDetails(skill: Skill) {
  selectedSkill.value = skill
  showDetailModal.value = true
}

function closeModal() {
  showDetailModal.value = false
  selectedSkill.value = null
}

async function deleteSkill(name: string) {
  if (!confirm(`确定要删除技能 "${name}" 吗？`)) return
  try {
    await api.deleteSkill(name)
    loadSkills()
    closeModal()
  } catch (error) {
    console.error('Failed to delete skill:', error)
  }
}

function formatDate(timestamp: number | string) {
  try {
    const date = new Date(typeof timestamp === 'string' ? timestamp : Number(timestamp))
    return date.toLocaleString('zh-CN')
  } catch {
    return String(timestamp)
  }
}

// ── Hub Functions ────────────────────────────────────────

async function searchHub() {
  if (!hubSearchQuery.value.trim()) return
  hubSearching.value = true
  try {
    hubSearchResults.value = await api.hubSearch(hubSearchQuery.value)
  } catch (error) {
    console.error('Hub search failed:', error)
    hubSearchResults.value = []
  } finally {
    hubSearching.value = false
  }
}

async function checkHubUpdates() {
  hubCheckingUpdates.value = true
  try {
    hubUpdates.value = await api.hubCheckUpdates()
  } catch (error) {
    console.error('Hub update check failed:', error)
  } finally {
    hubCheckingUpdates.value = false
  }
}

async function hubUpdateOne(name: string) {
  try {
    await api.hubUpdateAll() // update-all covers individual
    hubUpdates.value = hubUpdates.value.filter(u => u.name !== name)
    loadSkills()
  } catch (error) {
    console.error('Hub update failed:', error)
  }
}

async function installHubSkill(skillId: string) {
  installing.value = skillId
  try {
    await api.hubInstall(skillId)
    loadSkills()
  } catch (error) {
    console.error('Hub install failed:', error)
  } finally {
    installing.value = null
  }
}

async function previewHubSkill(skillId: string) {
  try {
    const content = await api.hubInspect(skillId)
    previewContent.value = content
    showPreviewModal.value = true
  } catch (error) {
    console.error('Hub preview failed:', error)
  }
}

function closePreviewModal() {
  showPreviewModal.value = false
  previewContent.value = ''
}

onMounted(() => {
  loadSkills()
})
</script>

<style scoped>
.skills-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 20px;
  overflow: hidden;
}

.skills-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.skills-title h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--text-primary);
}
.skills-title p {
  margin: 4px 0 0 0;
  font-size: 14px;
  color: var(--text-muted);
}

.skills-actions {
  display: flex;
  gap: 8px;
}

.skills-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  padding: 14px 16px;
}

.stat-icon {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-elevated);
  border-radius: 8px;
  color: var(--accent);
}

.stat-icon svg { width: 18px; height: 18px; }
.hub-icon svg { color: #8b5cf6; }
.auto-icon svg { color: #3ddc97; }
.custom-icon svg { color: #f59e0b; }

.stat-info { flex: 1; }
.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}
.stat-label {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 2px;
}

/* Tabs */
.tab-nav {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--border-subtle);
  padding-bottom: 0;
}

.tab-btn {
  padding: 8px 16px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: all 0.2s;
}

.tab-btn:hover {
  color: var(--text-primary);
}

.tab-btn.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

.tab-content {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.skills-toolbar {
  display: flex;
  gap: 12px;
  padding: 8px 0;
  align-items: center;
}

.search-box {
  position: relative;
  flex: 1;
  max-width: 400px;
}

.search-box svg {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--text-muted);
  pointer-events: none;
}

.search-box input {
  padding-left: 32px;
  width: 100%;
}

.filters { display: flex; gap: 8px; }

.skills-list {
  flex: 1;
  overflow-y: auto;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: var(--text-muted);
  gap: 12px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: var(--text-muted);
  text-align: center;
  gap: 12px;
}

.empty-state h3 {
  margin: 0;
  font-size: 16px;
  color: var(--text-primary);
}

.skills-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

.skill-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  padding: 16px;
  transition: all 0.2s;
  cursor: pointer;
}

.skill-card:hover {
  border-color: var(--border-focus);
  background: var(--bg-hover);
}

.hub-card { cursor: default; }

.skill-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 8px;
}

.skill-name {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  flex-wrap: wrap;
}

.skill-name-text {
  font-weight: 600;
  color: var(--text-primary);
  word-break: break-word;
}

.skill-version {
  font-size: 11px;
  color: var(--text-muted);
  background: var(--bg-elevated);
  padding: 2px 6px;
  border-radius: 6px;
}

.badge {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 10px;
  font-weight: 500;
  text-transform: uppercase;
  white-space: nowrap;
}

.badge-auto {
  background: rgba(61, 220, 151, 0.15);
  border: 1px solid rgba(61, 220, 151, 0.3);
  color: #3ddc97;
}

.badge-manual {
  background: rgba(245, 158, 11, 0.15);
  border: 1px solid rgba(245, 158, 11, 0.3);
  color: #f59e0b;
}

.badge-hub {
  background: rgba(139, 92, 246, 0.15);
  border: 1px solid rgba(139, 92, 246, 0.3);
  color: #8b5cf6;
}

.version-badge {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 10px;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-muted);
}

.skill-description {
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.5;
  margin-bottom: 10px;
}

.skill-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.platform-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(255,255,255,0.05);
  color: var(--text-muted);
  border: 1px solid rgba(255,255,255,0.08);
}

.tag {
  background: rgba(61, 220, 151, 0.1);
  border: 1px solid rgba(61, 220, 151, 0.2);
  border-radius: 12px;
  padding: 1px 8px;
  font-size: 10px;
  color: var(--accent);
}

.tag-more {
  background: rgba(255,255,255,0.05);
  border-color: rgba(255,255,255,0.1);
  color: var(--text-muted);
}

.skill-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.skill-meta {
  font-size: 11px;
  color: var(--text-muted);
}

.meta-label {
  color: var(--text-muted);
  font-size: 11px;
}

.skill-install {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--border-subtle);
}

/* Hub Updates */
.hub-updates {
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  padding: 12px 16px;
}

.hub-updates h4 {
  margin: 0 0 8px 0;
  font-size: 13px;
  color: var(--text-primary);
}

.update-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.update-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.update-name {
  font-weight: 600;
  color: var(--text-primary);
}

.update-versions {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}

/* Modal */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  backdrop-filter: blur(4px);
}

.modal {
  background: var(--bg-elevated);
  border: 1px solid var(--border-dim);
  border-radius: 16px;
  width: 800px;
  max-width: 90vw;
  max-height: 80vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-lg { width: 800px; }

.modal-header {
  padding: 20px 24px 16px;
  font-size: 15px;
  font-weight: 600;
  border-bottom: 1px solid var(--border-dim);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.modal-title {
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.modal-close {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--bg-surface);
  border: 1px solid var(--border-dim);
  color: var(--text-muted);
  cursor: pointer;
  font-size: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.modal-close:hover {
  background: rgba(255, 107, 107, 0.1);
  color: var(--accent-red);
}

.modal-body {
  padding: 20px 24px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.modal-footer {
  padding: 16px 24px;
  border-top: 1px solid var(--border-dim);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-section h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.detail-meta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 8px;
}

.meta-item {
  font-size: 13px;
  display: flex;
  gap: 8px;
}

.meta-item .meta-label {
  color: var(--text-muted);
  min-width: 50px;
}

.tags-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.prereq-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.prereq-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.prereq-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
}

.prereq-code {
  font-size: 11px;
  background: rgba(0,0,0,0.2);
  padding: 2px 8px;
  border-radius: 4px;
  font-family: var(--font-mono);
  color: var(--accent);
}

.skill-content-pre {
  background: rgba(0,0,0,0.2);
  padding: 16px;
  border-radius: 8px;
  font-size: 12px;
  font-family: var(--font-mono);
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-wrap: break-word;
  max-height: 400px;
  overflow-y: auto;
  color: var(--text-secondary);
}

.hint-text {
  font-size: 12px;
  color: var(--text-muted);
  font-style: italic;
}

.btn-danger {
  background: rgba(255, 107, 107, 0.15);
  border: 1px solid rgba(255, 107, 107, 0.3);
  color: #ff6b6b;
}

.btn-danger:hover {
  background: rgba(255, 107, 107, 0.25);
}

.btn-primary {
  background: var(--accent);
  color: #000;
  border: 1px solid var(--accent);
}

.btn-primary:hover {
  opacity: 0.9;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
