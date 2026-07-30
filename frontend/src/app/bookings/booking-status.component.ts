import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { interval } from 'rxjs';

import { Booking } from './booking.model';
import { BookingApiService } from './booking-api.service';

const DEMO_STRIPE_PAYMENT_METHOD_ID = 'pm_demo_4242424242424242';

type Urgency = 'normal' | 'warning' | 'critical';

function formatCountdown(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

function urgencyFor(remainingSeconds: number): Urgency {
  if (remainingSeconds < 30) {
    return 'critical';
  }
  if (remainingSeconds < 120) {
    return 'warning';
  }
  return 'normal';
}

@Component({
  selector: 'app-booking-status',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './booking-status.component.html',
  styleUrl: './booking-status.component.css'
})
export class BookingStatusComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly bookingApi = inject(BookingApiService);

  private readonly bookingId = this.route.snapshot.paramMap.get('id')!;

  readonly booking = signal<Booking | null>(null);
  readonly error = signal<string | null>(null);
  readonly confirming = signal(false);
  readonly cancelling = signal(false);
  readonly remainingSeconds = signal(0);
  readonly totalHoldSeconds = signal(0);

  readonly countdown = computed(() => formatCountdown(this.remainingSeconds()));
  readonly urgency = computed(() => urgencyFor(this.remainingSeconds()));
  readonly progressPercent = computed(() => {
    const total = this.totalHoldSeconds();
    if (total <= 0) {
      return 0;
    }
    return Math.max(0, Math.min(100, (this.remainingSeconds() / total) * 100));
  });

  constructor() {
    this.loadBooking();

    interval(1000)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.tick());
  }

  countdownClasses(): string {
    switch (this.urgency()) {
      case 'critical':
        return 'status held mt-4 inline-block rounded-md bg-red-100 px-3 py-2 font-semibold text-red-800 animate-pulse';
      case 'warning':
        return 'status held mt-4 inline-block rounded-md bg-orange-100 px-3 py-2 font-semibold text-orange-800';
      default:
        return 'status held mt-4 inline-block rounded-md bg-amber-100 px-3 py-2 font-semibold text-amber-800';
    }
  }

  progressBarClasses(): string {
    switch (this.urgency()) {
      case 'critical':
        return 'h-full rounded-full bg-red-500 transition-all';
      case 'warning':
        return 'h-full rounded-full bg-orange-500 transition-all';
      default:
        return 'h-full rounded-full bg-amber-500 transition-all';
    }
  }

  confirm(): void {
    this.confirming.set(true);
    this.error.set(null);

    this.bookingApi.confirmBooking(this.bookingId, DEMO_STRIPE_PAYMENT_METHOD_ID).subscribe({
      next: (booking) => {
        this.booking.set(booking);
        this.confirming.set(false);
      },
      error: () => {
        this.error.set('Could not start payment — the hold may have expired. Try holding the seat again.');
        this.confirming.set(false);
      }
    });
  }

  cancel(): void {
    this.cancelling.set(true);
    this.error.set(null);

    this.bookingApi.cancelBooking(this.bookingId).subscribe({
      next: (booking) => {
        this.booking.set(booking);
        this.cancelling.set(false);
      },
      error: () => {
        this.error.set('Could not release the seat.');
        this.cancelling.set(false);
      }
    });
  }

  private loadBooking(): void {
    this.bookingApi.getBooking(this.bookingId).subscribe({
      next: (booking) => {
        this.booking.set(booking);
        this.updateRemaining(booking);
      },
      error: () => this.error.set('Booking not found.')
    });
  }

  private tick(): void {
    const booking = this.booking();
    if (!booking) {
      return;
    }
    if (booking.status === 'HELD') {
      const secondsLeft = this.updateRemaining(booking);
      if (secondsLeft <= 0) {
        // The countdown reached zero — the hold has genuinely expired server-side
        // (Redis TTL), so re-fetch to pick up the real EXPIRED status instead of
        // sitting on a stale "0:00" HELD view. Self-terminating: once the re-fetch
        // returns EXPIRED, this branch no longer runs on the next tick.
        this.loadBooking();
      }
    } else if (booking.status === 'PAYMENT_PENDING') {
      this.loadBooking();
    }
  }

  private updateRemaining(booking: Booking): number {
    if (!booking.holdExpiresAt) {
      this.remainingSeconds.set(0);
      this.totalHoldSeconds.set(0);
      return 0;
    }
    const holdExpiresAt = new Date(booking.holdExpiresAt).getTime();
    const secondsLeft = Math.max(0, Math.round((holdExpiresAt - Date.now()) / 1000));
    this.remainingSeconds.set(secondsLeft);
    this.totalHoldSeconds.set(Math.max(1, Math.round((holdExpiresAt - new Date(booking.createdAt).getTime()) / 1000)));
    return secondsLeft;
  }
}
