<template>
  <div class="tools-page">
    <header class="page-header">
      <h1>🛠️ 工具浏览器</h1>
      <button class="btn-primary" @click="loadTools" :disabled="loading">
        {{ loading ? '加载中...' : '🔄 刷新工具' }}
      </button>
    </header>

    <!-- 工具调用结果 -->
    <div v-if="callResult" class="call-result">
      <div class="call-result-header">
        <span>调用结果</span>
        <button class="btn-close" @click="callResult = ''">✕</button>
      </div>
      <pre class="call-result-body">{{ callResult }}</pre>
    </div>

    <!-- 工具网格 -->
    <div v-if="tools.length === 0 && !loading" class="empty-state">
      <p>暂无工具</p>
      <p class="empty-hint">确保 Honcho 工具已注册，或检查后端连接</p>
    </div>

    <div class="tools-grid">
      <div v-for="tool in tools" :key="tool.name" class="tool-card">
        <div class="tool-header">
          <span class="tool-name">{{ tool.name }}</span>
          <span class="tool-badge">{{ tool.category || 'default' }}</span>
        </div>
        <div class="tool-description">{{ tool.description }}</div>

        <!-- 参数 Schema -->
        <div v-if="tool.parameters && tool.parameters.length > 0" class="tool-params">
          <span class="params-label">参数:</span>
          <div v-for="p in tool.parameters" :key="p.name" class="param-item">
            <code class="param-name">{{ p.name }}</code>
            <span class="param-type">{{ p.type }}</span>
            <span class="param-desc">{{ p.description }}</span>
          </div>
        </div>

        <!-- 调用表单 -->
        <div class="tool-call">
          <div v-for="p in tool.parameters" :key="p.name" class="call-param">
            <label :for="`${tool.name}-${p.name}`">{{ p.name }}</label>
            <input
              :id="`${tool.name}-${p.name}`"
              v-model="callParams[tool.name + ':' + p.name]"
              :placeholder="`${p.name} (${p.type})`"
              class="param-input"
            />
          </div>
          <button class="btn-call" @click="callTool(tool)" :disabled="calling === tool.name">
            {{ calling === tool.name ? '调用中...' : '▶ 调用' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 系统状态 -->
    <div class="system-info">
      <div class="info-item">
        <span>可用工具</span>
        <span>{{ tools.length }}</span>
      </div>
      <div class="info-item">
        <span>上次刷新</span>
        <span>{{ lastRefresh ? formatTime(lastRefresh) : '-' }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getSkills, type Skill } from '../api'

interface Tool extends Skill {
  parameters?: Array<{ name: string; type: string; description: string }>
}

const tools = ref<Tool[]>([])
const loading = ref(false)
const calling = ref<string | null>(null)
const callResult = ref('')
const callParams = ref<Record<string, string>>({})
const lastRefresh = ref<number | null>(null)

async function loadTools() {
  loading.value = true
  try {
    // 获取技能列表作为工具源
    const data = await getSkills()
    // 技能系统中的工具（过滤有参数的技能）
    tools.value = (data.skills || [])
      .filter(s => s.parameters && s.parameters.length > 0)
      .map(s => ({
        ...s,
        category: s.category || 'skill',
      }))
    lastRefresh.value = Date.now()
  } catch (e) {
    console.error('Failed to load tools:', e)
  } finally {
    loading.value = false
  }
}

async function callTool(tool: Tool) {
  calling.value = tool.name
  callResult.value = ''
  try {
    // 从 Honcho tools API 调用（如果后端支持）
    const res = await fetch('http://localhost:8993/honcho/tools/call', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        toolName: tool.name,
        parameters: Object.fromEntries(
          (tool.parameters || [])
            .map(p => [p.name, callParams.value[tool.name + ':' + p.name] || ''])
        ),
      }),
    })
    const data = await res.json()
    callResult.value = JSON.stringify(data, null, 2)
  } catch (e) {
    callResult.value = '调用失败: ' + String(e)
  } finally {
    calling.value = null
  }
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleString('zh-CN')
}

onMounted(() => {
  loadTools()
})
</script>

<style scoped>
.tools-page {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  height: 100%;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-header h1 {
  font-size: 1.3rem;
  color: var(--text-primary, #fff);
  margin: 0;
}

/* Call result */
.call-result {
  background: var(--bg-secondary, #0d1117);
  border: 1px solid var(--accent, #3ddc97);
  border-radius: 8px;
  overflow: hidden;
}

.call-result-header {
  background: var(--bg-tertiary, #161b22);
  padding: 0.5rem 0.75rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.85rem;
  color: var(--accent, #3ddc97);
  font-weight: 600;
}

.btn-close {
  background: none;
  border: none;
  color: var(--text-secondary, #8b949e);
  cursor: pointer;
  font-size: 0.85rem;
}

.call-result-body {
  padding: 0.75rem;
  font-size: 0.8rem;
  color: var(--text-primary, #fff);
  white-space: pre-wrap;
  overflow-x: auto;
  max-height: 300px;
  overflow-y: auto;
  margin: 0;
}

/* Tools grid */
.tools-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 1rem;
  overflow-y: auto;
  flex: 1;
}

.tool-card {
  background: var(--bg-secondary, #0d1117);
  border: 1px solid var(--border-color, #30363d);
  border-radius: 8px;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}

.tool-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.tool-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--accent, #3ddc97);
}

.tool-badge {
  font-size: 0.7rem;
  background: var(--bg-tertiary, #161b22);
  color: var(--text-secondary, #8b949e);
  padding: 0.15rem 0.5rem;
  border-radius: 20px;
}

.tool-description {
  font-size: 0.8rem;
  color: var(--text-secondary, #8b949e);
  line-height: 1.4;
}

/* Parameters */
.tool-params {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}

.params-label {
  font-size: 0.75rem;
  color: var(--text-secondary, #8b949e);
  font-weight: 600;
}

.param-item {
  display: flex;
  align-items: baseline;
  gap: 0.4rem;
  font-size: 0.8rem;
}

.param-name {
  color: var(--accent, #3ddc97);
  background: var(--bg-tertiary, #161b22);
  padding: 0.1rem 0.3rem;
  border-radius: 4px;
}

.param-type {
  color: #f0883e;
  font-size: 0.75rem;
}

.param-desc {
  color: var(--text-secondary, #8b949e);
  flex: 1;
}

/* Call form */
.tool-call {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  border-top: 1px solid var(--border-color, #30363d);
  padding-top: 0.6rem;
}

.call-param {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.call-param label {
  font-size: 0.75rem;
  color: var(--text-secondary, #8b949e);
}

.param-input {
  background: var(--bg-tertiary, #161b22);
  border: 1px solid var(--border-color, #30363d);
  border-radius: 6px;
  padding: 0.4rem 0.5rem;
  color: var(--text-primary, #fff);
  font-size: 0.8rem;
  width: 100%;
}

.param-input:focus {
  outline: none;
  border-color: var(--accent, #3ddc97);
}

.btn-call {
  background: var(--accent, #3ddc97);
  color: #000;
  border: none;
  border-radius: 6px;
  padding: 0.5rem;
  cursor: pointer;
  font-size: 0.85rem;
  font-weight: 600;
  margin-top: 0.3rem;
}

.btn-call:hover {
  opacity: 0.9;
}

.btn-call:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* System info */
.system-info {
  display: flex;
  gap: 1.5rem;
  padding: 0.5rem 0;
  border-top: 1px solid var(--border-color, #30363d);
}

.info-item {
  display: flex;
  gap: 0.5rem;
  font-size: 0.8rem;
}

.info-item span:first-child {
  color: var(--text-secondary, #8b949e);
}

.info-item span:last-child {
  color: var(--text-primary, #fff);
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
}

.btn-primary:hover {
  opacity: 0.9;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.empty-state {
  text-align: center;
  color: var(--text-secondary, #8b949e);
  padding: 3rem;
  font-size: 0.9rem;
}

.empty-hint {
  font-size: 0.8rem;
  margin-top: 0.5rem;
  color: #666;
}
</style>
