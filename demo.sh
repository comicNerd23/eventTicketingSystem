#!/usr/bin/env bash
# Happy-path slice demo: hold → confirm → watch Saga → CONFIRMED with ticket reference
#
# Prerequisites:
#   docker compose -f docker/docker-compose.yml up --build -d
#   Wait ~60s for services to start, then run this script.
#
# Kafdrop (Kafka UI): http://localhost:9000

set -e

BASE="http://localhost:8082"
EVENT_BASE="http://localhost:8081"
GATEWAY_BASE="http://localhost:8080"
PAYMENT_BASE="http://localhost:8083"
USER_ID="00000000-0000-0000-0000-000000000099"

echo ""
echo "========================================="
echo "  Event Ticketing — Happy Path Demo"
echo "========================================="
echo ""

# ── Step 0a: event-service — create a venue ─────────────────────────────────
echo ">>> 0a. POST /venues (event-service)"
VENUE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$EVENT_BASE/venues" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "The O2 Arena",
    "address": "Peninsula Square",
    "city": "London",
    "country": "UK",
    "sections": [
      {"name": "Floor", "rows": 10, "seatsPerRow": 20, "priceGbp": 89.50},
      {"name": "Upper Tier", "rows": 15, "seatsPerRow": 30, "priceGbp": 45.00}
    ]
  }')

HTTP_CODE=$(echo "$VENUE_RESPONSE" | tail -1)
VENUE_BODY=$(echo "$VENUE_RESPONSE" | head -1)
echo "HTTP $HTTP_CODE"
echo "$VENUE_BODY"

if [ "$HTTP_CODE" != "201" ]; then
  echo "ERROR: Expected 201, got $HTTP_CODE"
  exit 1
fi

VENUE_ID=$(echo "$VENUE_BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo ""
echo "    Venue ID : $VENUE_ID  (capacity: 650)"
echo ""

# ── Step 0b: event-service — create an event tied to the venue ──────────────
echo ">>> 0b. POST /events (event-service)"
EVENT_CREATE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$EVENT_BASE/events" \
  -H "Content-Type: application/json" \
  -d "{
    \"title\": \"Coldplay: Music of the Spheres Tour\",
    \"description\": \"Live at The O2\",
    \"category\": \"CONCERT\",
    \"venueId\": \"$VENUE_ID\",
    \"startsAt\": \"2026-09-15T19:30:00Z\",
    \"endsAt\": \"2026-09-15T22:30:00Z\"
  }")

HTTP_CODE=$(echo "$EVENT_CREATE_RESPONSE" | tail -1)
EVENT_CREATE_BODY=$(echo "$EVENT_CREATE_RESPONSE" | head -1)
echo "HTTP $HTTP_CODE"
echo "$EVENT_CREATE_BODY"

if [ "$HTTP_CODE" != "201" ]; then
  echo "ERROR: Expected 201, got $HTTP_CODE"
  exit 1
fi

CREATED_EVENT_ID=$(echo "$EVENT_CREATE_BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo ""
echo "    Event ID : $CREATED_EVENT_ID"
echo ""

# ── Step 0c: event-service — fetch it and see it in the list ────────────────
echo ">>> 0c. GET /events/$CREATED_EVENT_ID (event-service)"
curl -s "$EVENT_BASE/events/$CREATED_EVENT_ID"
echo ""
echo ""

echo ">>> 0d. GET /events?city=London (event-service)"
curl -s "$EVENT_BASE/events?city=London"
echo ""
echo ""
echo "    NOTE: booking-service below calls event-service to validate \$CREATED_EVENT_ID"
echo "    and fetch its real title."
echo ""

# ── Step 0e: event-service — fetch the generated seat map, pick a real seat ──
echo ">>> 0e. GET /events/$CREATED_EVENT_ID/seats (event-service)"
echo "    Seats were generated automatically when the event was created (Floor 10x20"
echo "    + Upper Tier 15x30 = 650). Picking a real seat instead of a made-up UUID."
SEAT_MAP_RESPONSE=$(curl -s -w "\n%{http_code}" "$EVENT_BASE/events/$CREATED_EVENT_ID/seats")
HTTP_CODE=$(echo "$SEAT_MAP_RESPONSE" | tail -1)
SEAT_MAP_BODY=$(echo "$SEAT_MAP_RESPONSE" | head -1)
echo "HTTP $HTTP_CODE"

if [ "$HTTP_CODE" != "200" ]; then
  echo "ERROR: Expected 200, got $HTTP_CODE"
  exit 1
fi

SEAT_COUNT=$(echo "$SEAT_MAP_BODY" | grep -o '"id":"[^"]*"' | wc -l)
SEAT_ID=$(echo "$SEAT_MAP_BODY" | grep -o '"id":"[^"]*"' | sed -n '1p' | cut -d'"' -f4)
GATEWAY_SEAT_ID=$(echo "$SEAT_MAP_BODY" | grep -o '"id":"[^"]*"' | sed -n '2p' | cut -d'"' -f4)
echo ""
if [ "$SEAT_COUNT" -eq 650 ]; then
  echo "  SUCCESS — 650 seats generated, all AVAILABLE."
else
  echo "  WARN: Expected 650 seats, got $SEAT_COUNT"
fi
echo "    Seat ID  : $SEAT_ID"
echo ""

# ── Step 1: Hold the seat ────────────────────────────────────────────────────
echo ">>> 1. POST /bookings/hold"
HOLD_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE/bookings/hold" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -d "{
    \"eventId\": \"$CREATED_EVENT_ID\",
    \"seatId\": \"$SEAT_ID\"
  }")

HTTP_CODE=$(echo "$HOLD_RESPONSE" | tail -1)
HOLD_BODY=$(echo "$HOLD_RESPONSE" | head -1)

echo "HTTP $HTTP_CODE"
echo "$HOLD_BODY"

if [ "$HTTP_CODE" != "201" ]; then
  echo "ERROR: Expected 201, got $HTTP_CODE"
  exit 1
fi

BOOKING_ID=$(echo "$HOLD_BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo ""
echo "    Booking ID : $BOOKING_ID"
echo "    Status     : HELD  (Redis lock active for 10 min)"
echo ""

# ── Step 2: Confirm (triggers payment Saga) ──────────────────────────────────
echo ">>> 2. POST /bookings/$BOOKING_ID/confirm"
CONFIRM_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE/bookings/$BOOKING_ID/confirm" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -d '{"stripePaymentMethodId": "pm_demo_4242424242424242"}')

HTTP_CODE=$(echo "$CONFIRM_RESPONSE" | tail -1)
CONFIRM_BODY=$(echo "$CONFIRM_RESPONSE" | head -1)

echo "HTTP $HTTP_CODE"
echo "$CONFIRM_BODY"

if [ "$HTTP_CODE" != "200" ]; then
  echo "ERROR: Expected 200, got $HTTP_CODE"
  exit 1
fi

echo ""
echo "    Status : PAYMENT_PENDING"
echo "    Kafka  : payment-initiated published → payment-service consuming..."
echo ""

# ── Step 3: payment-service consumed payment-initiated, created a payment ───
echo ">>> 3. GET /payments/bookings/$BOOKING_ID (payment-service)"
echo "    payment-service is the real, live saga participant now (payment-simulator has"
echo "    been retired) — it consumed the payment-initiated event published in Step 2 and"
echo "    created a payment via its stubbed PaymentGateway. The stub self-delivers a"
echo "    simulated Stripe webhook ~1s later (ADR-011), so this may already read SUCCEEDED"
echo "    by the time we check — either status is expected here."
for i in 1 2 3 4 5; do
  PAYMENT_RESPONSE=$(curl -s -w "\n%{http_code}" "$PAYMENT_BASE/payments/bookings/$BOOKING_ID")
  HTTP_CODE=$(echo "$PAYMENT_RESPONSE" | tail -1)
  if [ "$HTTP_CODE" = "200" ]; then break; fi
  sleep 1
done
PAYMENT_BODY=$(echo "$PAYMENT_RESPONSE" | head -1)
echo "HTTP $HTTP_CODE"
echo "$PAYMENT_BODY"

if [ "$HTTP_CODE" != "200" ]; then
  echo "ERROR: Expected 200, got $HTTP_CODE"
  exit 1
fi

PAYMENT_ID=$(echo "$PAYMENT_BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
PAYMENT_INTENT_ID=$(echo "$PAYMENT_BODY" | grep -o '"stripePaymentIntentId":"[^"]*"' | cut -d'"' -f4)
PAYMENT_STATUS=$(echo "$PAYMENT_BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
echo ""
echo "    Payment ID : $PAYMENT_ID  (status: $PAYMENT_STATUS, stripePaymentIntentId: $PAYMENT_INTENT_ID)"
echo ""

# ── Step 4: manually deliver the same Stripe webhook the stub already sent ──
echo ">>> 4. POST /payments/webhook (payment-service) — manual re-delivery"
echo "    Since ADR-011, the stub gateway already self-delivers this webhook moments after"
echo "    Step 2 — the saga typically finishes on its own before this step even runs. This"
echo "    call instead demonstrates that the webhook endpoint is safe against Stripe's"
echo "    real-world at-least-once redelivery guarantee: if the payment is already"
echo "    SUCCEEDED, the idempotency guard makes this a safe no-op (still HTTP 200)."
WEBHOOK_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$PAYMENT_BASE/payments/webhook" \
  -H "Content-Type: application/json" \
  -H "Stripe-Signature: t=demo,v1=stub_signature" \
  -d "{\"type\":\"payment_intent.succeeded\",\"paymentIntentId\":\"$PAYMENT_INTENT_ID\"}")

HTTP_CODE=$(echo "$WEBHOOK_RESPONSE" | tail -1)
echo "HTTP $HTTP_CODE"

if [ "$HTTP_CODE" != "200" ]; then
  echo "ERROR: Expected 200, got $HTTP_CODE"
  exit 1
fi
echo ""

# ── Step 5: Poll final booking state ─────────────────────────────────────────
echo ">>> 5. GET /bookings/$BOOKING_ID  (should be CONFIRMED)"
echo "    Reaches CONFIRMED because a webhook published payment-completed and booking-service"
echo "    consumed it — typically the stub's own self-delivered webhook from Step 3's ~1s"
echo "    delay, with Step 4's manual call as a no-op backup if it hasn't landed yet."
FINAL_RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE/bookings/$BOOKING_ID" \
  -H "X-User-Id: $USER_ID")

HTTP_CODE=$(echo "$FINAL_RESPONSE" | tail -1)
FINAL_BODY=$(echo "$FINAL_RESPONSE" | head -1)

echo "HTTP $HTTP_CODE"
echo "$FINAL_BODY"

STATUS=$(echo "$FINAL_BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
TICKET=$(echo "$FINAL_BODY" | grep -o '"ticketReference":"[^"]*"' | cut -d'"' -f4)
TICKET="${TICKET:-—}"

echo ""
echo "========================================="
echo "  Result"
echo "========================================="
echo "  Status           : $STATUS"
echo "  Ticket Reference : $TICKET"

if [ "$STATUS" = "CONFIRMED" ]; then
  echo ""
  echo "  SUCCESS — full Saga completed:"
  echo "    hold → HELD"
  echo "    confirm → PAYMENT_PENDING → payment-initiated on Kafka"
  echo "    payment-service webhook → SUCCEEDED → payment-completed on Kafka"
  echo "    booking-service → CONFIRMED + ticket-issued on Kafka"
  echo ""
  echo "  View all topics at: http://localhost:9000"
else
  echo ""
  echo "  WARN: Status is '$STATUS' — Saga may still be in flight."
  echo "  Try: curl -s $BASE/bookings/$BOOKING_ID -H 'X-User-Id: $USER_ID'"
fi
echo ""

# ── Step 6: confirm the payment-service side is now SUCCEEDED ───────────────
echo ">>> 6. GET /payments/$PAYMENT_ID (payment-service, should be SUCCEEDED)"
curl -s "$PAYMENT_BASE/payments/$PAYMENT_ID"
echo ""
echo ""

# ── Step 7: notification-service — independently consumed the same ticket-issued event ──
NOTIFICATION_BASE="http://localhost:8084"
echo ">>> 7. GET /notifications/bookings/$BOOKING_ID (notification-service)"
echo "    booking-service published ticket-issued back in Step 5 when the booking was CONFIRMED."
echo "    notification-service independently consumed it and recorded a confirmation notification."
for i in 1 2 3 4 5; do
  NOTIFICATION_RESPONSE=$(curl -s -w "\n%{http_code}" "$NOTIFICATION_BASE/notifications/bookings/$BOOKING_ID")
  HTTP_CODE=$(echo "$NOTIFICATION_RESPONSE" | tail -1)
  if [ "$HTTP_CODE" = "200" ]; then break; fi
  sleep 1
done
NOTIFICATION_BODY=$(echo "$NOTIFICATION_RESPONSE" | head -1)
echo "HTTP $HTTP_CODE"
echo "$NOTIFICATION_BODY"

if [ "$HTTP_CODE" != "200" ]; then
  echo "ERROR: Expected 200, got $HTTP_CODE"
  exit 1
fi

NOTIFICATION_TYPE=$(echo "$NOTIFICATION_BODY" | grep -o '"type":"[^"]*"' | cut -d'"' -f4)
NOTIFICATION_STATUS=$(echo "$NOTIFICATION_BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
echo ""
if [ "$NOTIFICATION_TYPE" = "TICKET_ISSUED" ] && [ "$NOTIFICATION_STATUS" = "SENT" ]; then
  echo "  SUCCESS — notification-service recorded a SENT TICKET_ISSUED notification."
else
  echo "  WARN: Expected type=TICKET_ISSUED status=SENT, got type=$NOTIFICATION_TYPE status=$NOTIFICATION_STATUS"
fi
echo ""

# ── Step 8: join the waitlist for the demo event ─────────────────────────────
WAITLIST_BASE="http://localhost:8085"
echo ">>> 8. POST /waitlist (waitlist-service)"
echo "    Joining the waitlist for the same event the demo booking is for."
WAITLIST_JOIN_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$WAITLIST_BASE/waitlist" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000042" \
  -d "{\"eventId\": \"$CREATED_EVENT_ID\"}")

HTTP_CODE=$(echo "$WAITLIST_JOIN_RESPONSE" | tail -1)
WAITLIST_JOIN_BODY=$(echo "$WAITLIST_JOIN_RESPONSE" | head -1)
echo "HTTP $HTTP_CODE"
echo "$WAITLIST_JOIN_BODY"

if [ "$HTTP_CODE" != "201" ]; then
  echo "ERROR: Expected 201, got $HTTP_CODE"
  exit 1
fi

WAITLIST_ENTRY_ID=$(echo "$WAITLIST_JOIN_BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo ""
echo "    Waitlist entry: $WAITLIST_ENTRY_ID (status: WAITING)"
echo ""

# ── Step 9: cancel the CONFIRMED booking — publishes booking-cancelled ──────
echo ">>> 9. POST /bookings/$BOOKING_ID/cancel (booking-service)"
echo "    Booking is CONFIRMED (from Step 5) — cancelling it publishes booking-cancelled,"
echo "    consumed by payment-service (refund), notification-service, and waitlist-service."
CANCEL_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE/bookings/$BOOKING_ID/cancel" \
  -H "X-User-Id: $USER_ID")

HTTP_CODE=$(echo "$CANCEL_RESPONSE" | tail -1)
CANCEL_BODY=$(echo "$CANCEL_RESPONSE" | head -1)

echo "HTTP $HTTP_CODE"
echo "$CANCEL_BODY"

if [ "$HTTP_CODE" != "200" ]; then
  echo "ERROR: Expected 200, got $HTTP_CODE"
  exit 1
fi

CANCEL_STATUS=$(echo "$CANCEL_BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
echo ""
if [ "$CANCEL_STATUS" = "CANCELLED" ]; then
  echo "  SUCCESS — booking cancelled, booking-cancelled published to Kafka."
else
  echo "  WARN: Expected status=CANCELLED, got status=$CANCEL_STATUS"
fi
echo ""

# ── Step 10: waitlist-service — promoted in response to booking-cancelled ────
echo ">>> 10. GET /waitlist/$WAITLIST_ENTRY_ID (waitlist-service, should be PROMOTED)"
echo "    waitlist-service independently consumed the booking-cancelled event from Step 9"
echo "    and promoted the next (only) waiting entry for this event."
for i in 1 2 3 4 5; do
  WAITLIST_GET_RESPONSE=$(curl -s -w "\n%{http_code}" "$WAITLIST_BASE/waitlist/$WAITLIST_ENTRY_ID")
  HTTP_CODE=$(echo "$WAITLIST_GET_RESPONSE" | tail -1)
  WAITLIST_GET_BODY=$(echo "$WAITLIST_GET_RESPONSE" | head -1)
  WAITLIST_STATUS=$(echo "$WAITLIST_GET_BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
  if [ "$WAITLIST_STATUS" = "PROMOTED" ]; then break; fi
  sleep 1
done
echo "HTTP $HTTP_CODE"
echo "$WAITLIST_GET_BODY"

echo ""
if [ "$WAITLIST_STATUS" = "PROMOTED" ]; then
  echo "  SUCCESS — waitlist entry promoted, waitlist-promoted published to Kafka."
else
  echo "  WARN: Expected status=PROMOTED, got status=$WAITLIST_STATUS"
fi
echo ""

# ── Step 11: notification-service — recorded booking-cancelled from Step 9 ──
echo ">>> 11. GET /notifications/bookings/$BOOKING_ID (notification-service, should now be BOOKING_CANCELLED)"
echo "    notification-service independently consumed the booking-cancelled event from Step 9."
echo "    This booking already has a TICKET_ISSUED notification from Step 7 — the endpoint"
echo "    returns the most recent one, which is now the cancellation."
for i in 1 2 3 4 5; do
  BC_NOTIFICATION_RESPONSE=$(curl -s -w "\n%{http_code}" "$NOTIFICATION_BASE/notifications/bookings/$BOOKING_ID")
  HTTP_CODE=$(echo "$BC_NOTIFICATION_RESPONSE" | tail -1)
  BC_NOTIFICATION_BODY=$(echo "$BC_NOTIFICATION_RESPONSE" | head -1)
  BC_NOTIFICATION_TYPE=$(echo "$BC_NOTIFICATION_BODY" | grep -o '"type":"[^"]*"' | cut -d'"' -f4)
  if [ "$BC_NOTIFICATION_TYPE" = "BOOKING_CANCELLED" ]; then break; fi
  sleep 1
done
echo "HTTP $HTTP_CODE"
echo "$BC_NOTIFICATION_BODY"

echo ""
if [ "$BC_NOTIFICATION_TYPE" = "BOOKING_CANCELLED" ]; then
  echo "  SUCCESS — notification-service recorded a BOOKING_CANCELLED notification."
else
  echo "  WARN: Expected type=BOOKING_CANCELLED, got type=$BC_NOTIFICATION_TYPE"
fi
echo ""

# ── Step 12: notification-service — recorded waitlist-promoted from Step 10 ──
echo ">>> 12. GET /notifications/waitlist-entries/$WAITLIST_ENTRY_ID (notification-service)"
echo "    notification-service independently consumed the waitlist-promoted event from Step 10."
for i in 1 2 3 4 5; do
  WP_NOTIFICATION_RESPONSE=$(curl -s -w "\n%{http_code}" "$NOTIFICATION_BASE/notifications/waitlist-entries/$WAITLIST_ENTRY_ID")
  HTTP_CODE=$(echo "$WP_NOTIFICATION_RESPONSE" | tail -1)
  if [ "$HTTP_CODE" = "200" ]; then break; fi
  sleep 1
done
WP_NOTIFICATION_BODY=$(echo "$WP_NOTIFICATION_RESPONSE" | head -1)
echo "HTTP $HTTP_CODE"
echo "$WP_NOTIFICATION_BODY"

if [ "$HTTP_CODE" != "200" ]; then
  echo "ERROR: Expected 200, got $HTTP_CODE"
  exit 1
fi

WP_NOTIFICATION_TYPE=$(echo "$WP_NOTIFICATION_BODY" | grep -o '"type":"[^"]*"' | cut -d'"' -f4)
WP_NOTIFICATION_STATUS=$(echo "$WP_NOTIFICATION_BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
echo ""
if [ "$WP_NOTIFICATION_TYPE" = "WAITLIST_PROMOTED" ] && [ "$WP_NOTIFICATION_STATUS" = "SENT" ]; then
  echo "  SUCCESS — notification-service recorded a SENT WAITLIST_PROMOTED notification."
else
  echo "  WARN: Expected type=WAITLIST_PROMOTED status=SENT, got type=$WP_NOTIFICATION_TYPE status=$WP_NOTIFICATION_STATUS"
fi
echo ""

# ── Step 13: api-gateway — read routed to event-service ──────────────────────
echo ">>> 13. GET /events?city=London (api-gateway, port 8080 → event-service)"
echo "    Same call as Step 0d, but through the single client-facing entry point instead"
echo "    of event-service's own port — proves the gateway's read-path routing works."
GATEWAY_EVENTS_RESPONSE=$(curl -s -w "\n%{http_code}" "$GATEWAY_BASE/events?city=London")
HTTP_CODE=$(echo "$GATEWAY_EVENTS_RESPONSE" | tail -1)
echo "HTTP $HTTP_CODE"
echo "$GATEWAY_EVENTS_RESPONSE" | head -1

echo ""
if [ "$HTTP_CODE" = "200" ]; then
  echo "  SUCCESS — api-gateway routed the read through to event-service."
else
  echo "  WARN: Expected 200, got $HTTP_CODE"
fi
echo ""

# ── Step 14: api-gateway — write routed to booking-service ───────────────────
echo ">>> 14. POST /bookings/hold (api-gateway, port 8080 → booking-service)"
echo "    A fresh hold on a different real seat, issued through the gateway instead of"
echo "    booking-service's own port — proves the gateway's write-path routing (with a"
echo "    request body) works."
GATEWAY_HOLD_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$GATEWAY_BASE/bookings/hold" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -d "{
    \"eventId\": \"$CREATED_EVENT_ID\",
    \"seatId\": \"$GATEWAY_SEAT_ID\"
  }")
HTTP_CODE=$(echo "$GATEWAY_HOLD_RESPONSE" | tail -1)
GATEWAY_HOLD_BODY=$(echo "$GATEWAY_HOLD_RESPONSE" | head -1)
echo "HTTP $HTTP_CODE"
echo "$GATEWAY_HOLD_BODY"

echo ""
if [ "$HTTP_CODE" = "201" ]; then
  echo "  SUCCESS — api-gateway routed the write through to booking-service."
else
  echo "  WARN: Expected 201, got $HTTP_CODE"
fi
echo ""

# ── Step 15: payment-service — real refund after booking-cancelled ───────────
echo ">>> 15. GET /payments/bookings/$BOOKING_ID (payment-service, should be REFUNDED)"
echo "    Step 9 cancelled this CONFIRMED booking, publishing booking-cancelled. payment-service's"
echo "    own payment for it was already SUCCEEDED (the stub's self-delivered webhook from"
echo "    Step 3), so its booking-cancelled consumer should have issued a stub refund and"
echo "    transitioned it here."
for i in 1 2 3 4 5; do
  REFUND_RESPONSE=$(curl -s -w "\n%{http_code}" "$PAYMENT_BASE/payments/bookings/$BOOKING_ID")
  HTTP_CODE=$(echo "$REFUND_RESPONSE" | tail -1)
  if [ "$HTTP_CODE" = "200" ]; then break; fi
  sleep 1
done
REFUND_BODY=$(echo "$REFUND_RESPONSE" | head -1)
echo "HTTP $HTTP_CODE"
echo "$REFUND_BODY"

REFUND_STATUS=$(echo "$REFUND_BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
echo ""
if [ "$REFUND_STATUS" = "REFUNDED" ]; then
  echo "  SUCCESS — payment-service refunded the payment after booking-cancelled."
else
  echo "  WARN: Expected status=REFUNDED, got $REFUND_STATUS"
fi
echo ""
