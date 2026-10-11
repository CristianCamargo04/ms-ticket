package com.ticketing.domain.internal_request;

/**
 * Input of the issue-complimentary-tickets use case.
 *
 * @param eventId        event identifier
 * @param beneficiaryId  customer receiving the tickets (owner of the resulting order)
 * @param quantity       number of tickets
 * @param issuedBy       authenticated administrator (JWT subject)
 * @param idempotencyKey value of the {@code Idempotency-Key} header
 * @param requestHash    fingerprint of the request payload
 * @param correlationId  request correlation id
 */
public record IssueComplimentaryRequest(String eventId, String beneficiaryId, int quantity, String issuedBy,
                                        String idempotencyKey, String requestHash, String correlationId) {
}
