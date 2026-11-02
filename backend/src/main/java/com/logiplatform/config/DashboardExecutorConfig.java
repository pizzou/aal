package com.logiplatform.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bounded executor for independent read-only control-tower aggregates.
 *
 * A small fixed pool is intentional: it shortens dashboard latency without
 * allowing one browser refresh to consume the entire PostgreSQL connection pool.
 */
@Configuration
public class DashboardExecutorConfig {

    @Bean(name = "dashboardExecutor", destroyMethod = "shutdown")
    public Executor dashboardExecutor() {
        // Limit concurrent reporting queries and queue depth so multiple
        // browser refreshes do not exhaust PostgreSQL connections or RAM.
        return new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(24), runnable -> {
            Thread thread = new Thread(runnable, "aal-dashboard");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
