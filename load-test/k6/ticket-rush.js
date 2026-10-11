// -----------------------------------------------------------------------------
// k6 "ticket rush": a high-demand drop on one small event.
//
// setup()    ADMIN creates an event with CAPACITY tickets and fixes the drop instant.
// VUs        BUYERS customers (one VU each, distinct JWT subjects load-customer-<run>-<n>) fetch a token,
//            wait for the drop, place ONE order for 1..MAX_QTY tickets (deterministic Idempotency-Key) at a
//            random instant of the DROP_SPREAD_MS window, poll it until the consumer decides (RESERVED or
//            REJECTED/SOLD_OUT) and, for half of the winners, confirm with tok_approved and poll until SOLD.
//            Defaults: 500 buyers for 100 seats (demand ~7.5x capacity) within 15 s. DROP_SPREAD_MS=1000 is
//            the stress variant (500 orders in one second); see load-test/README.md for measured results.
// teardown() Zero-oversell verification, black-box:
//            1. availability counters: available + reserved + pending + sold + complimentary == capacity,
//               and sold + held + complimentary <= capacity;
//            2. recount: every buyer replays its own Idempotency-Key (returns the existing order and its
//               current state; creates nothing once the event is sold out) -> tickets in SOLD / held orders
//               must equal the counters;
//            3. ADMIN reads every winning order: no ticket id appears in two orders.
//
// Run (grafana/k6 image on the compose network, so Docker Desktop's port proxy is not measured):
//   docker run --rm -i --network ms-ticket-network -v "$PWD/load-test/k6:/scripts:ro" \
//     -e API_URL=http://ticketing-api:8080 \
//     -e AUTH_URL=http://auth-mock:8080/default -e AUTH_HOST=localhost:8090 \
//     grafana/k6:1.3.0 run /scripts/ticket-rush.js
// AUTH_HOST keeps iss = http://localhost:8090/default (the issuer the API validates). The auth-mock must map
// client ids load-customer-* to CUSTOMER tokens (docker/auth-mock/config.json). See load-test/README.md.
// -----------------------------------------------------------------------------
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';

const API_URL = __ENV.API_URL || 'http://localhost:8080';
const AUTH_URL = __ENV.AUTH_URL || 'http://localhost:8090/default';
const AUTH_HOST = __ENV.AUTH_HOST || '';
const CAPACITY = Number(__ENV.CAPACITY || 100);
const BUYERS = Number(__ENV.BUYERS || 500);
const MAX_QTY = Number(__ENV.MAX_QTY || 2);
const START_DELAY_S = Number(__ENV.START_DELAY_S || 15);
const DROP_SPREAD_MS = Number(__ENV.DROP_SPREAD_MS || 15000);
const POLL_INTERVAL_S = Number(__ENV.POLL_INTERVAL_S || 1);
const MAX_POLLS = Number(__ENV.MAX_POLLS || 90);
const P95_MS = Number(__ENV.P95_MS || 300);

const ORDER_ENDPOINT = 'POST /api/v1/events/{eventId}/orders';
const BATCH = 100;

const ordersAccepted = new Counter('orders_accepted');
const ordersRejectedAtAdmission = new Counter('orders_rejected_at_admission');
const ordersReserved = new Counter('orders_reserved');
const ordersRejectedSoldOut = new Counter('orders_rejected_sold_out');
const ordersSold = new Counter('orders_sold');
const ordersUnresolved = new Counter('orders_unresolved');
const ticketsWon = new Counter('tickets_won');
const timeToDecision = new Trend('time_to_decision', true);
const timeToSold = new Trend('time_to_sold', true);
const oversellViolations = new Counter('oversell_violations');
const recountMismatches = new Counter('recount_mismatches');
const duplicateTickets = new Counter('duplicate_tickets');
const finalSold = new Gauge('final_sold');
const finalHeld = new Gauge('final_held');
const finalAvailable = new Gauge('final_available');

export const options = {
    scenarios: {
        rush: {
            executor: 'per-vu-iterations',
            vus: BUYERS,
            iterations: 1,
            maxDuration: '5m',
        },
    },
    setupTimeout: '60s',
    teardownTimeout: '5m',
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        // Admission latency of the drop (the API only validates, writes the order and enqueues it).
        [`http_req_duration{name:${ORDER_ENDPOINT}}`]: [`p(95)<${P95_MS}`],
        // 409 SOLD_OUT is an expected answer during a drop; 429 / 5xx are not.
        http_req_failed: ['rate<0.01'],
        checks: ['rate>0.99'],
        orders_unresolved: ['count==0'],
        oversell_violations: ['count==0'],
        recount_mismatches: ['count==0'],
        duplicate_tickets: ['count==0'],
    },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));

function tokenRequest(clientId) {
    const params = { tags: { name: 'POST /token' }, headers: {} };
    if (AUTH_HOST) {
        params.headers.Host = AUTH_HOST;
    }
    const form = { grant_type: 'client_credentials', client_id: clientId, client_secret: 'k6', scope: 'ms-ticket' };
    return ['POST', `${AUTH_URL}/token`, form, params];
}

function fetchToken(clientId) {
    const [method, url, body, params] = tokenRequest(clientId);
    const res = http.request(method, url, body, params);
    check(res, { 'token issued': (r) => r.status === 200 });
    return res.status === 200 ? res.json('access_token') : null;
}

function customerFor(runId, vu) {
    return `load-customer-${runId}-${vu}`;
}

function keyFor(runId, vu) {
    return `rush-${runId}-${vu}`;
}

function quantityFor(vu) {
    return 1 + (vu % MAX_QTY);
}

function json(headers) {
    return Object.assign({ 'Content-Type': 'application/json' }, headers);
}

function orderRequest(data, token, vu, name) {
    return ['POST', `${API_URL}/api/v1/events/${data.eventId}/orders`, JSON.stringify({ quantity: quantityFor(vu) }), {
        headers: json({ Authorization: `Bearer ${token}`, 'Idempotency-Key': keyFor(data.runId, vu) }),
        tags: { name },
    }];
}

function pollOrder(token, orderId, done) {
    for (let i = 0; i < MAX_POLLS; i++) {
        sleep(POLL_INTERVAL_S);
        const res = http.get(`${API_URL}/api/v1/orders/${orderId}`, {
            headers: { Authorization: `Bearer ${token}` },
            tags: { name: 'GET /api/v1/orders/{orderId}' },
        });
        if (res.status === 200 && done(res.json())) {
            return res.json();
        }
    }
    return null;
}

export function setup() {
    // Every custom counter gets a zero sample, so the thresholds always have data.
    [ordersAccepted, ordersRejectedAtAdmission, ordersReserved, ordersRejectedSoldOut, ordersSold, ordersUnresolved,
        ticketsWon, oversellViolations, recountMismatches, duplicateTickets].forEach((counter) => counter.add(0));

    const runId = Date.now().toString(36);
    const adminToken = fetchToken('admin');
    const eventDate = new Date(Date.now() + 90 * 24 * 3600 * 1000).toISOString();
    const res = http.post(`${API_URL}/api/v1/events`, JSON.stringify({
        name: `k6 ticket rush ${runId}`, venue: 'Arena Norte', eventDate, totalCapacity: CAPACITY,
        ticketPrice: 89.90, currency: 'USD',
    }), { headers: json({ Authorization: `Bearer ${adminToken}` }), tags: { name: 'POST /api/v1/events' } });
    if (!check(res, { 'event created': (r) => r.status === 201 })) {
        exec.test.abort(`event creation failed: ${res.status} ${res.body}`);
    }
    const eventId = res.json('eventId');
    console.log(`run ${runId}: event ${eventId}, capacity ${CAPACITY}, ${BUYERS} buyers x 1..${MAX_QTY} tickets`);
    return { runId, eventId, adminToken, dropAt: Date.now() + START_DELAY_S * 1000 };
}

export default function (data) {
    const vu = exec.vu.idInTest;
    const token = fetchToken(customerFor(data.runId, vu));
    if (!token) {
        return;
    }
    // Everybody hits "buy" within DROP_SPREAD_MS of the drop instant.
    const wait = data.dropAt + Math.random() * DROP_SPREAD_MS - Date.now();
    if (wait > 0) {
        sleep(wait / 1000);
    }

    const [method, url, body, params] = orderRequest(data, token, vu, ORDER_ENDPOINT);
    const res = http.request(method, url, body, params);
    if (res.status === 409) {
        ordersRejectedAtAdmission.add(1);
        check(res, { 'admission 409 is SOLD_OUT': (r) => r.json('errorCode') === 'SOLD_OUT' });
        return;
    }
    const accepted = check(res, {
        'order accepted: 202 + Location': (r) => r.status === 202
            && r.headers.Location === `/api/v1/orders/${r.json('orderId')}`,
    });
    if (!accepted) {
        console.warn(`vu ${vu}: unexpected ${res.status} ${res.body}`);
        return;
    }
    ordersAccepted.add(1);
    const orderId = res.json('orderId');
    const acceptedAt = Date.now();

    const decided = pollOrder(token, orderId, (o) => o.processingStatus !== 'QUEUED');
    if (!decided) {
        ordersUnresolved.add(1);
        console.warn(`vu ${vu}: order ${orderId} still QUEUED after ${MAX_POLLS} polls`);
        return;
    }
    timeToDecision.add(Date.now() - acceptedAt);
    if (decided.status === 'RESERVED') {
        ordersReserved.add(1);
        ticketsWon.add(decided.quantity);
    } else {
        check(decided, { 'loser is REJECTED with SOLD_OUT': (o) => o.processingStatus === 'REJECTED'
                && o.reason === 'SOLD_OUT' });
        ordersRejectedSoldOut.add(1);
        return;
    }

    // Half of the winners pay right away; the others keep their hold (10 min) until the end of the test.
    // vu % 4 (not vu % 2) so that both 1- and 2-ticket orders end up SOLD and RESERVED.
    if (vu % 4 >= 2) {
        return;
    }
    const confirmedAt = Date.now();
    const confirm = http.post(`${API_URL}/api/v1/orders/${orderId}/confirmation`,
        JSON.stringify({ paymentToken: 'tok_approved' }), {
            headers: json({ Authorization: `Bearer ${token}`, 'Idempotency-Key': `pay-${keyFor(data.runId, vu)}` }),
            tags: { name: 'POST /api/v1/orders/{orderId}/confirmation' },
        });
    check(confirm, { 'confirmation accepted (202)': (r) => r.status === 202 });
    const sold = pollOrder(token, orderId, (o) => o.status === 'SOLD' || o.processingStatus !== 'ACTIVE');
    if (check(sold, { 'paid order is SOLD': (o) => o !== null && o.status === 'SOLD' })) {
        ordersSold.add(1);
        timeToSold.add(Date.now() - confirmedAt);
    }
}

function inBatches(requests) {
    const responses = [];
    for (let i = 0; i < requests.length; i += BATCH) {
        responses.push(...http.batch(requests.slice(i, i + BATCH)));
    }
    return responses;
}

export function teardown(data) {
    // 1. Counters (strongly consistent read).
    const a = http.get(`${API_URL}/api/v1/events/${data.eventId}/availability`,
        { tags: { name: 'GET /api/v1/events/{eventId}/availability' } }).json();
    const held = a.reserved + a.pendingConfirmation;
    finalSold.add(a.sold);
    finalHeld.add(held);
    finalAvailable.add(a.available);
    const conserved = a.available + held + a.sold + a.complimentary === a.totalCapacity;
    const withinCapacity = held + a.sold + a.complimentary <= a.totalCapacity;
    if (!conserved || !withinCapacity) {
        oversellViolations.add(1);
    }
    check(a, {
        'counters add up to the capacity': () => conserved,
        'no oversell: sold + held + complimentary <= capacity': () => withinCapacity,
    });
    console.log(`availability: ${JSON.stringify(a)}`);

    if (a.available !== 0) {
        console.warn('event not sold out: the replay recount is skipped (a replay could create new orders)');
        return;
    }

    // 2. Recount through idempotent replays (same customer, same key, same body -> the existing order).
    const vus = [];
    for (let vu = 1; vu <= BUYERS; vu++) {
        vus.push(vu);
    }
    const tokens = inBatches(vus.map((vu) => tokenRequest(customerFor(data.runId, vu))))
        .map((r) => (r.status === 200 ? r.json('access_token') : null));
    const replays = inBatches(vus.map((vu, i) => orderRequest(data, tokens[i], vu, 'POST replay (recount)')));
    const tally = { sold: 0, held: 0, soldOrders: 0, heldOrders: 0, rejected: 0, noOrder: 0, other: 0 };
    const winners = [];
    replays.forEach((r, i) => {
        const quantity = quantityFor(vus[i]);
        if (r.status === 409) {
            tally.noOrder++;
            return;
        }
        const order = r.json();
        if (order.status === 'SOLD') {
            tally.sold += quantity;
            tally.soldOrders++;
            winners.push(order.orderId);
        } else if (order.status === 'RESERVED' || order.status === 'PENDING_CONFIRMATION') {
            tally.held += quantity;
            tally.heldOrders++;
            winners.push(order.orderId);
        } else if (order.processingStatus === 'REJECTED') {
            tally.rejected++;
        } else {
            tally.other++;
            console.warn(`replay of vu ${vus[i]}: ${r.status} ${r.body}`);
        }
    });
    console.log(`recount: ${JSON.stringify(tally)}`);
    const recountMatches = tally.sold === a.sold && tally.held === held && tally.other === 0;
    if (!recountMatches) {
        recountMismatches.add(1);
    }
    check(tally, { 'recount of SOLD/held orders matches the counters': () => recountMatches });

    // 3. No ticket in two orders.
    const details = inBatches(winners.map((id) => ['GET', `${API_URL}/api/v1/orders/${id}`, null, {
        headers: { Authorization: `Bearer ${data.adminToken}` }, tags: { name: 'GET order (recount, ADMIN)' },
    }]));
    const seen = new Set();
    let assigned = 0;
    details.forEach((r) => {
        r.json('ticketIds').forEach((ticketId) => {
            assigned++;
            if (seen.has(ticketId)) {
                duplicateTickets.add(1);
                console.error(`ticket ${ticketId} appears in two orders`);
            }
            seen.add(ticketId);
        });
    });
    console.log(`winning orders: ${winners.length}, tickets assigned: ${assigned}, distinct: ${seen.size}`);
    check(seen, { 'every assigned ticket belongs to exactly one order': () => seen.size === assigned
            && assigned === tally.sold + tally.held });
}
