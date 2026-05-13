package com.hermes.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步执行器配置 —— 为 @Async("taskExecutor") 提供命名 Bean。
 *
 * <p>配置要点：
 * <ul>
 *   <li>核心线程数 4，最大 8，队列容量 128</li>
 *   <li>拒绝策略 CallerRunsPolicy：队列满时由调用线程执行，避免任务丢失</li>
 *   <li>线程名前缀 AsyncTaskExecutor-，便于日志排查</li>
 * </ul>
 */
@Configuration
public class AsyncConfig {

    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("AsyncTaskExecutor-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        // 等待所有任务完成再关闭，避免消息丢失
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
