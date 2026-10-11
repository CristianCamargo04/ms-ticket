package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.business.usecase.StreamEventAvailabilityUseCase;
import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.domain.event.EventPolicy;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Streams the availability of an event by polling it at {@link EventPolicy#availabilityStreamInterval()} and emitting
 * a snapshot only when its counters change.
 *
 * <p>The poll is shared: all the subscribers of one event on this instance are fed by a single poll
 * ({@code replay(1).refCount(1)}), so the number of strongly consistent reads of the event item does not grow with the
 * number of open streams. The poll starts with the first subscriber, a later subscriber immediately receives the latest
 * polled snapshot, and the poll stops (and is forgotten) when its last subscriber cancels or when it fails; the next
 * subscriber then starts a new one. Polls never overlap ({@code concatMap} with a prefetch of one) and ticks that arrive
 * while a poll is still running are dropped instead of queued. Each subscriber keeps only the latest snapshot while it
 * is not requesting, so a slow client neither builds a backlog nor holds back the other subscribers, and it applies its
 * own change filter. The stream is infinite; it ends when the subscriber cancels (client disconnect) or with the first
 * lookup error (e.g. {@code EventNotFoundException}), which every subscriber of the event receives.
 */
@Service
public class StreamEventAvailabilityUseCaseImpl implements StreamEventAvailabilityUseCase {

    private final GetEventAvailabilityUseCase getEventAvailabilityUseCase;
    private final EventPolicy eventPolicy;
    private final ConcurrentMap<String, Flux<AvailabilitySnapshot>> sharedPolls = new ConcurrentHashMap<>();

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
        return Flux.defer(() -> sharedPolls.computeIfAbsent(eventId, this::sharedPoll))
                .onBackpressureLatest()
                .distinctUntilChanged(Function.identity(), AvailabilitySnapshot::sameCountsAs);
    }

    /**
     * Assembles (without subscribing) the poll shared by the subscribers of one event; it unregisters itself before
     * propagating its termination or cancellation, so that a later subscriber never joins a finished poll.
     */
    private Flux<AvailabilitySnapshot> sharedPoll(String eventId) {
        AtomicReference<Flux<AvailabilitySnapshot>> self = new AtomicReference<>();
        Runnable unregister = () -> sharedPolls.remove(eventId, self.get());
        Flux<AvailabilitySnapshot> shared = Flux.interval(Duration.ZERO, eventPolicy.availabilityStreamInterval())
                .onBackpressureDrop()
                .concatMap(tick -> getEventAvailabilityUseCase.execute(eventId), 1)
                .doOnTerminate(unregister)
                .doOnCancel(unregister)
                .replay(1)
                .refCount(1);
        self.set(shared);
        return shared;
    }
}
