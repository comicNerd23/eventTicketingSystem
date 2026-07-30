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
