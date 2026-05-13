package com.hermes.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Gateway 生命周期钩子系统
 * 
 * 参考 Python 版 gateway/hooks.py 实现。
 * 
 * 支持的钩子点：
 * - on_message_received: 收到消息时（在 Agent 处理前）
 * - on_processing_start: 开始处理时
 * - on_processing_complete: 处理完成时
 * - on_message_sent: 消息发送后
 * - on_error: 发生错误时
 * - on_session_reset: 会话重置时
 * - on_platform_connected: 平台连接成功时
 * - on_platform_disconnected: 平台断开连接时
 * - on_boot: Gateway 启动时
 * - on_shutdown: Gateway 关闭时
 */
@Component
public class GatewayHooks {
    private static final Logger log = LoggerFactory.getLogger(GatewayHooks.class);

    /**
     * 钩子点枚举
     */
    public enum HookPoint {
        ON_MESSAGE_RECEIVED("on_message_received"),
        ON_PROCESSING_START("on_processing_start"),
        ON_PROCESSING_COMPLETE("on_processing_complete"),
        ON_MESSAGE_SENT("on_message_sent"),
        ON_ERROR("on_error"),
        ON_SESSION_RESET("on_session_reset"),
        ON_PLATFORM_CONNECTED("on_platform_connected"),
        ON_PLATFORM_DISCONNECTED("on_platform_disconnected"),
        ON_BOOT("on_boot"),
        ON_SHUTDOWN("on_shutdown");

        private final String value;
        HookPoint(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /**
     * 钩子上下文
     */
    public static class HookContext {
        private final HookPoint point;
        private final Map<String, Object> data;
        private boolean cancelled = false;
        private String cancelReason = null;

        public HookContext(HookPoint point) {
            this.point = point;
            this.data = new HashMap<>();
        }

        public HookContext(HookPoint point, Map<String, Object> data) {
            this.point = point;
            this.data = data != null ? new HashMap<>(data) : new HashMap<>();
        }

        public HookPoint getPoint() { return point; }
        public Map<String, Object> getData() { return data; }
        public <T> T get(String key, Class<T> type) { return type.cast(data.get(key)); }
        public <T> T get(String key, Class<T> type, T defaultValue) {
            Object val = data.get(key);
            return val != null ? type.cast(val) : defaultValue;
        }
        public void put(String key, Object value) { data.put(key, value); }

        public boolean isCancelled() { return cancelled; }
        public String getCancelReason() { return cancelReason; }
        public void cancel(String reason) {
            this.cancelled = true;
            this.cancelReason = reason;
        }
    }

    /**
     * 钩子函数接口
     */
    @FunctionalInterface
    public interface Hook {
        /**
         * 执行钩子逻辑
         * 
         * @param context 钩子上下文
         * @throws Exception 钩子执行异常
         */
        void execute(HookContext context) throws Exception;
    }

    /**
     * 内置钩子：启动时加载 AGENTS.md 等文件
     * 
     * 参考 Python 版 builtin_hooks/boot_md.py
     */
    public static class BootMdHook implements Hook {
        private final String workspaceDir;

        public BootMdHook(String workspaceDir) {
            this.workspaceDir = workspaceDir;
        }

        @Override
        public void execute(HookContext context) throws Exception {
            // 在 Gateway 启动时，检查并加载工作区文件
            java.nio.file.Path wsPath = java.nio.file.Path.of(workspaceDir);
            if (!java.nio.file.Files.exists(wsPath)) {
                return;
            }

            List<String> loadedFiles = new ArrayList<>();
            String[] bootFiles = {"AGENTS.md", "SOUL.md", "USER.md", "TOOLS.md"};

            for (String filename : bootFiles) {
                java.nio.file.Path filePath = wsPath.resolve(filename);
                if (java.nio.file.Files.exists(filePath)) {
                    String content = java.nio.file.Files.readString(filePath);
                    context.put("boot_" + filename.toLowerCase(), content);
                    loadedFiles.add(filename);
                }
            }

            if (!loadedFiles.isEmpty()) {
                log.info("Boot hook loaded files: {}", loadedFiles);
            }
        }
    }

    // 钩子注册表
    private final Map<HookPoint, CopyOnWriteArrayList<Hook>> hooks = new ConcurrentHashMap<>();

    public GatewayHooks() {
        // 初始化所有钩子点
        for (HookPoint point : HookPoint.values()) {
            hooks.put(point, new CopyOnWriteArrayList<>());
        }
    }

    /**
     * 注册钩子
     */
    public void register(HookPoint point, Hook hook) {
        hooks.get(point).add(hook);
        log.debug("Registered hook for {}", point.getValue());
    }

    /**
     * 注销钩子
     */
    public boolean unregister(HookPoint point, Hook hook) {
        return hooks.get(point).remove(hook);
    }

    /**
     * 触发钩子
     * 
     * @param point 钩子点
     * @param data  钩子数据
     * @return 钩子上下文（可能被取消）
     */
    public HookContext fire(HookPoint point, Map<String, Object> data) {
        HookContext context = new HookContext(point, data);
        List<Hook> hookList = hooks.get(point);

        for (Hook hook : hookList) {
            try {
                hook.execute(context);
                if (context.isCancelled()) {
                    log.info("Hook chain cancelled at {} by hook: {}", 
                        point.getValue(), context.getCancelReason());
                    break;
                }
            } catch (Exception e) {
                log.error("Hook execution failed at {}: {}", point.getValue(), e.getMessage(), e);
                // 钩子失败不中断主流程
            }
        }

        return context;
    }

    /**
     * 触发钩子（无数据）
     */
    public HookContext fire(HookPoint point) {
        return fire(point, null);
    }

    /**
     * 获取钩子点注册的钩子数量
     */
    public int getHookCount(HookPoint point) {
        return hooks.get(point).size();
    }

    /**
     * 清除所有钩子
     */
    public void clearAll() {
        for (HookPoint point : HookPoint.values()) {
            hooks.get(point).clear();
        }
    }

    /**
     * 注册内置钩子
     */
    public void registerBuiltinHooks(String workspaceDir) {
        // 启动钩子
        register(HookPoint.ON_BOOT, new BootMdHook(workspaceDir));
        log.info("Registered builtin hooks");
    }
}
