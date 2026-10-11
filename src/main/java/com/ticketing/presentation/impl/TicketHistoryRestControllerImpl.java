package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.GetTicketHistoryUseCase;
import com.ticketing.presentation.TicketHistoryRestController;
import com.ticketing.presentation.dto.TicketTransitionResponseDto;
import com.ticketing.presentation.impl.support.ResourceIdValidator;
import com.ticketing.presentation.mapper.TicketTransitionDtoMapper;
import java.util.Objects;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Audit trail of a ticket (ADMIN only, enforced by the security configuration).
 */
@RestController
public class TicketHistoryRestControllerImpl implements TicketHistoryRestController {

    private final GetTicketHistoryUseCase getTicketHistoryUseCase;
    private final TicketTransitionDtoMapper mapper;

    /**
     * Creates the controller.
     *
     * @param getTicketHistoryUseCase reads the transitions
     * @param mapper                  DTO mapper
     */
    public TicketHistoryRestControllerImpl(GetTicketHistoryUseCase getTicketHistoryUseCase,
                                           TicketTransitionDtoMapper mapper) {
        this.getTicketHistoryUseCase = Objects.requireNonNull(getTicketHistoryUseCase, "getTicketHistoryUseCase");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Flux<TicketTransitionResponseDto> getTicketHistory(String eventId, String ticketId) {
        return Flux.defer(() -> getTicketHistoryUseCase.execute(
                        ResourceIdValidator.requireValid(ResourceIdValidator.EVENT_ID, eventId),
                        ResourceIdValidator.requireValid(ResourceIdValidator.TICKET_ID, ticketId)))
                .map(mapper::toResponse);
    }
}
