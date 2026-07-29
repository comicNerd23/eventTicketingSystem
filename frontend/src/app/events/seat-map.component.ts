import { AsyncPipe, DatePipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, forkJoin, map } from 'rxjs';

import { BookingApiService } from '../bookings/booking-api.service';
import { Event, Seat, SeatStatus } from './event.model';
import { EventsApiService } from './events-api.service';

export const CELL_SIZE = 22;

export interface SeatSection {
  name: string;
  seats: Seat[];
  viewBoxWidth: number;
  viewBoxHeight: number;
}

export interface SeatMapData {
  event: Event;
  sections: SeatSection[];
}

const SEAT_COLORS: Record<SeatStatus, string> = {
  AVAILABLE: '#4caf50',
  HELD: '#ffb300',
  BOOKED: '#9e9e9e'
};

function groupBySection(seats: Seat[]): SeatSection[] {
  const bySectionName = new Map<string, Seat[]>();
  for (const seat of seats) {
    const existing = bySectionName.get(seat.sectionName);
    if (existing) {
      existing.push(seat);
    } else {
      bySectionName.set(seat.sectionName, [seat]);
    }
  }

  return Array.from(bySectionName.entries()).map(([name, sectionSeats]) => ({
    name,
    seats: sectionSeats,
    viewBoxWidth: Math.max(...sectionSeats.map((s) => s.seatNumber)) * CELL_SIZE,
    viewBoxHeight: Math.max(...sectionSeats.map((s) => s.rowNumber)) * CELL_SIZE
  }));
}

@Component({
  selector: 'app-seat-map',
  standalone: true,
  imports: [AsyncPipe, DatePipe, RouterLink],
  templateUrl: './seat-map.component.html',
  styleUrl: './seat-map.component.css'
})
export class SeatMapComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly eventsApi = inject(EventsApiService);
  private readonly bookingApi = inject(BookingApiService);

  readonly cellSize = CELL_SIZE;

  private readonly eventId = this.route.snapshot.paramMap.get('id')!;

  data$: Observable<SeatMapData> = forkJoin({
    event: this.eventsApi.getEvent(this.eventId),
    seats: this.eventsApi.getSeatMap(this.eventId)
  }).pipe(map(({ event, seats }) => ({ event, sections: groupBySection(seats) })));

  holdingSeatId: string | null = null;
  holdError: string | null = null;

  seatColor(status: SeatStatus): string {
    return SEAT_COLORS[status];
  }

  seatClass(seat: Seat): string {
    const classes = ['seat', `seat-${seat.status.toLowerCase()}`];
    if (seat.id === this.holdingSeatId) {
      classes.push('seat-holding');
    }
    return classes.join(' ');
  }

  seatX(seat: Seat): number {
    return (seat.seatNumber - 1) * CELL_SIZE;
  }

  seatY(seat: Seat): number {
    return (seat.rowNumber - 1) * CELL_SIZE;
  }

  selectSeat(seat: Seat): void {
    if (seat.status !== 'AVAILABLE' || this.holdingSeatId) {
      return;
    }

    this.holdingSeatId = seat.id;
    this.holdError = null;

    this.bookingApi.holdSeat(this.eventId, seat.id).subscribe({
      next: (booking) => this.router.navigate(['/bookings', booking.id]),
      error: () => {
        this.holdError = 'That seat was just taken by someone else — please pick another.';
        this.holdingSeatId = null;
      }
    });
  }
}
