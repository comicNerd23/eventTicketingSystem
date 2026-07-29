import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { interval } from 'rxjs';

import { Booking } from './booking.model';
import { BookingApiService } from './booking-api.service';

const DEMO_STRIPE_PAYMENT_METHOD_ID = 'pm_demo_4242424242424242';

function formatCountdown(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
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

  readonly countdown = computed(() => formatCountdown(this.remainingSeconds()));

  constructor() {
    this.loadBooking();

    interval(1000)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.tick());
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
      this.updateRemaining(booking);
    } else if (booking.status === 'PAYMENT_PENDING') {
      this.loadBooking();
    }
  }

  private updateRemaining(booking: Booking): void {
    if (!booking.holdExpiresAt) {
      this.remainingSeconds.set(0);
      return;
    }
    const secondsLeft = Math.max(0, Math.round((new Date(booking.holdExpiresAt).getTime() - Date.now()) / 1000));
    this.remainingSeconds.set(secondsLeft);
  }
}
