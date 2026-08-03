#!/usr/bin/env node
// Reseeds event-service with a small, varied catalog of demo events.
//
// event-service has no DELETE endpoint (deliberately — nothing in the real API surface
// needs it), so this truncates its Postgres tables directly via `docker exec`. That's the
// same "pure disposable demo data, no real loss" precedent already used earlier in this
// project (see docs/plan.md, event-service seat-generation slice) — event-service's DB
// exists purely to demo against, never treated as data worth preserving across resets.
//
// Booking-service is untouched: any historical bookings referencing the old event/seat
// ids just become orphaned demo history, which is harmless — nothing re-validates a
// CONFIRMED/CANCELLED booking's event against event-service after the fact.
//
// Prerequisites: docker compose -f docker/docker-compose.yml up --build -d, event-service healthy.
//
// Cross-platform replacement for the old seed-events.sh (bash-only — needed Git Bash/WSL
// on Windows). Uses Node's built-in fetch + child_process, no dependencies.

const { execFileSync } = require("node:child_process");

const EVENT_BASE = "http://localhost:8081";
const POSTGRES_CONTAINER = "docker-postgres-1";

function log(line = "") {
  console.log(line);
}

function fail(message) {
  console.error(`ERROR: ${message}`);
  process.exit(1);
}

async function call(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
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

async function createEvent({ title, description, category, startsAt, endsAt, venue, address, city, country, sections }) {
  log(`>>> POST /venues — ${venue} (${city})`);
  const venueRes = await call("POST", `${EVENT_BASE}/venues`, {
    name: venue,
    address,
    city,
    country,
    sections,
  });
  if (venueRes.status !== 201) {
    fail(`Expected 201 creating venue, got ${venueRes.status}\n${venueRes.text}`);
  }
  const venueId = venueRes.json.id;

  log(`>>> POST /events — ${title}`);
  const eventRes = await call("POST", `${EVENT_BASE}/events`, {
    title,
    description,
    category,
    venueId,
    startsAt,
    endsAt,
  });
  if (eventRes.status !== 201) {
    fail(`Expected 201 creating event, got ${eventRes.status}\n${eventRes.text}`);
  }
  log("    OK");
  log("");
}

const EVENTS = [
  {
    title: "Coldplay: Music of the Spheres Tour", description: "Live at The O2", category: "CONCERT",
    startsAt: "2026-09-15T19:30:00Z", endsAt: "2026-09-15T22:30:00Z",
    venue: "The O2 Arena", address: "Peninsula Square", city: "London", country: "UK",
    sections: [
      { name: "Floor", rows: 10, seatsPerRow: 20, priceGbp: 89.5 },
      { name: "Upper Tier", rows: 15, seatsPerRow: 30, priceGbp: 45.0 },
    ],
  },
  {
    title: "Taylor Swift: The Eras Tour", description: "Live at Co-op Live", category: "CONCERT",
    startsAt: "2026-08-22T18:30:00Z", endsAt: "2026-08-22T22:00:00Z",
    venue: "Co-op Live", address: "Etihad Campus", city: "Manchester", country: "UK",
    sections: [
      { name: "Floor", rows: 8, seatsPerRow: 25, priceGbp: 150.0 },
      { name: "Tier 1", rows: 12, seatsPerRow: 30, priceGbp: 95.0 },
      { name: "Tier 2", rows: 10, seatsPerRow: 40, priceGbp: 55.0 },
    ],
  },
  {
    title: "Ed Sheeran: Mathematics Tour", description: "Live at Utilita Arena", category: "CONCERT",
    startsAt: "2026-10-03T19:00:00Z", endsAt: "2026-10-03T22:00:00Z",
    venue: "Utilita Arena", address: "King Edwards Road", city: "Birmingham", country: "UK",
    sections: [
      { name: "Floor", rows: 8, seatsPerRow: 20, priceGbp: 85.0 },
      { name: "Upper", rows: 10, seatsPerRow: 25, priceGbp: 50.0 },
    ],
  },
  {
    title: "Premier League: Arsenal vs Chelsea", description: "London derby", category: "SPORTS",
    startsAt: "2026-11-08T15:00:00Z", endsAt: "2026-11-08T17:00:00Z",
    venue: "Emirates Stadium", address: "Hornsey Road", city: "London", country: "UK",
    sections: [
      { name: "Home End", rows: 10, seatsPerRow: 30, priceGbp: 65.0 },
      { name: "Away End", rows: 6, seatsPerRow: 20, priceGbp: 70.0 },
      { name: "Main Stand", rows: 12, seatsPerRow: 30, priceGbp: 120.0 },
    ],
  },
  {
    title: "Wimbledon Championships Final", description: "Centre Court", category: "SPORTS",
    startsAt: "2026-08-30T14:00:00Z", endsAt: "2026-08-30T18:00:00Z",
    venue: "All England Club", address: "Church Road", city: "London", country: "UK",
    sections: [{ name: "Centre Court", rows: 15, seatsPerRow: 20, priceGbp: 250.0 }],
  },
  {
    title: "UFC Fight Night", description: "Main card", category: "SPORTS",
    startsAt: "2026-09-27T20:00:00Z", endsAt: "2026-09-27T23:30:00Z",
    venue: "AO Arena", address: "Trinity Way", city: "Manchester", country: "UK",
    sections: [
      { name: "Cage Side", rows: 5, seatsPerRow: 10, priceGbp: 300.0 },
      { name: "General", rows: 15, seatsPerRow: 30, priceGbp: 75.0 },
    ],
  },
  {
    title: "Hamilton", description: "The story of America then, told by America now", category: "THEATRE",
    startsAt: "2026-08-14T19:30:00Z", endsAt: "2026-08-14T22:15:00Z",
    venue: "Victoria Palace Theatre", address: "Victoria Street", city: "London", country: "UK",
    sections: [
      { name: "Stalls", rows: 15, seatsPerRow: 20, priceGbp: 150.0 },
      { name: "Circle", rows: 10, seatsPerRow: 25, priceGbp: 95.0 },
    ],
  },
  {
    title: "The Book of Mormon", description: "Tony Award-winning musical comedy", category: "THEATRE",
    startsAt: "2026-09-05T19:30:00Z", endsAt: "2026-09-05T22:00:00Z",
    venue: "Prince of Wales Theatre", address: "Coventry Street", city: "London", country: "UK",
    sections: [
      { name: "Stalls", rows: 12, seatsPerRow: 22, priceGbp: 89.0 },
      { name: "Circle", rows: 8, seatsPerRow: 24, priceGbp: 55.0 },
    ],
  },
  {
    title: "Live Comedy Night with John Mulaney", description: "Stand-up", category: "COMEDY",
    startsAt: "2026-08-19T20:00:00Z", endsAt: "2026-08-19T22:00:00Z",
    venue: "AO Arena", address: "Trinity Way", city: "Manchester", country: "UK",
    sections: [
      { name: "Floor", rows: 10, seatsPerRow: 20, priceGbp: 55.0 },
      { name: "Tier", rows: 12, seatsPerRow: 25, priceGbp: 35.0 },
    ],
  },
  {
    title: "Cirque du Soleil: Kurios", description: "Cabinet of Curiosities", category: "OTHER",
    startsAt: "2026-10-17T19:00:00Z", endsAt: "2026-10-17T21:30:00Z",
    venue: "Royal Albert Hall", address: "Kensington Gore", city: "London", country: "UK",
    sections: [
      { name: "Stalls", rows: 10, seatsPerRow: 20, priceGbp: 95.0 },
      { name: "Circle", rows: 8, seatsPerRow: 20, priceGbp: 65.0 },
    ],
  },
  {
    title: "Beyonce: Renaissance World Tour", description: "Live at Tottenham Hotspur Stadium", category: "CONCERT",
    startsAt: "2026-09-19T19:00:00Z", endsAt: "2026-09-19T22:30:00Z",
    venue: "Tottenham Hotspur Stadium", address: "782 High Road", city: "London", country: "UK",
    sections: [
      { name: "Floor", rows: 10, seatsPerRow: 25, priceGbp: 175.0 },
      { name: "Lower Tier", rows: 15, seatsPerRow: 35, priceGbp: 110.0 },
      { name: "Upper Tier", rows: 12, seatsPerRow: 40, priceGbp: 60.0 },
    ],
  },
  {
    title: "NBA London Game 2026", description: "Lakers vs Celtics", category: "SPORTS",
    startsAt: "2026-11-15T18:30:00Z", endsAt: "2026-11-15T21:00:00Z",
    venue: "The O2 Arena", address: "Peninsula Square", city: "London", country: "UK",
    sections: [
      { name: "Courtside", rows: 3, seatsPerRow: 10, priceGbp: 400.0 },
      { name: "Lower Bowl", rows: 12, seatsPerRow: 30, priceGbp: 140.0 },
      { name: "Upper Bowl", rows: 15, seatsPerRow: 35, priceGbp: 70.0 },
    ],
  },
  {
    title: "Dua Lipa: Radical Optimism Tour", description: "Live at First Direct Arena", category: "CONCERT",
    startsAt: "2026-08-27T19:30:00Z", endsAt: "2026-08-27T22:00:00Z",
    venue: "First Direct Arena", address: "Arena Way", city: "Leeds", country: "UK",
    sections: [
      { name: "Floor", rows: 8, seatsPerRow: 22, priceGbp: 95.0 },
      { name: "Tier", rows: 14, seatsPerRow: 35, priceGbp: 50.0 },
    ],
  },
];

async function main() {
  log("");
  log("=========================================");
  log("  Reseeding event-service demo catalog");
  log("=========================================");
  log("");

  log(">>> Truncating venues/events/sections/seats in ticketing_events");
  execFileSync(
    "docker",
    [
      "exec",
      POSTGRES_CONTAINER,
      "psql",
      "-U",
      "ticketing",
      "-d",
      "ticketing_events",
      "-c",
      "TRUNCATE TABLE seats, sections, events, venues RESTART IDENTITY CASCADE;",
    ],
    { stdio: "inherit" }
  );
  log("");

  for (const event of EVENTS) {
    await createEvent(event);
  }

  log("=========================================");
  log("  Done");
  log("=========================================");
  const finalRes = await call("GET", `${EVENT_BASE}/events?size=20`);
  log(`totalElements: ${finalRes.json?.totalElements}`);
  log("");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
