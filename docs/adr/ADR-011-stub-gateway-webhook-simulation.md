# ADR-011: Stub Payment Gateway Simulates Stripe's Own Webhook Delivery

**Status:** Accepted
**Date:** 2026-07-30
**Authors:** Engineering Team

---

## Context

Found by the user manually testing the frontend: clicking "Confirm & Pay" sends a booking to
`PAYMENT_PENDING` and it never resolves. Root-caused by reading the code and reproducing live
against the running stack (not just reading code) — held a real seat, confirmed it, and
watched the booking sit at `PAYMENT_PENDING` indefinitely:

- `POST /bookings/{id}/confirm` publishes `payment-initiated`; `payment-service` consumes it,
  calls `PaymentGateway.createCharge`, and persists a `PENDING` payment. Nothing else happens
  until something calls `POST /payments/webhook` — that is the only thing that moves a
  payment from `PENDING` to `SUCCEEDED` and publishes `payment-completed`, which is what lets
  `booking-service` finish the saga and mark the booking `CONFIRMED`.
- A full-repo search of the frontend for `"webhook"` returns zero matches. Nothing in
  `booking-api.service.ts` or anywhere else in the Angular app ever calls that endpoint.
- It gets worse than just stuck: `cancelBooking` only accepts `HELD`/`CONFIRMED` bookings, and
  the Redis hold-expiry listener only watches `HELD` bookings — so a `PAYMENT_PENDING` booking
  has no recovery path at all once stuck.

**Why this only surfaced now:** until the `payment-simulator` retirement slice, that service
auto-completed the saga ~600ms after `payment-initiated` without any webhook involved at all,
so nobody noticed the webhook was never wired into the frontend. Retiring it made
`payment-service`'s real two-phase, webhook-driven flow (matching how Stripe actually works)
the only path — and that phase genuinely has no caller anywhere except `demo.sh`'s own
explicit, manual webhook step.

### Option A: Frontend calls the webhook itself
Have the Angular app poll `payment-service` for the `PENDING` payment's intent id, then
`POST /payments/webhook` directly.

**Strengths:**
- Would work with no backend changes.

**Weaknesses:**
- Architecturally backwards: a webhook models an external party (Stripe) calling *your*
  backend. Having the browser call its own backend's webhook endpoint isn't something a real
  Stripe integration would ever do.
- Leaks payment-service internals (payment intent ids, the existence of a webhook endpoint)
  into the frontend, which today correctly never talks to payment-service at all — booking
  and payment are separate bounded contexts (ADR-001) and the frontend only knows
  booking-service.

### Option B: Stub gateway simulates Stripe's webhook delivery itself
`StubPaymentGateway` already stands in for "what Stripe would do" when creating a charge. Have
it also stand in for "Stripe calling back a moment later": after `createCharge` returns the
`PENDING` PaymentIntent, schedule a short delayed **real HTTP call to this service's own
`/payments/webhook` endpoint** — the same endpoint a real Stripe webhook would hit, with the
same signature-presence check enforced, so the webhook mechanism itself stays completely real
and testable.

**Strengths:**
- No frontend changes at all; the booking now finishes on its own, the same way it would if a
  real payment provider were wired in behind `PaymentGateway`.
- The real webhook controller, validation, and idempotency guard get exercised on every single
  confirmation, not just in `demo.sh`'s manual step.
- Confined entirely to the stub — nothing about `PaymentService`, `PaymentController`, or the
  `PaymentGateway` interface changes, so a real Stripe implementation later drops in behind the
  same interface with zero ripple.

**Weaknesses:**
- `payment-service` makes an outbound HTTP call to itself purely to simulate an external actor
  — a deliberate stand-in, not something that belongs anywhere near a real integration.

---

## Decision

We adopt **Option B** — the stub payment gateway self-delivers a simulated Stripe webhook call.

---

## Rationale

The webhook endpoint, its Stripe-Signature check, and its idempotency guard
(`PaymentService.handleWebhook` already no-ops on a non-`PENDING` payment) are the real
contract this service will eventually receive from Stripe. Simulating delivery from inside the
stub gateway exercises that exact contract on every confirmation, keeps the frontend and
booking-service completely unaware that anything changed, and keeps the "how does a charge get
confirmed" concern entirely inside the one component (`PaymentGateway`) whose entire purpose is
already to abstract that away.

Implementation: the delayed self-call is `@Async`, sleeping ~1 second (long enough to see the
frontend's "Processing payment…" state, short enough not to feel broken — the same rough shape
as `payment-simulator`'s retired auto-completion delay) before `POST`ing
`{"type": "payment_intent.succeeded", "paymentIntentId": ...}` with header
`Stripe-Signature: t=stub,v1=stub_signature`. The `@Async` method lives on a separate bean
(`StripeWebhookSimulator`), not on `StubPaymentGateway` itself — Spring's `@Async` proxy cannot
intercept a self-invoked call within the same bean, so calling it from `createCharge` on `this`
would silently execute synchronously and block the Kafka consumer thread that triggered it.

---

## Consequences

### Positive
- The real bug is fixed: confirming a booking now genuinely reaches `CONFIRMED` with a ticket
  reference, with zero manual or frontend-driven webhook calls.
- The webhook endpoint's real validation and idempotency behavior is exercised continuously,
  not just by `demo.sh`.
- `demo.sh`'s own manual webhook step (Step 4) now doubles as a live demonstration of
  idempotent handling of Stripe's real-world at-least-once redelivery guarantee, since it
  typically arrives after the stub's own simulated delivery has already succeeded the payment.

### Negative / Trade-offs
- `payment-service` now depends on `spring-boot-restclient` in main scope (previously
  test-only) purely to call itself — a footprint that only exists because of the stub.
- This entire mechanism (`StripeWebhookSimulator`, the self-HTTP-call, the artificial delay) is
  scaffolding that gets deleted, not adapted, the day a real `PaymentGateway` implementation
  (backed by the actual Stripe SDK and Stripe's own webhook delivery) replaces the stub —
  noted here explicitly so it doesn't read as production design later.

---

---

# ADR-011: Das Stub-Payment-Gateway simuliert Stripes eigene Webhook-Zustellung

**Status:** Akzeptiert  
**Datum:** 2026-07-30  
**Autoren:** Engineering-Team

---

## Kontext

Beim manuellen Testen des Frontends durch den Nutzer entdeckt: Ein Klick auf „Confirm & Pay“
schickt eine Buchung in `PAYMENT_PENDING`, und sie wird nie aufgelöst. Die Ursache wurde durch
Lesen des Codes und Reproduzieren live gegen den laufenden Stack ermittelt (nicht nur durch
Code-Lektüre) – ein echter Sitz wurde reserviert, bestätigt, und die Buchung blieb
unbegrenzt in `PAYMENT_PENDING`:

- `POST /bookings/{id}/confirm` veröffentlicht `payment-initiated`; `payment-service`
  konsumiert es, ruft `PaymentGateway.createCharge` auf und persistiert eine `PENDING`-Zahlung.
  Danach passiert nichts, bis jemand `POST /payments/webhook` aufruft – das ist das Einzige, das
  eine Zahlung von `PENDING` nach `SUCCEEDED` bringt und `payment-completed` veröffentlicht, womit
  `booking-service` die Saga abschließen und die Buchung auf `CONFIRMED` setzen kann.
- Eine Suche im gesamten Frontend nach `"webhook"` liefert null Treffer. Nichts in
  `booking-api.service.ts` oder irgendwo sonst in der Angular-App ruft diesen Endpunkt auf.
- Es ist schlimmer als nur „hängen geblieben“: `cancelBooking` akzeptiert nur
  `HELD`/`CONFIRMED`-Buchungen, und der Redis-Listener für abgelaufene Reservierungen beobachtet
  nur `HELD`-Buchungen – eine `PAYMENT_PENDING`-Buchung hat also, einmal hängen geblieben,
  keinerlei Wiederherstellungspfad.

**Warum das erst jetzt auffiel:** Bis zum Slice zur Stilllegung des `payment-simulator`
schloss dieser Service die Saga etwa 600 ms nach `payment-initiated` automatisch ab, ganz ohne
Webhook, sodass niemand bemerkte, dass der Webhook nie ans Frontend angebunden war. Mit seiner
Stilllegung wurde der echte zweiphasige, webhook-gesteuerte Ablauf des `payment-service` (wie
Stripe tatsächlich funktioniert) der einzige Pfad – und diese Phase hat tatsächlich nirgends
einen Aufrufer außer dem expliziten, manuellen Webhook-Schritt in `demo.sh`.

### Option A: Das Frontend ruft den Webhook selbst auf
Die Angular-App fragt `payment-service` per Polling nach der Intent-ID der `PENDING`-Zahlung ab
und ruft dann direkt `POST /payments/webhook` auf.

**Stärken:**
- Würde ohne Backend-Änderungen funktionieren.

**Schwächen:**
- Architektonisch verkehrt herum: Ein Webhook bildet eine externe Partei (Stripe) ab, die
  *dein* Backend aufruft. Dass der Browser den Webhook-Endpunkt seines eigenen Backends
  aufruft, würde eine echte Stripe-Integration nie tun.
- Gibt Interna des payment-service (Payment-Intent-IDs, die Existenz eines Webhook-Endpunkts)
  ans Frontend preis, das heute korrekterweise gar nicht mit dem payment-service spricht –
  Buchung und Zahlung sind getrennte Bounded Contexts (ADR-001), und das Frontend kennt nur den
  booking-service.

### Option B: Das Stub-Gateway simuliert die Webhook-Zustellung von Stripe selbst
`StubPaymentGateway` steht beim Erzeugen einer Zahlung bereits für „was Stripe tun würde“. Es
soll auch für „Stripe ruft einen Moment später zurück“ stehen: Nachdem `createCharge` den
`PENDING`-PaymentIntent zurückgegeben hat, wird ein kurz verzögerter **echter HTTP-Aufruf an den
`/payments/webhook`-Endpunkt dieses eigenen Services** eingeplant – denselben Endpunkt, den ein
echter Stripe-Webhook träfe, mit derselben erzwungenen Prüfung auf das Vorhandensein einer
Signatur, sodass der Webhook-Mechanismus selbst vollständig echt und testbar bleibt.

**Stärken:**
- Keinerlei Frontend-Änderungen; die Buchung schließt nun von selbst ab, genauso wie wenn ein
  echter Zahlungsanbieter hinter `PaymentGateway` angebunden wäre.
- Der echte Webhook-Controller, die Validierung und der Idempotenz-Schutz werden bei jeder
  einzelnen Bestätigung ausgeübt, nicht nur im manuellen Schritt von `demo.sh`.
- Vollständig auf den Stub beschränkt – an `PaymentService`, `PaymentController` oder dem
  `PaymentGateway`-Interface ändert sich nichts, sodass sich später eine echte
  Stripe-Implementierung hinter demselben Interface ohne Folgeänderungen einfügt.

**Schwächen:**
- `payment-service` macht einen ausgehenden HTTP-Aufruf an sich selbst, nur um einen externen
  Akteur zu simulieren – ein bewusster Platzhalter, der nirgends in die Nähe einer echten
  Integration gehört.

---

## Entscheidung

Wir führen **Option B** ein – das Stub-Payment-Gateway stellt einen simulierten Stripe-Webhook-
Aufruf selbst zu.

---

## Begründung

Der Webhook-Endpunkt, seine Stripe-Signature-Prüfung und sein Idempotenz-Schutz
(`PaymentService.handleWebhook` tut bei einer nicht-`PENDING`-Zahlung bereits nichts) sind der
echte Vertrag, den dieser Service irgendwann von Stripe erhalten wird. Die Zustellung aus dem
Stub-Gateway heraus zu simulieren, übt genau diesen Vertrag bei jeder Bestätigung aus, lässt
Frontend und booking-service völlig im Unklaren darüber, dass sich etwas geändert hat, und hält
die Frage „wie wird eine Zahlung bestätigt“ vollständig in der einen Komponente
(`PaymentGateway`), deren ganzer Zweck es bereits ist, das wegzuabstrahieren.

Umsetzung: Der verzögerte Selbstaufruf ist `@Async` und schläft etwa 1 Sekunde (lang genug, um
den Zustand „Processing payment…“ im Frontend zu sehen, kurz genug, um sich nicht kaputt
anzufühlen – ungefähr dieselbe Form wie die Verzögerung der stillgelegten automatischen
Fertigstellung des `payment-simulator`), bevor es
`{"type": "payment_intent.succeeded", "paymentIntentId": ...}` mit dem Header
`Stripe-Signature: t=stub,v1=stub_signature` per `POST` sendet. Die `@Async`-Methode liegt in
einer separaten Bean (`StripeWebhookSimulator`), nicht in `StubPaymentGateway` selbst – Springs
`@Async`-Proxy kann einen Selbstaufruf innerhalb derselben Bean nicht abfangen; ein Aufruf aus
`createCharge` über `this` würde stillschweigend synchron laufen und den Kafka-Consumer-Thread
blockieren, der ihn ausgelöst hat.

---

## Konsequenzen

### Positiv
- Der echte Fehler ist behoben: Das Bestätigen einer Buchung erreicht nun tatsächlich
  `CONFIRMED` mit einer Ticket-Referenz, ohne manuelle oder frontendgesteuerte Webhook-Aufrufe.
- Das echte Validierungs- und Idempotenzverhalten des Webhook-Endpunkts wird laufend geübt,
  nicht nur durch `demo.sh`.
- Der manuelle Webhook-Schritt von `demo.sh` (Schritt 4) dient nun zugleich als Live-Demonstration
  der idempotenten Behandlung von Stripes realer At-least-once-Wiederzustellung, da er
  typischerweise eintrifft, nachdem die simulierte Zustellung des Stubs die Zahlung bereits
  erfolgreich abgeschlossen hat.

### Negativ / Abwägungen
- `payment-service` hängt nun im Main-Scope von `spring-boot-restclient` ab (zuvor nur im
  Test-Scope), allein um sich selbst aufzurufen – ein Fußabdruck, der nur wegen des Stubs
  existiert.
- Dieser gesamte Mechanismus (`StripeWebhookSimulator`, der HTTP-Selbstaufruf, die künstliche
  Verzögerung) ist Gerüst, das gelöscht und nicht angepasst wird, sobald eine echte
  `PaymentGateway`-Implementierung (mit dem echten Stripe-SDK und Stripes eigener
  Webhook-Zustellung) den Stub ersetzt – hier ausdrücklich vermerkt, damit es später nicht wie
  Produktionsdesign wirkt.
