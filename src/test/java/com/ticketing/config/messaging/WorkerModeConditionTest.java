package com.ticketing.config.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketing.enums.RuntimeModeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

class WorkerModeConditionTest {

    private final WorkerModeCondition condition = new WorkerModeCondition();

    @ParameterizedTest(name = "runtime-mode {0} -> worker enabled: {1}")
    @CsvSource({"all, true", "worker, true", "WORKER, true", "api, false", "API, false"})
    void shouldMatchOnlyWhenWorkersAreEnabled(String runtimeMode, boolean expected) {
        ConditionOutcome outcome = outcomeFor(new MockEnvironment()
                .withProperty(WorkerModeCondition.RUNTIME_MODE_PROPERTY, runtimeMode));

        assertThat(outcome.isMatch()).isEqualTo(expected);
        assertThat(outcome.getMessage()).contains("ticketing.runtime-mode is " + runtimeMode.toUpperCase());
    }

    @Test
    void shouldDefaultToAllWhenTheModeIsNotConfigured() {
        MockEnvironment environment = new MockEnvironment();

        assertThat(WorkerModeCondition.runtimeMode(environment)).isEqualTo(RuntimeModeEnum.ALL);
        assertThat(outcomeFor(environment).isMatch()).isTrue();
    }

    @Test
    void shouldResolveTheConfiguredMode() {
        MockEnvironment environment = new MockEnvironment().withProperty("ticketing.runtime-mode", "api");

        assertThat(WorkerModeCondition.runtimeMode(environment)).isEqualTo(RuntimeModeEnum.API);
    }

    private ConditionOutcome outcomeFor(MockEnvironment environment) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return condition.getMatchOutcome(context, mock(AnnotatedTypeMetadata.class));
    }
}
