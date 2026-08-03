#!/usr/bin/env node
// Happy-path slice demo: hold → confirm → watch Saga → CONFIRMED with ticket reference
//
// Prerequisites:
//   docker compose -f docker/docker-compose.yml up --build -d
//   Wait ~60s for services to start, then run: node demo.js
//
// Kafdrop (Kafka UI): http://localhost:9000
//
// Cross-platform replacement for the old demo.sh (bash-only — needed Git Bash/WSL on
// Windows). Uses Node's built-in fetch, no dependencies.

const BASE = "http://localhost:8082";
const EVENT_BASE = "http://localhost:8081";
const GATEWAY_BASE = "http://localhost:8080";
const PAYMENT_BASE = "http://localhost:8083";
const NOTIFICATION_BASE = "http://localhost:8084";
const WAITLIST_BASE = "http://localhost:8085";
const USER_ID = "00000000-0000-0000-0000-000000000099";

function log(line = "") {
  console.log(line);
}

function fail(message) {
  console.error(`ERROR: ${message}`);
  process.exit(1);
}

async function call(method, url, { headers = {}, body } = {}) {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { "Content-Type": "application/json", ...headers } : headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    json = undefined;
  }
  return { status: res.status, text, json };
}

function expect(res, expectedStatus, label) {
  log(`HTTP ${res.status}`);
  log(res.text);
  if (res.status !== expectedStatus) {
    fail(`Expected ${expectedStatus}${label ? ` ${label}` : ""}, got ${res.status}`);
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function poll(fn, isDone, attempts = 5, delayMs = 1000) {
  let res;
  for (let i = 0; i < attempts; i++) {
    res = await fn();
    if (isDone(res)) break;
    await sleep(delayMs);
  }
  return res;
}

async function main() {
  log("");
  log("=========================================");
  log("  Event Ticketing — Happy Path Demo");
  log("=========================================");
  log("");

  // ── Step 0a: event-service — create a venue ─────────────────────────────────
  log(">>> 0a. POST /venues (event-service)");
  const venueRes = await call("POST", `${EVENT_BASE}/venues`, {
    body: {
      name: "The O2 Arena",
      address: "Peninsula Square",
      city: "London",
      country: "UK",
      sections: [
        { name: "Floor", rows: 10, seatsPerRow: 20, priceGbp: 89.5 },
        { name: "Upper Tier", rows: 15, seatsPerRow: 30, priceGbp: 45.0 },
      ],
    },
  });
  expect(venueRes, 201);
  const venueId = venueRes.json.id;
  log("");
  log(`    Venue ID : ${venueId}  (capacity: 650)`);
  log("");

  // ── Step 0b: event-service — create an event tied to the venue ──────────────
  log(">>> 0b. POST /events (event-service)");
  const eventCreateRes = await call("POST", `${EVENT_BASE}/events`, {
    body: {
      title: "Coldplay: Music of the Spheres Tour",
      description: "Live at The O2",
      category: "CONCERT",
      venueId,
      startsAt: "2026-09-15T19:30:00Z",
      endsAt: "2026-09-15T22:30:00Z",
    },
  });
  expect(eventCreateRes, 201);
  const eventId = eventCreateRes.json.id;
  log("");
  log(`    Event ID : ${eventId}`);
  log("");

  // ── Step 0c: event-service — fetch it and see it in the list ────────────────
  log(`>>> 0c. GET /events/${eventId} (event-service)`);
  log((await call("GET", `${EVENT_BASE}/events/${eventId}`)).text);
  log("");

  log(">>> 0d. GET /events?city=London (event-service)");
  log((await call("GET", `${EVENT_BASE}/events?city=London`)).text);
  log("");
  log("    NOTE: booking-service below calls event-service to validate $eventId");
  log("    and fetch its real title.");
  log("");

  // ── Step 0e: event-service — fetch the generated seat map, pick real seats ──
  log(`>>> 0e. GET /events/${eventId}/seats (event-service)`);
  log("    Seats were generated automatically when the event was created (Floor 10x20");
  log("    + Upper Tier 15x30 = 650). Picking real seats instead of made-up UUIDs.");
  const seatMapRes = await call("GET", `${EVENT_BASE}/events/${eventId}/seats`);
  log(`HTTP ${seatMapRes.status}`);
  if (seatMapRes.status !== 200) fail(`Expected 200, got ${seatMapRes.status}`);
  const seats = seatMapRes.json;
  log("");
  if (seats.length === 650) {
    log("  SUCCESS — 650 seats generated, all AVAILABLE.");
  } else {
    log(`  WARN: Expected 650 seats, got ${seats.length}`);
  }
  const seatId = seats[0].id;
  const gatewaySeatId = seats[1].id;
  log(`    Seat ID  : ${seatId}`);
  log("");

  // ── Step 1: Hold the seat ────────────────────────────────────────────────────
  log(">>> 1. POST /bookings/hold");
  const holdRes = await call("POST", `${BASE}/bookings/hold`, {
    headers: { "X-User-Id": USER_ID },
    body: { eventId, seatId },
  });
  expect(holdRes, 201);
  const bookingId = holdRes.json.id;
  log("");
  log(`    Booking ID : ${bookingId}`);
  log("    Status     : HELD  (Redis lock active for 10 min)");
  log("");

  // ── Step 2: Confirm (triggers payment Saga) ──────────────────────────────────
  log(`>>> 2. POST /bookings/${bookingId}/confirm`);
  const confirmRes = await call("POST", `${BASE}/bookings/${bookingId}/confirm`, {
    headers: { "X-User-Id": USER_ID },
    body: { stripePaymentMethodId: "pm_demo_4242424242424242" },
  });
  expect(confirmRes, 200);
  log("");
  log("    Status : PAYMENT_PENDING");
  log("    Kafka  : payment-initiated published → payment-service consuming...");
  log("");

  // ── Step 3: payment-service consumed payment-initiated, created a payment ───
  log(`>>> 3. GET /payments/bookings/${bookingId} (payment-service)`);
  log("    payment-service is the real, live saga participant now (payment-simulator has");
  log("    been retired) — it consumed the payment-initiated event published in Step 2 and");
  log("    created a payment via its stubbed PaymentGateway. The stub self-delivers a");
  log("    simulated Stripe webhook ~1s later (ADR-011), so this may already read SUCCEEDED");
  log("    by the time we check — either status is expected here.");
  const paymentRes = await poll(
    () => call("GET", `${PAYMENT_BASE}/payments/bookings/${bookingId}`),
    (res) => res.status === 200
  );
  expect(paymentRes, 200);
  const paymentId = paymentRes.json.id;
  const paymentIntentId = paymentRes.json.stripePaymentIntentId;
  const paymentStatus = paymentRes.json.status;
  log("");
  log(`    Payment ID : ${paymentId}  (status: ${paymentStatus}, stripePaymentIntentId: ${paymentIntentId})`);
  log("");

  // ── Step 4: manually deliver the same Stripe webhook the stub already sent ──
  log(">>> 4. POST /payments/webhook (payment-service) — manual re-delivery");
  log("    Since ADR-011, the stub gateway already self-delivers this webhook moments after");
  log("    Step 2 — the saga typically finishes on its own before this step even runs. This");
  log("    call instead demonstrates that the webhook endpoint is safe against Stripe's");
  log("    real-world at-least-once redelivery guarantee: if the payment is already");
  log("    SUCCEEDED, the idempotency guard makes this a safe no-op (still HTTP 200).");
  const webhookRes = await call("POST", `${PAYMENT_BASE}/payments/webhook`, {
    headers: { "Stripe-Signature": "t=demo,v1=stub_signature" },
    body: { type: "payment_intent.succeeded", paymentIntentId },
  });
  log(`HTTP ${webhookRes.status}`);
  if (webhookRes.status !== 200) fail(`Expected 200, got ${webhookRes.status}`);
  log("");

  // ── Step 5: Poll final booking state ─────────────────────────────────────────
  log(`>>> 5. GET /bookings/${bookingId}  (should be CONFIRMED)`);
  log("    Reaches CONFIRMED because a webhook published payment-completed and booking-service");
  log("    consumed it — typically the stub's own self-delivered webhook from Step 3's ~1s");
  log("    delay, with Step 4's manual call as a no-op backup if it hasn't landed yet.");
  const finalRes = await call("GET", `${BASE}/bookings/${bookingId}`, {
    headers: { "X-User-Id": USER_ID },
  });
  log(`HTTP ${finalRes.status}`);
  log(finalRes.text);
  const status = finalRes.json?.status;
  const ticket = finalRes.json?.ticketReference ?? "—";
  log("");
  log("=========================================");
  log("  Result");
  log("=========================================");
  log(`  Status           : ${status}`);
  log(`  Ticket Reference : ${ticket}`);
  if (status === "CONFIRMED") {
    log("");
    log("  SUCCESS — full Saga completed:");
    log("    hold → HELD");
    log("    confirm → PAYMENT_PENDING → payment-initiated on Kafka");
    log("    payment-service webhook → SUCCEEDED → payment-completed on Kafka");
    log("    booking-service → CONFIRMED + ticket-issued on Kafka");
    log("");
    log("  View all topics at: http://localhost:9000");
  } else {
    log("");
    log(`  WARN: Status is '${status}' — Saga may still be in flight.`);
    log(`  Try: curl -s ${BASE}/bookings/${bookingId} -H 'X-User-Id: ${USER_ID}'`);
  }
  log("");

  // ── Step 6: confirm the payment-service side is now SUCCEEDED ───────────────
  log(`>>> 6. GET /payments/${paymentId} (payment-service, should be SUCCEEDED)`);
  log((await call("GET", `${PAYMENT_BASE}/payments/${paymentId}`)).text);
  log("");

  // ── Step 7: notification-service — independently consumed ticket-issued ─────
  log(`>>> 7. GET /notifications/bookings/${bookingId} (notification-service)`);
  log("    booking-service published ticket-issued back in Step 5 when the booking was CONFIRMED.");
  log("    notification-service independently consumed it and recorded a confirmation notification.");
  const notificationRes = await poll(
    () => call("GET", `${NOTIFICATION_BASE}/notifications/bookings/${bookingId}`),
    (res) => res.status === 200
  );
  expect(notificationRes, 200);
  log("");
  if (notificationRes.json.type === "TICKET_ISSUED" && notificationRes.json.status === "SENT") {
    log("  SUCCESS — notification-service recorded a SENT TICKET_ISSUED notification.");
  } else {
    log(`  WARN: Expected type=TICKET_ISSUED status=SENT, got type=${notificationRes.json.type} status=${notificationRes.json.status}`);
  }
  log("");

  // ── Step 8: join the waitlist for the demo event ─────────────────────────────
  log(">>> 8. POST /waitlist (waitlist-service)");
  log("    Joining the waitlist for the same event the demo booking is for.");
  const waitlistJoinRes = await call("POST", `${WAITLIST_BASE}/waitlist`, {
    headers: { "X-User-Id": "00000000-0000-0000-0000-000000000042" },
    body: { eventId },
  });
  expect(waitlistJoinRes, 201);
  const waitlistEntryId = waitlistJoinRes.json.id;
  log("");
  log(`    Waitlist entry: ${waitlistEntryId} (status: WAITING)`);
  log("");

  // ── Step 9: cancel the CONFIRMED booking — publishes booking-cancelled ──────
  log(`>>> 9. POST /bookings/${bookingId}/cancel (booking-service)`);
  log("    Booking is CONFIRMED (from Step 5) — cancelling it publishes booking-cancelled,");
  log("    consumed by payment-service (refund), notification-service, and waitlist-service.");
  const cancelRes = await call("POST", `${BASE}/bookings/${bookingId}/cancel`, {
    headers: { "X-User-Id": USER_ID },
  });
  expect(cancelRes, 200);
  log("");
  if (cancelRes.json.status === "CANCELLED") {
    log("  SUCCESS — booking cancelled, booking-cancelled published to Kafka.");
  } else {
    log(`  WARN: Expected status=CANCELLED, got status=${cancelRes.json.status}`);
  }
  log("");

  // ── Step 10: waitlist-service — promoted in response to booking-cancelled ────
  log(`>>> 10. GET /waitlist/${waitlistEntryId} (waitlist-service, should be PROMOTED)`);
  log("    waitlist-service independently consumed the booking-cancelled event from Step 9");
  log("    and promoted the next (only) waiting entry for this event.");
  const waitlistGetRes = await poll(
    () => call("GET", `${WAITLIST_BASE}/waitlist/${waitlistEntryId}`),
    (res) => res.json?.status === "PROMOTED"
  );
  log(`HTTP ${waitlistGetRes.status}`);
  log(waitlistGetRes.text);
  log("");
  if (waitlistGetRes.json?.status === "PROMOTED") {
    log("  SUCCESS — waitlist entry promoted, waitlist-promoted published to Kafka.");
  } else {
    log(`  WARN: Expected status=PROMOTED, got status=${waitlistGetRes.json?.status}`);
  }
  log("");

  // ── Step 11: notification-service — recorded booking-cancelled from Step 9 ──
  log(`>>> 11. GET /notifications/bookings/${bookingId} (notification-service, should now be BOOKING_CANCELLED)`);
  log("    notification-service independently consumed the booking-cancelled event from Step 9.");
  log("    This booking already has a TICKET_ISSUED notification from Step 7 — the endpoint");
  log("    returns the most recent one, which is now the cancellation.");
  const bcNotificationRes = await poll(
    () => call("GET", `${NOTIFICATION_BASE}/notifications/bookings/${bookingId}`),
    (res) => res.json?.type === "BOOKING_CANCELLED"
  );
  log(`HTTP ${bcNotificationRes.status}`);
  log(bcNotificationRes.text);
  log("");
  if (bcNotificationRes.json?.type === "BOOKING_CANCELLED") {
    log("  SUCCESS — notification-service recorded a BOOKING_CANCELLED notification.");
  } else {
    log(`  WARN: Expected type=BOOKING_CANCELLED, got type=${bcNotificationRes.json?.type}`);
  }
  log("");

  // ── Step 12: notification-service — recorded waitlist-promoted from Step 10 ──
  log(`>>> 12. GET /notifications/waitlist-entries/${waitlistEntryId} (notification-service)`);
  log("    notification-service independently consumed the waitlist-promoted event from Step 10.");
  const wpNotificationRes = await poll(
    () => call("GET", `${NOTIFICATION_BASE}/notifications/waitlist-entries/${waitlistEntryId}`),
    (res) => res.status === 200
  );
  expect(wpNotificationRes, 200);
  if (wpNotificationRes.json.type === "WAITLIST_PROMOTED" && wpNotificationRes.json.status === "SENT") {
    log("  SUCCESS — notification-service recorded a SENT WAITLIST_PROMOTED notification.");
  } else {
    log(`  WARN: Expected type=WAITLIST_PROMOTED status=SENT, got type=${wpNotificationRes.json.type} status=${wpNotificationRes.json.status}`);
  }
  log("");

  // ── Step 13: api-gateway — read routed to event-service ──────────────────────
  log(">>> 13. GET /events?city=London (api-gateway, port 8080 → event-service)");
  log("    Same call as Step 0d, but through the single client-facing entry point instead");
  log("    of event-service's own port — proves the gateway's read-path routing works.");
  const gatewayEventsRes = await call("GET", `${GATEWAY_BASE}/events?city=London`);
  log(`HTTP ${gatewayEventsRes.status}`);
  log("");
  if (gatewayEventsRes.status === 200) {
    log("  SUCCESS — api-gateway routed the read through to event-service.");
  } else {
    log(`  WARN: Expected 200, got ${gatewayEventsRes.status}`);
  }
  log("");

  // ── Step 14: api-gateway — write routed to booking-service ───────────────────
  log(">>> 14. POST /bookings/hold (api-gateway, port 8080 → booking-service)");
  log("    A fresh hold on a different real seat, issued through the gateway instead of");
  log("    booking-service's own port — proves the gateway's write-path routing (with a");
  log("    request body) works.");
  const gatewayHoldRes = await call("POST", `${GATEWAY_BASE}/bookings/hold`, {
    headers: { "X-User-Id": USER_ID },
    body: { eventId, seatId: gatewaySeatId },
  });
  log(`HTTP ${gatewayHoldRes.status}`);
  log(gatewayHoldRes.text);
  log("");
  if (gatewayHoldRes.status === 201) {
    log("  SUCCESS — api-gateway routed the write through to booking-service.");
  } else {
    log(`  WARN: Expected 201, got ${gatewayHoldRes.status}`);
  }
  log("");

  // ── Step 15: payment-service — real refund after booking-cancelled ───────────
  log(`>>> 15. GET /payments/bookings/${bookingId} (payment-service, should be REFUNDED)`);
  log("    Step 9 cancelled this CONFIRMED booking, publishing booking-cancelled. payment-service's");
  log("    own payment for it was already SUCCEEDED (the stub's self-delivered webhook from");
  log("    Step 3), so its booking-cancelled consumer should have issued a stub refund and");
  log("    transitioned it here.");
  const refundRes = await poll(
    () => call("GET", `${PAYMENT_BASE}/payments/bookings/${bookingId}`),
    (res) => res.status === 200
  );
  log(`HTTP ${refundRes.status}`);
  log(refundRes.text);
  log("");
  if (refundRes.json?.status === "REFUNDED") {
    log("  SUCCESS — payment-service refunded the payment after booking-cancelled.");
  } else {
    log(`  WARN: Expected status=REFUNDED, got ${refundRes.json?.status}`);
  }
  log("");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
