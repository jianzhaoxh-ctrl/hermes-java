<template>
  <div class="page">
    <div class="grid-2">
      <div class="card">
        <div class="card-header"><div class="card-title">API 配置</div></div>
        <div style="margin-top:4px">
          <div class="form-group">
            <label class="form-label">API Base URL</label>
            <input class="form-input" value="https://dashscope.aliyuncs.com/compatible-mode/v1" readonly/>
          </div>
          <div class="form-group">
            <label class="form-label">默认模型</label>
            <input class="form-input" value="qwen-plus" readonly/>
          </div>
          <div class="form-group">
            <label class="form-label">最大历史消息数</label>
            <input class="form-input" value="50" readonly/>
          </div>
          <div class="form-group">
            <label class="form-label">最大 Token 数</label>
            <input class="form-input" value="4096" readonly/>
          </div>
          <div class="form-group">
            <label class="form-label">Temperature</label>
            <input class="form-input" value="0.7" readonly/>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-header"><div class="card-title">系统信息</div></div>
        <div style="margin-top:4px">
          <div class="form-group">
            <label class="form-label">框架</label>
            <input class="form-input" value="Spring Boot 3.2 + WebFlux" readonly/>
          </div>
          <div class="form-group">
            <label class="form-label">服务端口</label>
            <input class="form-input" value="8993" readonly/>
          </div>
          <div class="form-group">
            <label class="form-label">Java 版本</label>
            <input class="form-input" value="Java 21" readonly/>
          </div>
          <div class="form-group">
            <label class="form-label">Redis</label>
            <input class="form-input" value="127.0.0.1:6379/db7" readonly/>
          </div>
          <div class="form-group">
            <label class="form-label">流式响应</label>
            <input class="form-input" value="已启用" readonly/>
          </div>
        </div>
      </div>
    </div>

    <div class="card" style="margin-top:20px">
      <div class="card-header"><div class="card-title">REST API 端点</div></div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>方法</th><th>端点</th><th>说明</th></tr></thead>
          <tbody>
            <tr><td><span class="badge badge-running">POST</span></td><td class="mono">/api/chat</td><td>发送聊天消息</td></tr>
            <tr><td><span class="badge badge-running">GET</span></td><td class="mono">/api/sessions</td><td>获取活跃会话列表</td></tr>
            <tr><td><span class="badge badge-failed">DELETE</span></td><td class="mono">/api/sessions/{id}</td><td>清除指定会话</td></tr>
            <tr><td><span class="badge badge-running">GET</span></td><td class="mono">/api/scheduler/jobs</td><td>获取定时任务列表</td></tr>
            <tr><td><span class="badge badge-running">POST</span></td><td class="mono">/api/scheduler/jobs</td><td>创建定时任务</td></tr>
            <tr><td><span class="badge badge-failed">DELETE</span></td><td class="mono">/api/scheduler/jobs/{id}</td><td>删除定时任务</td></tr>
            <tr><td><span class="badge badge-running">GET</span></td><td class="mono">/api/subagents</td><td>获取子 Agent 列表</td></tr>
            <tr><td><span class="badge badge-running">POST</span></td><td class="mono">/api/subagents</td><td>启动子 Agent</td></tr>
            <tr><td><span class="badge badge-failed">DELETE</span></td><td class="mono">/api/subagents/{id}</td><td>终止子 Agent</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
