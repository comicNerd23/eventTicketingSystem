import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../environments/environment';
import { Booking } from './booking.model';
import { BookingApiService } from './booking-api.service';

describe('BookingApiService', () => {
  let service: BookingApiService;
  let httpMock: HttpTestingController;

  const mockBooking: Booking = {
    id: '11111111-1111-1111-1111-111111111111',
    eventId: '22222222-2222-2222-2222-222222222222',
    eventTitle: 'Coldplay: Music of the Spheres Tour',
    seatId: '33333333-3333-3333-3333-333333333333',
    seatLabel: 'Floor-R1-S1',
    userId: '44444444-4444-4444-4444-444444444444',
    status: 'HELD',
    totalAmountGbp: 89.5,
    holdExpiresAt: '2026-07-29T19:00:00Z',
    confirmedAt: null,
    cancelledAt: null,
    expiredAt: null,
    ticketReference: null,
    createdAt: '2026-07-29T18:50:00Z'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(BookingApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('holdSeat posts eventId/seatId and returns the created booking', () => {
    service.holdSeat(mockBooking.eventId, mockBooking.seatId).subscribe((booking) => {
      expect(booking).toEqual(mockBooking);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/bookings/hold`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ eventId: mockBooking.eventId, seatId: mockBooking.seatId });
    req.flush(mockBooking);
  });

  it('getBooking fetches a booking by id', () => {
    service.getBooking(mockBooking.id).subscribe((booking) => {
      expect(booking).toEqual(mockBooking);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/bookings/${mockBooking.id}`);
    expect(req.request.method).toBe('GET');
    req.flush(mockBooking);
  });

  it('confirmBooking posts the stripe payment method id', () => {
    const confirmed: Booking = { ...mockBooking, status: 'PAYMENT_PENDING' };

    service.confirmBooking(mockBooking.id, 'pm_demo_4242424242424242').subscribe((booking) => {
      expect(booking).toEqual(confirmed);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/bookings/${mockBooking.id}/confirm`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ stripePaymentMethodId: 'pm_demo_4242424242424242' });
    req.flush(confirmed);
  });

  it('cancelBooking posts to the cancel endpoint', () => {
    const cancelled: Booking = { ...mockBooking, status: 'CANCELLED', cancelledAt: '2026-07-29T18:55:00Z' };

    service.cancelBooking(mockBooking.id).subscribe((booking) => {
      expect(booking).toEqual(cancelled);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/bookings/${mockBooking.id}/cancel`);
    expect(req.request.method).toBe('POST');
    req.flush(cancelled);
  });
});
