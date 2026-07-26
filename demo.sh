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
USER_ID="00000000-0000-0000-0000-000000000099"
EVENT_ID="$(powershell -Command '[System.Guid]::NewGuid().ToString()' | tr -d '\r')"
SEAT_ID="$(powershell -Command '[System.Guid]::NewGuid().ToString()' | tr -d '\r')"

echo ""
echo "========================================="
echo "  Event Ticketing — Happy Path Demo"
echo "========================================="
echo "Event ID : $EVENT_ID"
echo "Seat ID  : $SEAT_ID"
echo ""

# ── Step 1: Hold the seat ────────────────────────────────────────────────────
echo ">>> 1. POST /bookings/hold"
HOLD_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE/bookings/hold" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -d "{
    \"eventId\": \"$EVENT_ID\",
    \"seatId\": \"$SEAT_ID\",
    \"eventTitle\": \"Coldplay: Music of the Spheres Tour\",
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
