package com.ticketing.config.messaging;

import com.ticketing.enums.RuntimeModeEnum;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when this instance runs the workers: {@code ticketing.runtime-mode} is {@code all} (the default) or
 * {@code worker} (see {@link RuntimeModeEnum#workerEnabled()}). Guards every inbound worker channel: the SQS listener
 * container and the sweeper timer. An {@code api} instance creates none of them.
 */
public final class WorkerModeCondition extends SpringBootCondition {

    /** Property selecting the runtime mode. */
    public static final String RUNTIME_MODE_PROPERTY = "ticketing.runtime-mode";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        RuntimeModeEnum runtimeMode = runtimeMode(context.getEnvironment());
        ConditionMessage.Builder message = ConditionMessage.forCondition("Worker runtime mode");
        return runtimeMode.workerEnabled()
                ? ConditionOutcome.match(message.because(RUNTIME_MODE_PROPERTY + " is " + runtimeMode))
                : ConditionOutcome.noMatch(message.because(RUNTIME_MODE_PROPERTY + " is " + runtimeMode));
    }

    /**
     * Resolves the runtime mode with relaxed binding ({@code worker}, {@code WORKER}, ...).
     *
     * @param environment the environment
     * @return the configured mode, {@link RuntimeModeEnum#ALL} when unset
     */
    public static RuntimeModeEnum runtimeMode(Environment environment) {
        return Binder.get(environment).bind(RUNTIME_MODE_PROPERTY, RuntimeModeEnum.class).orElse(RuntimeModeEnum.ALL);
    }
}
