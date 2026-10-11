package com.ticketing.config.web;

import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ticketing.runtime-mode", havingValue = "worker")
public class WorkerModeWebConfig {
    @Bean
    public WorkerModeApiWebFilter workerModeApiWebFilter(ProblemDetailFactory problems,
                                                         ProblemDetailResponseWriter writer) {
        return new WorkerModeApiWebFilter(problems, writer);
    }
}
