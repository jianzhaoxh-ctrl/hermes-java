<template>
  <div class="page">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px">
      <div style="font-size:14px;font-weight:600;color:var(--text-primary)">
        子 Agent ({{ subagents.length }} 个)
      </div>
      <button class="btn btn-primary" @click="showModal=true">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        启动 Agent
      </button>
    </div>

    <div class="card">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>任务</th>
              <th>会话</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>结果 / 错误</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in subagents" :key="a.id">
              <td class="mono accent">{{ a.id }}</td>
              <td style="max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ a.task }}</td>
              <td class="mono">{{ a.parentSessionId }}</td>
              <td><span :class="badge(a.status)">{{ statusLabel(a.status) }}</span></td>
              <td class="mono">{{ new Date(a.createdAt).toLocaleString('zh-CN') }}</td>
              <td style="max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--text-muted)">
                {{ a.result ? a.result.substring(0,50)+'...' : a.error || '-' }}
              </td>
              <td>
                <button class="btn btn-danger btn-xs" @click="kill(a.id)">终止</button>
              </td>
            </tr>
            <tr v-if="!subagents.length">
              <td colspan="7" style="text-align:center;padding:40px;color:var(--text-muted)">暂无子 Agent</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 启动弹窗 -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal=false">
      <div class="modal">
        <div class="modal-header">
          <div class="modal-title">启动子 Agent</div>
          <button class="modal-close" @click="showModal=false">x</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">任务描述 *</label>
            <textarea class="form-textarea" v-model="form.task" placeholder="描述子 Agent 需要完成的任务..." rows="4"></textarea>
          </div>
          <div class="form-group">
            <label class="form-label">会话 ID</label>
            <input class="form-input" v-model="form.sessionId" placeholder="default"/>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" @click="showModal=false">取消</button>
          <button class="btn btn-primary" @click="spawn">启动</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import * as api from '../api'

const props = defineProps<{ subagents: any[] }>()
const emit = defineEmits(['refresh'])

const showModal = ref(false)
const form = reactive({ task: '', sessionId: 'default' })

function badge(s: string) {
  if (s === 'running') return 'badge badge-running'
  if (s === 'completed') return 'badge badge-completed'
  return 'badge badge-failed'
}
function statusLabel(s: string) {
  if (s === 'running') return '运行中'
  if (s === 'completed') return '已完成'
  if (s === 'failed') return '失败'
  if (s === 'active') return '活跃'
  return s
}

async function spawn() {
  if (!form.task) return
  try {
    await api.spawnSubAgent(form.task, form.sessionId)
    showModal.value = false
    form.task = ''
    emit('refresh')
  } catch {}
}

async function kill(id: string) {
  try { await api.killSubAgent(id); emit('refresh') } catch {}
}
</script>
