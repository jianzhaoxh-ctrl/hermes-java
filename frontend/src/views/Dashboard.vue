<template>
  <div class="page">
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-label">活跃会话</div>
        <div class="stat-value accent">{{ stats.sessionCount }}</div>
        <div class="stat-delta">实时对话会话数</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">定时任务</div>
        <div class="stat-value amber">{{ stats.jobCount }}</div>
        <div class="stat-delta">已激活的 Cron 任务</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">运行中 Agent</div>
        <div class="stat-value blue">{{ stats.subagentCount }}</div>
        <div class="stat-delta">并行子 Agent 数量</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">LLM 模型</div>
        <div class="stat-value" style="font-size:14px;padding-top:6px;color:var(--accent)">{{ stats.modelName }}</div>
        <div class="stat-delta">DashScope / Qwen</div>
      </div>
    </div>

    <div class="grid-2">
      <div class="card">
        <div class="card-header">
          <div class="card-title">最近会话</div>
          <button class="btn btn-ghost btn-xs" @click="$emit('navigate','sessions')">查看全部 →</button>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>会话 ID</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="s in sessions.slice(0,8)" :key="s">
                <td class="mono accent">{{ s }}</td>
                <td><span :class="badgeClass('active')">{{ statusLabel('active') }}</span></td>
              </tr>
              <tr v-if="!sessions.length">
                <td colspan="2" style="text-align:center;padding:24px;color:var(--text-muted)">暂无会话 — 开始对话吧！</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card">
        <div class="card-header">
          <div class="card-title">定时任务</div>
          <button class="btn btn-ghost btn-xs" @click="$emit('navigate','scheduler')">管理 →</button>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>任务描述</th><th>Cron</th><th>执行次数</th></tr></thead>
            <tbody>
              <tr v-for="j in jobs.slice(0,8)" :key="j.id">
                <td style="max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ j.task }}</td>
                <td class="mono">{{ j.expression }}</td>
                <td class="mono">{{ j.runCount }}</td>
              </tr>
              <tr v-if="!jobs.length">
                <td colspan="3" style="text-align:center;padding:24px;color:var(--text-muted)">暂无定时任务</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="card" style="margin-top:20px">
      <div class="card-header">
        <div class="card-title">运行中的子 Agent</div>
        <button class="btn btn-ghost btn-xs" @click="$emit('navigate','subagents')">查看全部 →</button>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>ID</th><th>任务</th><th>会话</th><th>状态</th><th>创建时间</th></tr></thead>
          <tbody>
            <tr v-for="a in subagents.slice(0,10)" :key="a.id">
              <td class="mono accent">{{ a.id }}</td>
              <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ a.task }}</td>
              <td class="mono">{{ a.parentSessionId }}</td>
              <td><span :class="badgeClass(a.status)">{{ statusLabel(a.status) }}</span></td>
              <td class="mono">{{ new Date(a.createdAt).toLocaleTimeString() }}</td>
            </tr>
            <tr v-if="!subagents.length">
              <td colspan="5" style="text-align:center;padding:24px;color:var(--text-muted)">暂无运行中的子 Agent</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  stats: { sessionCount: number; jobCount: number; subagentCount: number; modelName: string }
  sessions: string[]
  jobs: any[]
  subagents: any[]
}>()
defineEmits(['navigate'])

function badgeClass(status: string) {
  if (status === 'running') return 'badge badge-running'
  if (status === 'completed') return 'badge badge-completed'
  if (status === 'active') return 'badge badge-active'
  return 'badge badge-failed'
}
function statusLabel(s: string) {
  if (s === 'running') return '运行中'
  if (s === 'completed') return '已完成'
  if (s === 'active') return '活跃'
  if (s === 'failed') return '失败'
  return s
}
</script>
