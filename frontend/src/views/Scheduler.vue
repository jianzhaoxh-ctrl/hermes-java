<template>
  <div class="page">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px">
      <div style="font-size:14px;font-weight:600;color:var(--text-primary)">定时任务 ({{ jobs.length }})</div>
      <button class="btn btn-primary" @click="showModal=true">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        新建任务
      </button>
    </div>

    <div class="card">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>任务 ID</th>
              <th>任务描述</th>
              <th>Cron</th>
              <th>会话</th>
              <th>上次执行</th>
              <th>执行次数</th>
              <th>下次执行</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="j in jobs" :key="j.id">
              <td class="mono">{{ j.id }}</td>
              <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ j.task }}</td>
              <td class="mono accent">{{ j.expression }}</td>
              <td class="mono">{{ j.sessionId }}</td>
              <td class="mono">{{ j.lastRun ? new Date(j.lastRun).toLocaleString('zh-CN') : '-' }}</td>
              <td class="mono">{{ j.runCount }}</td>
              <td class="mono">{{ j.nextRun || '-' }}</td>
              <td>
                <button class="btn btn-danger btn-xs" @click="removeJob(j.id)">删除</button>
              </td>
            </tr>
            <tr v-if="!jobs.length">
              <td colspan="8" style="text-align:center;padding:40px;color:var(--text-muted)">暂无定时任务</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Cron 参考表 -->
    <div class="card" style="margin-top:20px">
      <div class="card-header"><div class="card-title">Cron 表达式参考</div></div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>表达式</th><th>含义</th><th>示例</th></tr></thead>
          <tbody>
            <tr v-for="c in cronExamples" :key="c.expr">
              <td class="mono accent">{{ c.expr }}</td>
              <td>{{ c.meaning }}</td>
              <td class="mono">{{ c.example }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新建任务弹窗 -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal=false">
      <div class="modal">
        <div class="modal-header">
          <div class="modal-title">新建定时任务</div>
          <button class="modal-close" @click="showModal=false">x</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">Cron 表达式 *</label>
            <input class="form-input" v-model="form.cron" placeholder="*/5 * * * *"/>
            <div class="form-hint">格式：分 时 日 月 周</div>
          </div>
          <div class="form-group">
            <label class="form-label">任务描述 *</label>
            <textarea class="form-textarea" v-model="form.task" placeholder="描述这个任务要做什么..."></textarea>
          </div>
          <div class="form-group">
            <label class="form-label">会话 ID</label>
            <input class="form-input" v-model="form.sessionId" placeholder="default"/>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" @click="showModal=false">取消</button>
          <button class="btn btn-primary" @click="submit">创建</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import * as api from '../api'

const props = defineProps<{ jobs: any[] }>()
const emit = defineEmits(['refresh'])

const showModal = ref(false)
const form = reactive({ cron: '', task: '', sessionId: 'default' })

const cronExamples = [
  { expr: '*/5 * * * *', meaning: '每 5 分钟', example: '*/10 * * * *' },
  { expr: '0 * * * *', meaning: '每小时整点', example: '0 9 * * *' },
  { expr: '0 9 * * *', meaning: '每天 9:00', example: '0 */2 * * *' },
  { expr: '*/2 * * * *', meaning: '每 2 分钟', example: '0 0 * * 0' },
]

async function submit() {
  if (!form.cron || !form.task) return
  try {
    await api.createJob(form.cron, form.task, form.sessionId)
    showModal.value = false
    form.cron = ''; form.task = ''
    emit('refresh')
  } catch {}
}

async function removeJob(id: string) {
  try { await api.deleteJob(id); emit('refresh') } catch {}
}
</script>
