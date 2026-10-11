package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.business.usecase.StreamEventAvailabilityUseCase;
import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.domain.event.EventPolicy;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Streams the availability of an event by polling it at {@link EventPolicy#availabilityStreamInterval()} and emitting
 * a snapshot only when its counters change.
 *
 * <p>Polls never overlap ({@code concatMap} with a prefetch of one) and ticks that arrive while a poll is still running
 * or while the subscriber is not requesting are dropped instead of queued, so a slow consumer or a slow read cannot
 * build up a backlog. The stream is infinite; it ends when the subscriber cancels (client disconnect) or with the
 * first lookup error (e.g. {@code EventNotFoundException}).
 */
@Service
public class StreamEventAvailabilityUseCaseImpl implements StreamEventAvailabilityUseCase {

    private final GetEventAvailabilityUseCase getEventAvailabilityUseCase;
    private final EventPolicy eventPolicy;

    /**
     * Creates the use case.
     *
     * @param getEventAvailabilityUseCase consistent availability read used for every poll
     * @param eventPolicy                 event rules (polling interval)
     */
    public StreamEventAvailabilityUseCaseImpl(GetEventAvailabilityUseCase getEventAvailabilityUseCase,
                                              EventPolicy eventPolicy) {
        this.getEventAvailabilityUseCase = getEventAvailabilityUseCase;
        this.eventPolicy = eventPolicy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Flux<AvailabilitySnapshot> execute(String eventId) {
        return Flux.interval(Duration.ZERO, eventPolicy.availabilityStreamInterval())
                .onBackpressureDrop()
                .concatMap(tick -> getEventAvailabilityUseCase.execute(eventId), 1)
                .distinctUntilChanged(Function.identity(), AvailabilitySnapshot::sameCountsAs);
    }
}
