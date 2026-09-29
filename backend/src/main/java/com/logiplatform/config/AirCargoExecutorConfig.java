package com.logiplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Blocking airline adapters run on Java 21 virtual threads so one slow provider
 * does not serialize the entire air-cargo search.
 */
@Configuration
public class AirCargoExecutorConfig {

    @Bean(name = "airCargoExecutor", destroyMethod = "close")
    public ExecutorService airCargoExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
