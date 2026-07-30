#!/usr/bin/env bash
# Reseeds event-service with a small, varied catalog of demo events.
#
# event-service has no DELETE endpoint (deliberately — nothing in the real API surface
# needs it), so this truncates its Postgres tables directly via docker exec. That's the
# same "pure disposable demo data, no real loss" precedent already used earlier in this
# project (see docs/plan.md, event-service seat-generation slice) — event-service's DB
# exists purely to demo against, never treated as data worth preserving across resets.
#
# Booking-service is untouched: any historical bookings referencing the old event/seat
# ids just become orphaned demo history, which is harmless — nothing re-validates a
# CONFIRMED/CANCELLED booking's event against event-service after the fact.
#
# Prerequisites: docker compose -f docker/docker-compose.yml up --build -d, event-service healthy.

set -e

EVENT_BASE="http://localhost:8081"
POSTGRES_CONTAINER="docker-postgres-1"

echo ""
echo "========================================="
echo "  Reseeding event-service demo catalog"
echo "========================================="
echo ""

echo ">>> Truncating venues/events/sections/seats in ticketing_events"
docker exec "$POSTGRES_CONTAINER" psql -U ticketing -d ticketing_events -c \
  "TRUNCATE TABLE seats, sections, events, venues RESTART IDENTITY CASCADE;"
echo ""

create_event() {
  local title="$1" description="$2" category="$3" starts_at="$4" ends_at="$5"
  local venue_name="$6" address="$7" city="$8" country="$9"
  local sections_json="${10}"

  echo ">>> POST /venues — $venue_name ($city)"
  VENUE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$EVENT_BASE/venues" \
    -H "Content-Type: application/json" \
    -d "{
      \"name\": \"$venue_name\",
      \"address\": \"$address\",
      \"city\": \"$city\",
      \"country\": \"$country\",
      \"sections\": $sections_json
    }")
  HTTP_CODE=$(echo "$VENUE_RESPONSE" | tail -1)
  if [ "$HTTP_CODE" != "201" ]; then
    echo "ERROR: Expected 201 creating venue, got $HTTP_CODE"
    echo "$VENUE_RESPONSE" | head -1
    exit 1
  fi
  VENUE_ID=$(echo "$VENUE_RESPONSE" | head -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

  echo ">>> POST /events — $title"
  EVENT_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$EVENT_BASE/events" \
    -H "Content-Type: application/json" \
    -d "{
      \"title\": \"$title\",
      \"description\": \"$description\",
      \"category\": \"$category\",
      \"venueId\": \"$VENUE_ID\",
      \"startsAt\": \"$starts_at\",
      \"endsAt\": \"$ends_at\"
    }")
  HTTP_CODE=$(echo "$EVENT_RESPONSE" | tail -1)
  if [ "$HTTP_CODE" != "201" ]; then
    echo "ERROR: Expected 201 creating event, got $HTTP_CODE"
    echo "$EVENT_RESPONSE" | head -1
    exit 1
  fi
  echo "    OK"
  echo ""
}

create_event \
  "Coldplay: Music of the Spheres Tour" "Live at The O2" "CONCERT" \
  "2026-09-15T19:30:00Z" "2026-09-15T22:30:00Z" \
  "The O2 Arena" "Peninsula Square" "London" "UK" \
  '[{"name": "Floor", "rows": 10, "seatsPerRow": 20, "priceGbp": 89.50}, {"name": "Upper Tier", "rows": 15, "seatsPerRow": 30, "priceGbp": 45.00}]'

create_event \
  "Taylor Swift: The Eras Tour" "Live at Co-op Live" "CONCERT" \
  "2026-08-22T18:30:00Z" "2026-08-22T22:00:00Z" \
  "Co-op Live" "Etihad Campus" "Manchester" "UK" \
  '[{"name": "Floor", "rows": 8, "seatsPerRow": 25, "priceGbp": 150.00}, {"name": "Tier 1", "rows": 12, "seatsPerRow": 30, "priceGbp": 95.00}, {"name": "Tier 2", "rows": 10, "seatsPerRow": 40, "priceGbp": 55.00}]'

create_event \
  "Ed Sheeran: Mathematics Tour" "Live at Utilita Arena" "CONCERT" \
  "2026-10-03T19:00:00Z" "2026-10-03T22:00:00Z" \
  "Utilita Arena" "King Edwards Road" "Birmingham" "UK" \
  '[{"name": "Floor", "rows": 8, "seatsPerRow": 20, "priceGbp": 85.00}, {"name": "Upper", "rows": 10, "seatsPerRow": 25, "priceGbp": 50.00}]'

create_event \
  "Premier League: Arsenal vs Chelsea" "London derby" "SPORTS" \
  "2026-11-08T15:00:00Z" "2026-11-08T17:00:00Z" \
  "Emirates Stadium" "Hornsey Road" "London" "UK" \
  '[{"name": "Home End", "rows": 10, "seatsPerRow": 30, "priceGbp": 65.00}, {"name": "Away End", "rows": 6, "seatsPerRow": 20, "priceGbp": 70.00}, {"name": "Main Stand", "rows": 12, "seatsPerRow": 30, "priceGbp": 120.00}]'

create_event \
  "Wimbledon Championships Final" "Centre Court" "SPORTS" \
  "2026-08-30T14:00:00Z" "2026-08-30T18:00:00Z" \
  "All England Club" "Church Road" "London" "UK" \
  '[{"name": "Centre Court", "rows": 15, "seatsPerRow": 20, "priceGbp": 250.00}]'

create_event \
  "UFC Fight Night" "Main card" "SPORTS" \
  "2026-09-27T20:00:00Z" "2026-09-27T23:30:00Z" \
  "AO Arena" "Trinity Way" "Manchester" "UK" \
  '[{"name": "Cage Side", "rows": 5, "seatsPerRow": 10, "priceGbp": 300.00}, {"name": "General", "rows": 15, "seatsPerRow": 30, "priceGbp": 75.00}]'

create_event \
  "Hamilton" "The story of America then, told by America now" "THEATRE" \
  "2026-08-14T19:30:00Z" "2026-08-14T22:15:00Z" \
  "Victoria Palace Theatre" "Victoria Street" "London" "UK" \
  '[{"name": "Stalls", "rows": 15, "seatsPerRow": 20, "priceGbp": 150.00}, {"name": "Circle", "rows": 10, "seatsPerRow": 25, "priceGbp": 95.00}]'

create_event \
  "The Book of Mormon" "Tony Award-winning musical comedy" "THEATRE" \
  "2026-09-05T19:30:00Z" "2026-09-05T22:00:00Z" \
  "Prince of Wales Theatre" "Coventry Street" "London" "UK" \
  '[{"name": "Stalls", "rows": 12, "seatsPerRow": 22, "priceGbp": 89.00}, {"name": "Circle", "rows": 8, "seatsPerRow": 24, "priceGbp": 55.00}]'

create_event \
  "Live Comedy Night with John Mulaney" "Stand-up" "COMEDY" \
  "2026-08-19T20:00:00Z" "2026-08-19T22:00:00Z" \
  "AO Arena" "Trinity Way" "Manchester" "UK" \
  '[{"name": "Floor", "rows": 10, "seatsPerRow": 20, "priceGbp": 55.00}, {"name": "Tier", "rows": 12, "seatsPerRow": 25, "priceGbp": 35.00}]'

create_event \
  "Cirque du Soleil: Kurios" "Cabinet of Curiosities" "OTHER" \
  "2026-10-17T19:00:00Z" "2026-10-17T21:30:00Z" \
  "Royal Albert Hall" "Kensington Gore" "London" "UK" \
  '[{"name": "Stalls", "rows": 10, "seatsPerRow": 20, "priceGbp": 95.00}, {"name": "Circle", "rows": 8, "seatsPerRow": 20, "priceGbp": 65.00}]'

echo "========================================="
echo "  Done"
echo "========================================="
curl -s "$EVENT_BASE/events?size=20" | grep -o '"totalElements":[0-9]*'
echo ""
