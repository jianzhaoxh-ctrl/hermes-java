<template>
  <div class="chat-container">
    <div class="session-selector" style="padding:12px 28px 0;flex-shrink:0">
      <label class="form-label" style="margin:0;align-self:center;margin-right:8px;font-size:11px">会话：</label>
      <select class="session-select" v-model="selectedSessionOption">
        <option v-for="s in sessions" :key="s" :value="s">{{ s }}</option>
        <option value="__custom__">🔍 自定义会话</option>
        <option value="__new__">+ 新建会话</option>
      </select>
      <input
        v-if="selectedSessionOption === '__custom__'"
        class="form-input"
        style="width:180px"
        v-model="customSessionName"
        placeholder="输入会话ID..."
        @keydown.enter="switchToCustomSession"
      />
      <button
        v-if="selectedSessionOption === '__custom__'"
        class="btn btn-ghost btn-sm"
        @click="switchToCustomSession"
        :disabled="!customSessionName.trim()"
      >
        {{ isExistingSession(customSessionName.trim()) ? '切换' : '创建' }}
      </button>
      <span v-if="selectedSessionOption === '__custom__' && customSessionName.trim() && !isExistingSession(customSessionName.trim())"
            class="session-hint">
        ⚠ 输入的会话不存在，将创建新会话
      </span>
      <input
        v-if="selectedSessionOption === '__new__'"
        class="form-input"
        style="width:180px"
        v-model="newSessionName"
        placeholder="输入会话名称..."
        @keydown.enter="createAndSwitch"
      />
      <button
        v-if="selectedSessionOption === '__new__'"
        class="btn btn-ghost btn-sm"
        @click="createAndSwitch"
      >
        + 新建
      </button>
    </div>

    <div class="chat-messages" ref="msgEl">
      <div v-for="(msg, i) in messages" :key="i" class="chat-message" :class="{user: msg.role==='user'}">
        <div class="msg-avatar" :class="msg.role==='user' ? 'user-avatar' : 'bot'">
          {{ msg.role === 'user' ? 'U' : 'H' }}
        </div>
        <div class="msg-bubble markdown-content" v-html="renderMarkdown(msg.content)"/>
      </div>

      <div v-if="loading" class="chat-message">
        <div class="msg-avatar bot">
          <div class="spinner" style="width:12px;height:12px;border-width:1.5px"/>
        </div>
        <div class="msg-bubble" style="color:var(--text-muted);font-style:italic">思考中...</div>
      </div>

      <div v-if="!messages.length && !loading" class="empty-state" style="margin:auto;padding:40px">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
        </svg>
        <h3>开始对话</h3>
        <p>在下方发送消息，与 Hermes Agent 开始交流</p>
      </div>
    </div>

    <div class="chat-input-area">
      <div class="chat-input-row">
        <textarea
          class="chat-input"
          v-model="inputText"
          placeholder="输入消息...（回车发送，Shift+回车换行）"
          rows="1"
          @keydown.enter.prevent="send"
          @input="autoResize"
          ref="inputEl"
        />
        <button class="btn btn-primary" @click="send" :disabled="loading || !inputText.trim()">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>
          </svg>
          发送
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch, computed } from 'vue'

// ── Simple Markdown renderer (no external dependency) ──
function renderMarkdown(text: string): string {
  if (!text) return ''
  let html = text
    // Escape HTML to prevent XSS
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    // Code blocks (``` ... ```)
    .replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
      const langLabel = lang ? `<span class="code-lang">${lang}</span>` : ''
      return `<pre class="code-block">${langLabel}<code>${code.trim()}</code></pre>`
    })
    // Inline code
    .replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>')
    // Bold
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    // Italic
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    // Strikethrough
    .replace(/~~(.+?)~~/g, '<del>$1</del>')
    // Links
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>')
    // Unordered lists
    .replace(/^[\s]*[-*] (.+)$/gm, '<li>$1</li>')
    // Ordered lists
    .replace(/^[\s]*\d+\. (.+)$/gm, '<li>$1</li>')
    // Wrap consecutive <li> in <ul>
    .replace(/(<li>.*<\/li>\n?)+/g, (match) => `<ul>${match}</ul>`)
    // Headers
    .replace(/^### (.+)$/gm, '<h4>$1</h4>')
    .replace(/^## (.+)$/gm, '<h3>$1</h3>')
    .replace(/^# (.+)$/gm, '<h2>$1</h2>')
    // Horizontal rule
    .replace(/^---$/gm, '<hr/>')
    // Line breaks → <br>
    .replace(/\n/g, '<br/>')
    // Clean up: remove <br> around block elements
    .replace(/<br\s*\/>\s*(<pre|<ul|<h[2-4]|<hr)/g, '$1')
    .replace(/(<\/pre>|<\/ul>|<\/h[2-4]>|<hr\/>)\s*<br\s*\/>/g, '$1')
  return html
}
import * as api from '../api'
import { sessionStore } from '../sessionStore'

const props = defineProps<{ sessions?: string[]; initialSessionId?: string | null }>()

// 会话列表（优先用 props，否则自主加载）
const sessions = ref<string[]>(props.sessions || [])
const sessionsLoaded = ref(false)

// 会话选择状态
const selectedSessionOption = ref('default')
const customSessionName = ref('')
const newSessionName = ref('')
const messages = ref<{role:string; content:string}[]>([])
const inputText = ref('')
const loading = ref(false)
const msgEl = ref<HTMLElement>()
const inputEl = ref<HTMLTextAreaElement>()

function isExistingSession(name: string): boolean {
  return sessions.value.includes(name)
}

// 自主加载会话列表
async function loadSessions() {
  if (sessionsLoaded.value) return
  try {
    const res = await api.getSessions()
    sessions.value = res.activeSessions || []
    sessionsLoaded.value = true
  } catch (e) {
    console.error('加载会话列表失败', e)
  }
}

// 初始化加载
loadSessions()

// 当 external 传入 initialSessionId 时自动切换
watch(() => props.initialSessionId, (newId) => {
  if (newId && newId !== selectedSessionOption.value) {
    selectedSessionOption.value = newId
  }
}, { immediate: true })

// 计算属性：获取当前实际的会话 ID
const currentSessionId = computed(() => {
  if (selectedSessionOption.value === '__custom__') {
    return customSessionName.value.trim() || 'default'
  } else if (selectedSessionOption.value === '__new__') {
    return newSessionName.value.trim() || 'default'
  } else {
    return selectedSessionOption.value
  }
})

// 当会话切换时清空消息并加载历史
watch(currentSessionId, async () => {
  messages.value = []
  const sid = currentSessionId.value
  if (!sid || sid === 'default') return
  try {
    const data = await api.getSessionMessages(sid)
    messages.value = (data.messages || []).map((m: any) => ({
      role: m.role || 'user',
      content: m.content || '',
    }))
  } catch (e) {
    // no history yet — normal for new sessions
  }
})

function autoResize(e: Event) {
  const el = e.target as HTMLTextAreaElement
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

async function scrollToBottom() {
  await nextTick()
  if (msgEl.value) msgEl.value.scrollTop = msgEl.value.scrollHeight
}

async function send() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  const sid = currentSessionId.value

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  if (inputEl.value) { inputEl.value.style.height = 'auto' }
  loading.value = true
  await scrollToBottom()

  // Use SSE streaming for progressive response
  const assistantMsg = { role: 'assistant' as const, content: '' }
  messages.value.push(assistantMsg)

  try {
    const generator = api.streamChat(text, sid)
    for await (const chunk of generator) {
      assistantMsg.content += chunk
      await scrollToBottom()
    }
    if (!assistantMsg.content) {
      assistantMsg.content = '（空响应）'
    }
  } catch (e: any) {
    assistantMsg.content = 'Error: ' + (e?.message || 'Request failed')
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

function createAndSwitch() {
  const name = newSessionName.value.trim()
  if (name) {
    if (!sessions.value.includes(name)) {
      sessions.value.unshift(name)
    }
    selectedSessionOption.value = name
    sessionStore.currentSessionId = name
    newSessionName.value = ''
  }
}

function switchToCustomSession() {
  const name = customSessionName.value.trim()
  if (name) {
    if (!sessions.value.includes(name)) {
      sessions.value.unshift(name)
    }
    selectedSessionOption.value = name
    sessionStore.currentSessionId = name
    customSessionName.value = ''
  }
}
</script>

<style scoped>
/* Markdown rendering in chat messages */
.markdown-content {
  line-height: 1.6;
}

.markdown-content :deep(pre.code-block) {
  background: var(--card-bg, #0f1320);
  border: 1px solid var(--border, #1e2438);
  border-radius: 6px;
  padding: 12px 16px;
  margin: 8px 0;
  overflow-x: auto;
  position: relative;
}

.markdown-content :deep(.code-lang) {
  position: absolute;
  top: 6px;
  right: 10px;
  font-size: 10px;
  color: var(--text-muted, #6b7280);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.markdown-content :deep(pre.code-block code) {
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text, #e5e7eb);
  white-space: pre;
}

.markdown-content :deep(code.inline-code) {
  background: var(--card-bg, #0f1320);
  border: 1px solid var(--border, #1e2438);
  border-radius: 4px;
  padding: 1px 6px;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  font-size: 12px;
  color: #f472b6;
}

.markdown-content :deep(strong) {
  color: #fff;
  font-weight: 600;
}

.markdown-content :deep(em) {
  font-style: italic;
  color: var(--text-muted, #9ca3af);
}

.markdown-content :deep(h2) {
  font-size: 16px;
  margin: 12px 0 6px;
  color: #fff;
}

.markdown-content :deep(h3) {
  font-size: 14px;
  margin: 10px 0 4px;
  color: var(--text-muted, #d1d5db);
}

.markdown-content :deep(h4) {
  font-size: 13px;
  margin: 8px 0 4px;
  color: var(--text-muted, #d1d5db);
}

.markdown-content :deep(ul) {
  margin: 4px 0;
  padding-left: 20px;
}

.markdown-content :deep(li) {
  margin: 2px 0;
}

.markdown-content :deep(hr) {
  border: none;
  border-top: 1px solid var(--border, #1e2438);
  margin: 12px 0;
}

.markdown-content :deep(a) {
  color: #3ddc97;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.markdown-content :deep(a:hover) {
  color: #5eead4;
}
</style>