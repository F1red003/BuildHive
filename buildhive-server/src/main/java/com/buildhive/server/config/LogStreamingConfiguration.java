package com.buildhive.server.config;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class LogStreamingConfiguration implements WebMvcConfigurer {
    @Bean public ThreadPoolTaskExecutor logStreamExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4); executor.setMaxPoolSize(32); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("log-stream-"); executor.initialize(); return executor;
    }
    @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(logStreamExecutor()).setDefaultTimeout(31 * 60 * 1000L);
    }
}
