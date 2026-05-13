/**
 * 共享会话状态 — 所有页面共用同一个 currentSessionId
 * 解决前台 session 与 API 请求 user1 不一致的问题
 */
import { reactive } from 'vue'

export interface SessionStore {
  currentSessionId: string
  allSessions: string[]
}

export const sessionStore = reactive<SessionStore>({
  currentSessionId: 'default',
  allSessions: [],
})

export function setCurrentSession(id: string) {
  sessionStore.currentSessionId = id
  // 同时更新 URL hash，方便分享
  window.location.hash = id
}

export function initSessionFromUrl() {
  const hash = window.location.hash.replace('#', '').trim()
  if (hash) sessionStore.currentSessionId = hash
}

// 启动时从 URL 恢复会话
if (typeof window !== 'undefined') {
  initSessionFromUrl()
  window.addEventListener('hashchange', () => {
    const hash = window.location.hash.replace('#', '').trim()
    if (hash) sessionStore.currentSessionId = hash
  })
}
