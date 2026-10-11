package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record IssueComplimentaryCommand(OrderData order, List<String> ticketIds, Instant now, String actor)
        implements InventoryCommand {
    public IssueComplimentaryCommand {
        ticketIds = InventoryCommand.requireValidSelection(order, ticketIds);
        DomainPreconditions.require(order.type() == OrderTypeEnum.COMPLIMENTARY,
                "issueComplimentary requires a COMPLIMENTARY order");
        Objects.requireNonNull(now, "now");
        DomainPreconditions.requireNonBlank(actor, "actor");
    }

    @Override
    public TransitionReasonEnum reason() {
        return TransitionReasonEnum.COMPLIMENTARY_ISSUED;
    }
}
