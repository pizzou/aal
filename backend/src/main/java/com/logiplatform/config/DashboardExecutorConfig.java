package com.logiplatform.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
        return Executors.newFixedThreadPool(6, runnable -> {
            Thread thread = new Thread(runnable, "aal-dashboard");
            thread.setDaemon(true);
            return thread;
        });
    }
}
