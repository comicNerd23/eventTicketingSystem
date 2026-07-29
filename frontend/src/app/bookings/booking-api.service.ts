import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { Booking } from './booking.model';

@Injectable({ providedIn: 'root' })
export class BookingApiService {
  private readonly http = inject(HttpClient);

  holdSeat(eventId: string, seatId: string): Observable<Booking> {
    return this.http.post<Booking>(`${environment.apiBaseUrl}/bookings/hold`, { eventId, seatId });
  }

  getBooking(bookingId: string): Observable<Booking> {
    return this.http.get<Booking>(`${environment.apiBaseUrl}/bookings/${bookingId}`);
  }

  confirmBooking(bookingId: string, stripePaymentMethodId: string): Observable<Booking> {
    return this.http.post<Booking>(`${environment.apiBaseUrl}/bookings/${bookingId}/confirm`, {
      stripePaymentMethodId
    });
  }

  cancelBooking(bookingId: string): Observable<Booking> {
    return this.http.post<Booking>(`${environment.apiBaseUrl}/bookings/${bookingId}/cancel`, {});
  }
}
