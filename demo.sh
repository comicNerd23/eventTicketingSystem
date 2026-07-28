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
USER_ID="00000000-0000-0000-0000-000000000099"
SEAT_ID="$(powershell -Command '[System.Guid]::NewGuid().ToString()' | tr -d '\r')"

echo ""
echo "========================================="
echo "  Event Ticketing — Happy Path Demo"
echo "========================================="
echo "Seat ID  : $SEAT_ID"
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
      {"name": "Floor", "rows": 10, "seatsPerRow": 20},
      {"name": "Upper Tier", "rows": 15, "seatsPerRow": 30}
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
echo "    and fetch its real title. Seat-level data (seatId/seatLabel/priceGbp) is"
echo "    still client-supplied — event-service has no per-seat model yet."
echo ""

# ── Step 1: Hold the seat ────────────────────────────────────────────────────
echo ">>> 1. POST /bookings/hold"
HOLD_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE/bookings/hold" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -d "{
    \"eventId\": \"$CREATED_EVENT_ID\",
    \"seatId\": \"$SEAT_ID\",
    \"seatLabel\": \"B7\",
    \"priceGbp\": 89.50
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
echo "    Kafka  : payment-initiated published → payment-simulator consuming..."
echo ""

# ── Step 3: Wait for Saga to complete ───────────────────────────────────────
echo ">>> 3. Waiting 3s for payment-completed → ticket-issued Saga..."
sleep 3
echo ""

# ── Step 4: Poll final state ─────────────────────────────────────────────────
echo ">>> 4. GET /bookings/$BOOKING_ID  (should be CONFIRMED)"
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
  echo "    simulator → payment-completed on Kafka"
  echo "    booking-service → CONFIRMED + ticket-issued on Kafka"
  echo ""
  echo "  View all topics at: http://localhost:9000"
else
  echo ""
  echo "  WARN: Status is '$STATUS' — Saga may still be in flight."
  echo "  Try: curl -s $BASE/bookings/$BOOKING_ID -H 'X-User-Id: $USER_ID'"
fi
echo ""

# ── Step 5: payment-service — independently consumed the same payment-initiated event ──
PAYMENT_BASE="http://localhost:8083"
echo ">>> 5. GET /payments/bookings/$BOOKING_ID (payment-service)"
echo "    payment-service has its own Kafka consumer group, distinct from payment-simulator's,"
echo "    so it independently received the same payment-initiated event published in Step 2."
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
echo ""
echo "    Payment ID : $PAYMENT_ID  (status: PENDING, stripePaymentIntentId: $PAYMENT_INTENT_ID)"
echo ""

# ── Step 6: simulate Stripe's async webhook confirming the charge ──────────
echo ">>> 6. POST /payments/webhook (payment-service) — simulated Stripe confirmation"
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

# ── Step 7: confirm the payment-service side is now SUCCEEDED ───────────────
echo ">>> 7. GET /payments/$PAYMENT_ID (payment-service, should be SUCCEEDED)"
curl -s "$PAYMENT_BASE/payments/$PAYMENT_ID"
echo ""
echo ""
echo "    NOTE: payment-service published its own payment-completed for this booking."
echo "    booking-service already CONFIRMED this booking earlier (Step 4) via payment-simulator's"
echo "    payment-completed — its handlePaymentCompleted() no-ops on this second, later event"
echo "    (booking already CONFIRMED). This is expected while both consumers coexist — see"
echo "    docs/plan.md for the follow-up once payment-service replaces payment-simulator."
echo ""
