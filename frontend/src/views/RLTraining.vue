<template>
  <div class="rl-view">

    <!-- Header -->
    <div class="rl-header">
      <div class="rl-title">
        <span class="rl-emoji">🤖</span>
        <span>RL Training</span>
        <span class="rl-badge" :class="req.ready ? 'ready' : 'not-ready'">
          {{ req.ready ? '就绪' : '未就绪' }}
        </span>
      </div>
      <div class="rl-header-right">
        <span v-if="req.missing_requirements?.length" class="rl-warn">
          ⚠ {{ req.missing_requirements.join(', ') }}
        </span>
        <button class="btn btn-ghost btn-sm" @click="loadAll()">刷新</button>
      </div>
    </div>

    <!-- Tab Bar -->
    <div class="rl-tabs">
      <button class="rl-tab" :class="{active: tab==='envs'}" @click="tab='envs'; loadEnvs()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
        环境
      </button>
      <button class="rl-tab" :class="{active: tab==='config'}" @click="tab='config'">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
        配置
      </button>
      <button class="rl-tab" :class="{active: tab==='training'}" @click="tab='training'; loadRuns()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg>
        训练
      </button>
      <button class="rl-tab" :class="{active: tab==='trajectory'}" @click="tab='trajectory'">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        轨迹
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="loading-row">
      <div class="spinner"/>
      <span>加载中…</span>
    </div>

    <!-- ════════════════ Tab: 环境 ════════════════ -->
    <div v-if="tab==='envs' && !loading" class="rl-content">
      <div class="rl-toolbar">
        <div class="rl-pill">发现 {{ environments.length }} 个环境</div>
        <div class="rl-toolbar-right">
          <button class="btn btn-ghost btn-sm" @click="loadEnvs()">扫描</button>
        </div>
      </div>

      <!-- Requirements check -->
      <div class="rl-req-bar" :class="req.ready ? 'req-ready' : 'req-not-ready'">
        <div v-for="(v, k) in {
          'Python ≥3.11': req.python_311_plus,
          'TINKER_API_KEY': req.has_tinker_key,
          'WANDB_API_KEY': req.has_wandb_key,
          'tinker-atropos': req.has_tinker_submodule
        }" :key="k" class="rl-req-item" :class="v ? 'ok' : 'fail'">
          <span>{{ v ? '✅' : '❌' }} {{ k }}</span>
        </div>
      </div>

      <!-- Environment list -->
      <div class="rl-env-grid" v-if="environments.length">
        <div v-for="env in environments" :key="env.name"
             class="rl-env-card"
             :class="{selected: selectedEnv===env.name}"
             @click="selectEnv(env.name)">
          <div class="rl-env-name">{{ env.name }}</div>
          <div class="rl-env-class">{{ env.class_name }}</div>
          <div class="rl-env-desc">{{ env.description || '—' }}</div>
          <div class="rl-env-path">{{ env.file_path }}</div>
        </div>
      </div>
      <div v-else class="rl-empty">
        未发现任何 RL 环境。<br/>
        确保 tinker-atropos 子模块已正确克隆，且 environments/ 目录存在。
      </div>
    </div>

    <!-- ════════════════ Tab: 配置 ════════════════ -->
    <div v-if="tab==='config' && !loading" class="rl-content">
      <div class="rl-toolbar">
        <div class="rl-pill">当前环境: <strong>{{ currentEnv || '未选择' }}</strong></div>
        <div class="rl-toolbar-right">
          <button class="btn btn-accent btn-sm" :disabled="!selectedEnv || trainingActive" @click="startTraining()">
            🚀 启动训练
          </button>
        </div>
      </div>

      <!-- Configurable fields -->
      <div v-if="config.configurable_fields?.length" class="rl-section-title">可编辑字段</div>
      <div class="rl-config-list" v-if="config.configurable_fields?.length">
        <div v-for="f in config.configurable_fields" :key="f.name" class="rl-config-item">
          <div class="rl-config-header">
            <span class="rl-config-name">{{ f.name }}</span>
            <span class="rl-config-type">{{ f.type }}</span>
          </div>
          <div class="rl-config-desc">{{ f.description }}</div>
          <div class="rl-config-row">
            <span class="rl-config-label">当前值：</span>
            <input class="form-input" style="width:240px" v-model="configValues[f.name]"
                   :placeholder="String(f.default ?? '')" @keydown.enter="editField(f.name)"/>
            <button class="btn btn-ghost btn-sm" @click="editField(f.name)">更新</button>
          </div>
          <div class="rl-config-hint" v-if="editResults[f.name]">
            → {{ editResults[f.name] }}
          </div>
        </div>
      </div>
      <div v-else-if="selectedEnv" class="rl-empty">
        此环境无自定义可编辑字段，或尚未选择环境。
      </div>

      <!-- Locked fields -->
      <div v-if="config.locked_fields?.length" class="rl-section-title" style="margin-top:20px">锁定字段（基础设施）</div>
      <div class="rl-locked-grid" v-if="config.locked_fields?.length">
        <div v-for="f in config.locked_fields" :key="f.name" class="rl-locked-item">
          <span class="rl-locked-name">{{ f.name }}</span>
          <span class="rl-locked-val">{{ f.locked_value }}</span>
        </div>
      </div>
    </div>

    <!-- ════════════════ Tab: 训练 ════════════════ -->
    <div v-if="tab==='training' && !loading" class="rl-content">
      <div class="rl-toolbar">
        <div class="rl-pill">{{ runs.length }} 个训练运行</div>
        <div class="rl-toolbar-right">
          <button class="btn btn-ghost btn-sm" @click="loadRuns()">刷新</button>
        </div>
      </div>

      <div v-if="runs.length" class="rl-runs-list">
        <div v-for="run in runs" :key="run.run_id" class="rl-run-item"
             :class="'status-' + run.status">
          <div class="rl-run-header">
            <div class="rl-run-id">
              <span class="rl-run-name">{{ run.environment }}</span>
              <span class="rl-run-badge" :class="'badge-' + run.status">{{ run.status }}</span>
            </div>
            <div class="rl-run-badges">
              <span class="badge">{{ run.wandb_project }}</span>
              <span class="badge">{{ run.running_time_minutes.toFixed(1) }} min</span>
              <span v-if="run.error" class="badge badge-err">❌ {{ run.error }}</span>
            </div>
          </div>
          <div class="rl-run-actions">
            <button class="btn btn-ghost btn-xs" @click="checkStatus(run.run_id)">检查状态</button>
            <button v-if="run.status==='running'" class="btn btn-ghost btn-xs" @click="stopRun(run.run_id)">停止</button>
            <button class="btn btn-ghost btn-xs" @click="getRunResults(run.run_id)">结果</button>
          </div>

          <!-- Status detail -->
          <div v-if="statusDetail[run.run_id]" class="rl-run-detail">
            <div v-if="statusDetail[run.run_id].metrics" class="rl-metrics">
              <div v-for="(v, k) in statusDetail[run.run_id].metrics" :key="k" class="rl-metric">
                <span class="rl-metric-label">{{ k }}</span>
                <span class="rl-metric-val">{{ v }}</span>
              </div>
            </div>
            <div v-if="statusDetail[run.run_id].logs" class="rl-log-links">
              <span>Logs:</span>
              <a :href="'file://' + statusDetail[run.run_id].logs.api" target="_blank" class="rl-log-link">api</a>
              <a :href="'file://' + statusDetail[run.run_id].logs.trainer" target="_blank" class="rl-log-link">trainer</a>
              <a :href="'file://' + statusDetail[run.run_id].logs.env" target="_blank" class="rl-log-link">env</a>
            </div>
            <div v-if="statusDetail[run.run_id].wandb_url" class="rl-wandb-link">
              <a :href="statusDetail[run.run_id].wandb_url" target="_blank">📊 WandB Dashboard</a>
            </div>
            <div v-if="statusDetail[run.run_id].rate_limited" class="rl-rate-limit">
              ⏳ 速率限制: {{ Math.round(statusDetail[run.run_id].next_check_in_seconds / 60) }} 分钟后可再次检查
            </div>
          </div>
        </div>
      </div>
      <div v-else class="rl-empty">暂无训练运行。先选择环境、配置参数，然后启动训练。</div>
    </div>

    <!-- ════════════════ Tab: 轨迹 ════════════════ -->
    <div v-if="tab==='trajectory' && !loading" class="rl-content">
      <div class="rl-section-title">轨迹压缩</div>
      <div class="rl-compress-form">
        <div class="rl-field-row">
          <label class="form-label">输入目录</label>
          <input class="form-input" style="flex:1" v-model="compressInput" placeholder="D:\data\trajectories"/>
        </div>
        <div class="rl-field-row">
          <label class="form-label">输出目录</label>
          <input class="form-input" style="flex:1" v-model="compressOutput" placeholder="留空则输出到 {input}/compressed"/>
        </div>
        <div class="rl-field-row">
          <label class="form-label">最大 Token</label>
          <input class="form-input" style="width:120px" v-model.number="compressMaxTokens" type="number" placeholder="16384"/>
          <label class="form-label" style="margin-left:16px">采样 %</label>
          <input class="form-input" style="width:80px" v-model.number="compressSample" type="number" placeholder="0=全部" min="0" max="100"/>
        </div>
        <div class="rl-field-row">
          <button class="btn btn-accent btn-sm" @click="compressTrajectories()">压缩轨迹</button>
        </div>
        <div v-if="compressResult" class="rl-compress-result">
          <pre>{{ JSON.stringify(compressResult, null, 2) }}</pre>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import * as api from '../api'

const tab = ref('envs')
const loading = ref(false)

// ── 数据 ──
const req = ref<api.RLRequirements>({ ready: false, python_311_plus: false, has_tinker_key: false, has_wandb_key: false, has_tinker_submodule: false, missing_requirements: [] })
const environments = ref<api.RLEnvironment[]>([])
const selectedEnv = ref('')
const currentEnv = ref('')
const config = ref<any>({ configurable_fields: [], locked_fields: [] })
const configValues = reactive<Record<string, string>>({})
const editResults = reactive<Record<string, string>>({})
const runs = ref<api.RLRunState[]>([])
const statusDetail = reactive<Record<string, any>>({})

// ── 压缩表单 ──
const compressInput = ref('')
const compressOutput = ref('')
const compressMaxTokens = ref(16384)
const compressSample = ref(0)
const compressResult = ref<any>(null)

onMounted(() => loadAll())

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([loadReq(), loadEnvs()])
  } finally { loading.value = false }
}

async function loadReq() {
  req.value = await api.rlGetRequirements().catch(() => req.value)
}

async function loadEnvs() {
  loading.value = true
  try {
    const data = await api.rlListEnvironments()
    environments.value = data.environments || []
  } catch { environments.value = [] }
  loading.value = false
}

async function selectEnv(name: string) {
  selectedEnv.value = name
  loading.value = true
  try {
    const data = await api.rlSelectEnvironment(name)
    currentEnv.value = name
    await loadConfig()
    tab.value = 'config'
  } catch (e) {
    console.error(e)
  } finally { loading.value = false }
}

async function loadConfig() {
  try {
    const data = await api.rlGetConfig()
    config.value = data
    // 初始化 configValues
    for (const f of data.configurable_fields || []) {
      if (configValues[f.name] === undefined) {
        configValues[f.name] = String(f.current_value ?? f.default ?? '')
      }
    }
  } catch (e) {
    console.error(e)
  }
}

async function editField(field: string) {
  const val = configValues[field]
  const result = await api.rlEditConfig(field, val).catch(() => ({ error: '请求失败' }))
  editResults[field] = result.error || result.message || JSON.stringify(result)
  setTimeout(() => { delete editResults[field] }, 3000)
}

async function startTraining() {
  loading.value = true
  try {
    const result = await api.rlStartTraining()
    if (result.error) {
      alert('启动失败: ' + result.error)
    } else {
      tab.value = 'training'
      await loadRuns()
    }
  } catch (e) {
    alert('请求失败: ' + e)
  } finally { loading.value = false }
}

const trainingActive = ref(false)

async function loadRuns() {
  loading.value = true
  try {
    const data = await api.rlListRuns()
    runs.value = data.runs || []
    trainingActive.value = runs.value.some(r => r.status === 'running' || r.status === 'starting')
  } catch { runs.value = [] }
  loading.value = false
}

async function checkStatus(runId: string) {
  try {
    const result = await api.rlCheckStatus(runId)
    if (result.rate_limited) {
      statusDetail[runId] = { rate_limited: true, next_check_in_seconds: result.next_check_in_seconds }
    } else {
      statusDetail[runId] = result
    }
  } catch { statusDetail[runId] = { error: '请求失败' } }
}

async function stopRun(runId: string) {
  const result = await api.rlStopTraining(runId).catch(() => ({ error: '请求失败' }))
  statusDetail[runId] = result
  await loadRuns()
}

async function getRunResults(runId: string) {
  const result = await api.rlGetResults(runId).catch(() => ({ error: '请求失败' }))
  statusDetail[runId] = result
}

async function compressTrajectories() {
  if (!compressInput.value.trim()) {
    alert('请输入输入目录')
    return
  }
  compressResult.value = await api.rlCompressTrajectories(
    compressInput.value.trim(),
    compressOutput.value.trim() || undefined,
    compressMaxTokens.value,
    compressSample.value
  ).catch(e => ({ error: String(e) }))
}
</script>

<style scoped>
.rl-view {
  height: 100%; display: flex; flex-direction: column; gap: 14px; overflow: hidden;
}

.rl-header {
  display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px;
  padding: 12px 16px; background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.07); border-radius: 10px;
}
.rl-title { display: flex; align-items: center; gap: 10px; font-size: 16px; font-weight: 700; }
.rl-emoji { font-size: 20px; }
.rl-badge { font-size: 12px; padding: 3px 10px; border-radius: 20px; font-weight: 600; }
.rl-badge.ready { background: rgba(61,220,151,0.15); color: var(--accent); border: 1px solid rgba(61,220,151,0.3); }
.rl-badge.not-ready { background: rgba(255,107,107,0.1); color: var(--accent-red); border: 1px solid rgba(255,107,107,0.3); }
.rl-header-right { display: flex; align-items: center; gap: 12px; }
.rl-warn { font-size: 12px; color: rgba(245,166,35,0.9); }

.rl-tabs {
  display: flex; gap: 4px; border-bottom: 1px solid rgba(255,255,255,0.08); padding-bottom: 4px;
}
.rl-tab {
  display: flex; align-items: center; gap: 6px; padding: 8px 16px; background: none; border: none;
  border-radius: 8px; color: rgba(255,255,255,0.45); cursor: pointer; font-size: 13px;
  transition: all 0.2s;
}
.rl-tab svg { width: 14px; height: 14px; }
.rl-tab:hover { color: rgba(255,255,255,0.8); background: rgba(255,255,255,0.05); }
.rl-tab.active { color: var(--accent); background: rgba(61,220,151,0.12); font-weight: 600; }

.rl-content { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; padding-right: 4px; }

.rl-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.rl-toolbar-right { display: flex; align-items: center; gap: 8px; }
.rl-pill {
  padding: 4px 12px; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20px; font-size: 12px; color: rgba(255,255,255,0.6);
}

.rl-req-bar {
  display: flex; gap: 12px; padding: 12px 16px; border-radius: 10px; flex-wrap: wrap;
}
.rl-req-bar.req-ready { background: rgba(61,220,151,0.06); border: 1px solid rgba(61,220,151,0.2); }
.rl-req-bar.req-not-ready { background: rgba(255,107,107,0.06); border: 1px solid rgba(255,107,107,0.2); }
.rl-req-item { font-size: 12px; }
.rl-req-item.ok { color: var(--accent); }
.rl-req-item.fail { color: var(--accent-red); }

.rl-env-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.rl-env-card {
  background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.07);
  border-radius: 12px; padding: 14px 16px; cursor: pointer; transition: all 0.2s;
  display: flex; flex-direction: column; gap: 6px;
}
.rl-env-card:hover { border-color: rgba(61,220,151,0.3); background: rgba(61,220,151,0.04); }
.rl-env-card.selected { border-color: rgba(61,220,151,0.5); background: rgba(61,220,151,0.08); }
.rl-env-name { font-size: 14px; font-weight: 700; color: var(--accent); }
.rl-env-class { font-size: 11px; color: rgba(255,255,255,0.4); font-family: monospace; }
.rl-env-desc { font-size: 12px; color: rgba(255,255,255,0.5); }
.rl-env-path { font-size: 10px; color: rgba(255,255,255,0.25); font-family: monospace; word-break: break-all; }

.rl-section-title { font-size: 12px; font-weight: 600; color: rgba(255,255,255,0.4); text-transform: uppercase; letter-spacing: 0.08em; }

.rl-config-list { display: flex; flex-direction: column; gap: 12px; }
.rl-config-item {
  background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.07);
  border-radius: 10px; padding: 12px 16px; display: flex; flex-direction: column; gap: 6px;
}
.rl-config-header { display: flex; align-items: center; gap: 8px; }
.rl-config-name { font-size: 13px; font-weight: 600; color: var(--accent); font-family: monospace; }
.rl-config-type { font-size: 10px; color: rgba(255,255,255,0.3); background: rgba(255,255,255,0.06); padding: 1px 6px; border-radius: 6px; }
.rl-config-desc { font-size: 12px; color: rgba(255,255,255,0.45); }
.rl-config-row { display: flex; align-items: center; gap: 8px; }
.rl-config-label { font-size: 11px; color: rgba(255,255,255,0.35); min-width: 56px; }
.rl-config-hint { font-size: 11px; color: var(--accent); }

.rl-locked-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 8px; }
.rl-locked-item {
  display: flex; justify-content: space-between; align-items: center;
  background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.06);
  border-radius: 8px; padding: 8px 12px; font-size: 12px;
}
.rl-locked-name { font-family: monospace; color: rgba(255,255,255,0.5); }
.rl-locked-val { font-family: monospace; color: rgba(255,255,255,0.25); font-size: 11px; }

.rl-runs-list { display: flex; flex-direction: column; gap: 10px; }
.rl-run-item {
  background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.07);
  border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 8px;
}
.rl-run-item.status-running { border-color: rgba(61,220,151,0.3); }
.rl-run-item.status-failed { border-color: rgba(255,107,107,0.3); }
.rl-run-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.rl-run-id { display: flex; align-items: center; gap: 8px; }
.rl-run-name { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.rl-run-badge { font-size: 11px; padding: 2px 8px; border-radius: 12px; font-weight: 600; }
.badge-running { background: rgba(61,220,151,0.15); color: var(--accent); }
.badge-completed { background: rgba(61,220,151,0.1); color: var(--accent); }
.badge-failed, .badge-stopped { background: rgba(255,107,107,0.1); color: var(--accent-red); }
.badge-pending, .badge-starting { background: rgba(245,166,35,0.1); color: var(--accent-amber); }
.rl-run-badges { display: flex; gap: 4px; flex-wrap: wrap; }
.badge-err { background: rgba(255,107,107,0.15); color: var(--accent-red); border-color: rgba(255,107,107,0.3); }
.rl-run-actions { display: flex; gap: 6px; }
.rl-run-detail { padding: 10px 12px; background: rgba(0,0,0,0.2); border-radius: 8px; }
.rl-metrics { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 8px; }
.rl-metric { display: flex; flex-direction: column; gap: 2px; }
.rl-metric-label { font-size: 10px; color: rgba(255,255,255,0.35); }
.rl-metric-val { font-size: 14px; font-weight: 700; color: var(--accent); }
.rl-log-links { display: flex; align-items: center; gap: 8px; font-size: 12px; color: rgba(255,255,255,0.4); margin-top: 6px; }
.rl-log-link { color: var(--accent); font-family: monospace; font-size: 11px; }
.rl-wandb-link a { color: var(--accent); font-size: 12px; }
.rl-rate-limit { font-size: 12px; color: rgba(245,166,35,0.9); }

.rl-empty { font-size: 13px; color: var(--text-muted); padding: 24px; text-align: center; line-height: 1.6; }

.rl-compress-form { display: flex; flex-direction: column; gap: 10px; }
.rl-field-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.rl-compress-result { margin-top: 8px; }
.rl-compress-result pre { font-size: 12px; color: rgba(255,255,255,0.6); background: rgba(0,0,0,0.2); border-radius: 8px; padding: 12px; max-height: 300px; overflow: auto; }

.loading-row { display: flex; align-items: center; gap: 10px; padding: 32px; color: rgba(255,255,255,0.4); font-size: 13px; justify-content: center; }
.spinner { width: 16px; height: 16px; border: 2px solid rgba(255,255,255,0.1); border-top-color: var(--accent); border-radius: 50%; animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.badge { padding: 2px 8px; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1); border-radius: 20px; font-size: 11px; color: rgba(255,255,255,0.5); }
</style>
