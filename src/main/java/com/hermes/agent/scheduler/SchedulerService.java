package com.hermes.agent.scheduler;

import com.hermes.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SchedulerService {
    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final Map<String, CronJob> jobs = new ConcurrentHashMap<>();
    private final Agent agent;
    private final AtomicLong jobIdCounter = new AtomicLong(1);

    public SchedulerService(@Lazy Agent agent) {
        this.agent = agent;
    }

    public String scheduleCron(String expression, String taskDescription, String sessionId) {
        String jobId = "job_" + jobIdCounter.getAndIncrement();
        CronJob job = new CronJob(jobId, expression, taskDescription, sessionId);
        jobs.put(jobId, job);
        log.info("Scheduled cron job {}: {} (cron: {}, session: {})", jobId, taskDescription, expression, sessionId);
        return jobId;
    }

    public void cancelJob(String jobId) {
        jobs.remove(jobId);
        log.info("Cancelled job: {}", jobId);
    }

    public List<Map<String, Object>> getAllJobs() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CronJob job : jobs.values()) {
            Map<String, Object> info = new HashMap<>();
            info.put("id", job.jobId);
            info.put("expression", job.expression);
            info.put("task", job.taskDescription);
            info.put("sessionId", job.sessionId);
            info.put("lastRun", job.lastRun != null ? job.lastRun.toString() : null);
            info.put("nextRun", job.getNextRunTime());
            info.put("runCount", job.runCount);
            result.add(info);
        }
        return result;
    }

    @Scheduled(fixedRate = 60000)
    public void checkScheduledJobs() {
        LocalDateTime now = LocalDateTime.now();
        for (CronJob job : jobs.values()) {
            if (job.shouldRun(now)) {
                job.lastRun = Instant.now();
                job.runCount++;
                log.info("Executing scheduled job: {} - {}", job.jobId, job.taskDescription);
                agent.chat(job.sessionId, "[Scheduled Task] " + job.taskDescription)
                        .subscribe(
                                result -> log.info("Scheduled task {} completed", job.jobId),
                                error -> log.error("Scheduled task {} failed: {}", job.jobId, error.getMessage())
                        );
            }
        }
    }

    public static class CronJob {
        public final String jobId;
        public final String expression;
        public final String taskDescription;
        public final String sessionId;
        public Instant lastRun;
        public int runCount;

        public CronJob(String jobId, String expression, String taskDescription, String sessionId) {
            this.jobId = jobId;
            this.expression = expression;
            this.taskDescription = taskDescription;
            this.sessionId = sessionId;
            this.runCount = 0;
        }

        public String getNextRunTime() {
            return LocalDateTime.now().plusMinutes(1).toString();
        }

        public boolean shouldRun(LocalDateTime now) {
            if (lastRun == null) return true;
            return java.time.Duration.between(
                    LocalDateTime.ofInstant(lastRun, ZoneId.systemDefault()),
                    now).toMinutes() >= 1;
        }
    }
}