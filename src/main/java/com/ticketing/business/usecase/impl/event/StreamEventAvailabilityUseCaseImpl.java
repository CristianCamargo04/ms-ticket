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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@Service
public class StreamEventAvailabilityUseCaseImpl implements StreamEventAvailabilityUseCase {
    private final GetEventAvailabilityUseCase getEventAvailabilityUseCase;
    private final EventPolicy eventPolicy;
    private final ConcurrentMap<String, Flux<AvailabilitySnapshot>> sharedPolls = new ConcurrentHashMap<>();

    @Override
    public Flux<AvailabilitySnapshot> execute(String eventId) {
        return Flux.defer(() -> sharedPolls.computeIfAbsent(eventId, this::sharedPoll))
                .onBackpressureLatest()
                .distinctUntilChanged(Function.identity(), AvailabilitySnapshot::sameCountsAs);
    }

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
