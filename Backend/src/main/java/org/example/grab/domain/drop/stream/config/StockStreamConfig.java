package org.example.grab.domain.drop.stream.config;

import org.example.grab.domain.drop.stream.service.StockStreamRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableConfigurationProperties(StockStreamProperties.class)
public class StockStreamConfig {

    @Bean
    public StockStreamRegistry stockStreamRegistry(StockStreamProperties properties) {
        return new StockStreamRegistry(properties, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    public StockStreamExecutors stockStreamExecutors(StockStreamProperties properties) {
        ThreadPoolExecutor sender = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.maxConnections()), threadFactory("stock-stream-send-"),
                new ThreadPoolExecutor.AbortPolicy());
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, threadFactory("stock-stream-timer-"));
        scheduler.setRemoveOnCancelPolicy(true);
        return new StockStreamExecutors(sender, scheduler);
    }

    private ThreadFactory threadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
