package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.GetTicketHistoryUseCase;
import com.ticketing.presentation.TicketHistoryRestController;
import com.ticketing.presentation.dto.TicketTransitionResponseDto;
import com.ticketing.presentation.impl.support.ResourceIdValidator;
import com.ticketing.presentation.mapper.TicketTransitionDtoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@RestController
public class TicketHistoryRestControllerImpl implements TicketHistoryRestController {
    private final GetTicketHistoryUseCase getTicketHistoryUseCase;
    private final TicketTransitionDtoMapper mapper;

    @Override
    public Flux<TicketTransitionResponseDto> getTicketHistory(String eventId, String ticketId) {
        return Flux.defer(() -> getTicketHistoryUseCase.execute(
                        ResourceIdValidator.requireValid(ResourceIdValidator.EVENT_ID, eventId),
                        ResourceIdValidator.requireValid(ResourceIdValidator.TICKET_ID, ticketId)))
                .map(mapper::toResponse);
    }
}
