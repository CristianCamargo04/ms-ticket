package com.ticketing.config.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SqsQueueListenerTest {
    private static final Function<String, Mono<Void>> HANDLER = body -> Mono.empty();

    @Test
    void shouldBindAQueueToItsHandler() {
        SqsQueueListener listener = new SqsQueueListener("orders", HANDLER);

        assertThat(listener.queueName()).isEqualTo("orders");
        assertThat(listener.handler()).isSameAs(HANDLER);
        StepVerifier.create(listener.handler().apply("{}")).verifyComplete();
    }

    @Test
    void shouldRejectMissingOrBlankArguments() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsQueueListener(" ", HANDLER));
        assertThatNullPointerException().isThrownBy(() -> new SqsQueueListener(null, HANDLER));
        assertThatNullPointerException().isThrownBy(() -> new SqsQueueListener("orders", null));
    }
}
