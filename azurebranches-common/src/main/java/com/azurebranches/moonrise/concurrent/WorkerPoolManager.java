/*
 * AzureBranches - Worker Pool Manager
 * Server-level registry for named WorkerThreadPool instances.
 */
package com.azurebranches.moonrise.concurrent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkerPoolManager {
    private static final Map<String, WorkerThreadPool> pools = new ConcurrentHashMap<>();
    private static volatile boolean shutdown = false;

    public static WorkerThreadPool get(String name) {
        int threads; long holdNs;
        try {
            com.azurebranches.config.AzureBranchesConfig cfg = com.azurebranches.config.AzureBranchesConfig.get();
            threads = cfg.ioPoolThreadsConfig();
            holdNs = cfg.ioPoolHoldTimeMs() * 1_000_000L;
        } catch (IllegalStateException e) {
            threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
            holdNs = 20_000_000L;
        }
        return get(name, threads, holdNs);
    }

    public static WorkerThreadPool get(String name, int threadCount) { return get(name, threadCount, 20_000_000L); }

    public static WorkerThreadPool get(String name, int threadCount, long holdTimeNanos) {
        if (shutdown) throw new IllegalStateException("WorkerPoolManager is shut down");
        return pools.computeIfAbsent(name, k -> WorkerThreadPool.builder().name("AzureBranches-"+k).threadCount(threadCount).holdTimeNanos(holdTimeNanos).build());
    }

    public static WorkerThreadPool register(String name, WorkerThreadPool pool) {
        if (shutdown) throw new IllegalStateException("WorkerPoolManager is shut down");
        return pools.computeIfAbsent(name, k -> pool);
    }

    public static boolean has(String name) { return pools.containsKey(name); }

    public static void shutdownAll(long timeoutMs) {
        shutdown = true;
        for (Map.Entry<String, WorkerThreadPool> entry : pools.entrySet()) {
            String name = entry.getKey();
            WorkerThreadPool pool = entry.getValue();
            try { if (!pool.shutdown(timeoutMs)) System.err.println("[WorkerPoolManager] "+name+" did not drain"); }
            catch (Exception e) { System.err.println("[WorkerPoolManager] "+name+" shutdown error: "+e.getMessage()); pool.halt(); }
        }
        pools.clear();
    }

    public static void haltAll() { shutdown=true; for(WorkerThreadPool p:pools.values())p.halt(); pools.clear(); }
    public static int poolCount() { return pools.size(); }

    public static String stats() {
        StringBuilder sb = new StringBuilder("=== Worker Pool Stats ===\n");
        for (WorkerThreadPool pool : pools.values()) sb.append("  ").append(pool.stats()).append("\n");
        return sb.append("=========================").toString();
    }
}
