package com.hermes.agent.loop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe iteration counter for an agent loop.
 * 
 * Mirrors Python's IterationBudget from run_agent.py:
 * - Parent agent has max_iterations (default 90)
 * - Each subagent gets independent budget capped at delegation.max_iterations (default 50)
 * - execute_code iterations are refunded via refund() so they don't eat into budget
 * 
 * Usage:
 * <pre>
 * IterationBudget budget = new IterationBudget(90);
 * while (budget.canContinue()) {
 *     budget.consume();
 *     // ... API call + tool execution ...
 *     if (isExecuteCodeTool) {
 *         budget.refund();
 *     }
 * }
 * </pre>
 */
public class IterationBudget {
    
    private static final Logger log = LoggerFactory.getLogger(IterationBudget.class);
    
    /** Default maximum iterations for parent agent */
    public static final int DEFAULT_MAX_ITERATIONS = 90;
    
    /** Default maximum iterations for subagent */
    public static final int DEFAULT_SUBAGENT_MAX_ITERATIONS = 50;
    
    private final int maxTotal;
    private final AtomicInteger used;
    private final ReentrantLock lock;
    
    /**
     * Create a new iteration budget with specified maximum.
     * 
     * @param maxTotal Maximum number of iterations allowed
     */
    public IterationBudget(int maxTotal) {
        this.maxTotal = maxTotal;
        this.used = new AtomicInteger(0);
        this.lock = new ReentrantLock();
    }
    
    /**
     * Create a new iteration budget with default maximum (90).
     */
    public IterationBudget() {
        this(DEFAULT_MAX_ITERATIONS);
    }
    
    /**
     * Try to consume one iteration.
     * 
     * @return true if iteration was allowed, false if budget exhausted
     */
    public boolean consume() {
        lock.lock();
        try {
            if (used.get() >= maxTotal) {
                log.debug("Iteration budget exhausted: {}/{}", used.get(), maxTotal);
                return false;
            }
            used.incrementAndGet();
            log.trace("Consumed iteration: {}/{}", used.get(), maxTotal);
            return true;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Check if budget allows more iterations without consuming.
     * 
     * @return true if more iterations are possible
     */
    public boolean canContinue() {
        return used.get() < maxTotal;
    }
    
    /**
     * Give back one iteration (e.g., for execute_code turns that shouldn't count).
     * Mirrors Python's budget.refund() for programmatic tool calling.
     */
    public void refund() {
        lock.lock();
        try {
            if (used.get() > 0) {
                used.decrementAndGet();
                log.trace("Refunded iteration: {}/{}", used.get(), maxTotal);
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get the number of iterations used.
     */
    public int getUsed() {
        return used.get();
    }
    
    /**
     * Get the maximum allowed iterations.
     */
    public int getMaxTotal() {
        return maxTotal;
    }
    
    /**
     * Get the remaining iterations.
     */
    public int getRemaining() {
        return Math.max(0, maxTotal - used.get());
    }
    
    /**
     * Reset the budget for reuse.
     */
    public void reset() {
        used.set(0);
        log.debug("Iteration budget reset");
    }
    
    @Override
    public String toString() {
        return String.format("IterationBudget[used=%d, max=%d, remaining=%d]", 
                used.get(), maxTotal, getRemaining());
    }
}
