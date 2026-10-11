package com.ticketing.config.web;

import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Web settings of worker-only instances ({@code ticketing.runtime-mode=worker}, case-insensitive; ADR-008): the HTTP
 * server stays up for the actuator, while {@link WorkerModeApiWebFilter} answers {@code 404} to every {@code /api/**}
 * request. {@code all} and {@code api} instances do not register the filter.
 *
 * <p>The complementary gate lives with the workers themselves: the SQS listener container and the sweeper timer are
 * only created when the mode runs workers ({@code WorkerModeCondition}), so an {@code api} instance never consumes
 * a queue nor sweeps.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ticketing.runtime-mode", havingValue = "worker")
public class WorkerModeWebConfig {

    /**
     * The filter hiding the business API.
     *
     * @param problems builds the 404 problem
     * @param writer   writes it to the response
     * @return the filter
     */
    @Bean
    public WorkerModeApiWebFilter workerModeApiWebFilter(ProblemDetailFactory problems,
                                                         ProblemDetailResponseWriter writer) {
        return new WorkerModeApiWebFilter(problems, writer);
    }
}
