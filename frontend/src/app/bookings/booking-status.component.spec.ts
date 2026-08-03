import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { Booking } from './booking.model';
import { BookingApiService } from './booking-api.service';
import { BookingStatusComponent } from './booking-status.component';

describe('BookingStatusComponent', () => {
  const heldBooking: Booking = {
    id: '11111111-1111-1111-1111-111111111111',
    eventId: '22222222-2222-2222-2222-222222222222',
    eventTitle: 'Coldplay: Music of the Spheres Tour',
    seatId: '33333333-3333-3333-3333-333333333333',
    seatLabel: 'Floor-R1-S1',
    userId: '44444444-4444-4444-4444-444444444444',
    status: 'HELD',
    totalAmountGbp: 89.5,
    holdExpiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
    confirmedAt: null,
    cancelledAt: null,
    expiredAt: null,
    ticketReference: null,
    createdAt: new Date().toISOString()
  };

  function setup(bookingApiStub: Partial<BookingApiService>) {
    TestBed.configureTestingModule({
      imports: [BookingStatusComponent],
      providers: [
        provideRouter([]),
        { provide: BookingApiService, useValue: bookingApiStub },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: heldBooking.id }) } }
        }
      ]
    });
    const fixture = TestBed.createComponent(BookingStatusComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders the HELD state with a countdown and both actions', () => {
    const fixture = setup({ getBooking: () => of(heldBooking) });
    const text = (fixture.nativeElement as HTMLElement).textContent!;

    expect(text).toContain(heldBooking.eventTitle);
    expect(text).toContain('Floor-R1-S1');
    expect(text).toContain('Held');
    expect(text).toContain('Confirm & Pay');
    expect(text).toContain('Release seat');
  });

  it('confirm() calls the API and re-renders with the returned status', () => {
    const pending: Booking = { ...heldBooking, status: 'PAYMENT_PENDING' };
    const confirmBooking = jasmine.createSpy().and.returnValue(of(pending));
    const fixture = setup({ getBooking: () => of(heldBooking), confirmBooking });

    const [confirmButton]: HTMLButtonElement[] = fixture.nativeElement.querySelectorAll('button');
    confirmButton.click();
    fixture.detectChanges();

    expect(confirmBooking).toHaveBeenCalledWith(heldBooking.id, 'pm_demo_4242424242424242');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Processing payment');
  });

  it('renders a CONFIRMED booking with its ticket reference', () => {
    const confirmedBooking: Booking = {
      ...heldBooking,
      status: 'CONFIRMED',
      confirmedAt: new Date().toISOString(),
      ticketReference: 'TCK-DEMO-1234'
    };
    const fixture = setup({ getBooking: () => of(confirmedBooking) });

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('TCK-DEMO-1234');
  });

  it('surfaces an inline error if confirm() fails, without throwing', () => {
    const confirmBooking = jasmine.createSpy().and.returnValue(throwError(() => new Error('conflict')));
    const fixture = setup({ getBooking: () => of(heldBooking), confirmBooking });

    const [confirmButton]: HTMLButtonElement[] = fixture.nativeElement.querySelectorAll('button');
    expect(() => {
      confirmButton.click();
      fixture.detectChanges();
    }).not.toThrow();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Could not start payment');
  });

  it('renders the critical (pulsing, red) urgency tier when under 30 seconds remain', () => {
    const nearlyExpired: Booking = {
      ...heldBooking,
      holdExpiresAt: new Date(Date.now() + 15 * 1000).toISOString()
    };
    const fixture = setup({ getBooking: () => of(nearlyExpired) });

    const statusEl = fixture.nativeElement.querySelector('.status.held') as HTMLElement;
    expect(statusEl.className).toContain('bg-n-danger');
    expect(statusEl.className).toContain('animate-pulse');
  });

  it('re-fetches the booking once the countdown reaches zero, picking up EXPIRED', fakeAsync(() => {
    const expiringNow: Booking = {
      ...heldBooking,
      holdExpiresAt: new Date(Date.now() - 1000).toISOString()
    };
    const expiredBooking: Booking = { ...heldBooking, status: 'EXPIRED', expiredAt: new Date().toISOString() };
    const getBooking = jasmine.createSpy().and.returnValues(of(expiringNow), of(expiredBooking));

    const fixture = setup({ getBooking });
    expect(getBooking).toHaveBeenCalledTimes(1);

    tick(1000);
    fixture.detectChanges();

    expect(getBooking).toHaveBeenCalledTimes(2);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Your hold expired');

    // Destroy while still inside the fakeAsync zone so the component's interval(1000)
    // subscription (cleaned up via takeUntilDestroyed) doesn't trip fakeAsync's
    // pending-periodic-timer check on teardown.
    fixture.destroy();
  }));
});
