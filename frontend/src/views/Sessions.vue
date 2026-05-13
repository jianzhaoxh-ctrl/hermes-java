<template>
  <div class="page">
    <div class="card">
      <div class="card-header">
        <div class="card-title">活跃会话 ({{ sessions.length }})</div>
        <button class="btn btn-ghost btn-sm" @click="$emit('refresh')">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
          刷新
        </button>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>会话 ID</th><th>来源</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="s in sessions" :key="s">
              <td class="mono accent">{{ s }}</td>
              <td class="mono" style="color:var(--text-muted)">local</td>
              <td>
                <button class="btn btn-ghost btn-xs" @click="$emit('chat', s); selectSession(s)">对话</button>
                <button class="btn btn-danger btn-xs" style="margin-left:6px" @click="clearSession(s)">清除</button>
              </td>
            </tr>
            <tr v-if="!sessions.length">
              <td colspan="3" style="text-align:center;padding:40px;color:var(--text-muted)">暂无活跃会话</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import * as api from '../api'

const props = defineProps<{ sessions: string[] }>()
const emit = defineEmits(['refresh', 'chat'])
const selected = ref('')

function selectSession(s: string) { selected.value = s }

async function clearSession(sid: string) {
  try {
    await api.deleteSession(sid)
    emit('refresh')
  } catch { /* ignore */ }
}
</script>
