import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import type { Booking } from '../bookings/booking.model';
import { BookingApiService } from '../bookings/booking-api.service';
import type { Event, Seat } from './event.model';
import { EventsApiService } from './events-api.service';
import { SeatMapComponent } from './seat-map.component';

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];

  onmessage: ((event: MessageEvent) => void) | null = null;
  readonly url: string;

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  close(): void {}
}

describe('SeatMapComponent', () => {
  const mockEvent: Event = {
    id: '11111111-1111-1111-1111-111111111111',
    title: 'Coldplay: Music of the Spheres Tour',
    description: null,
    category: 'CONCERT',
    venueId: '22222222-2222-2222-2222-222222222222',
    venueName: 'The O2 Arena',
    city: 'London',
    startsAt: '2026-09-15T19:30:00Z',
    endsAt: '2026-09-15T22:30:00Z',
    status: 'PUBLISHED',
    organizerId: '33333333-3333-3333-3333-333333333333',
    totalSeats: 650,
    availableSeats: 648,
    imageUrl: null,
    createdAt: '2026-07-29T00:00:00Z'
  };

  const mockSeats: Seat[] = [
    {
      id: '44444444-4444-4444-4444-444444444444',
      sectionId: '55555555-5555-5555-5555-555555555555',
      sectionName: 'Floor',
      rowNumber: 1,
      seatNumber: 1,
      label: 'Floor-A1',
      priceGbp: 120,
      status: 'AVAILABLE'
    },
    {
      id: '66666666-6666-6666-6666-666666666666',
      sectionId: '55555555-5555-5555-5555-555555555555',
      sectionName: 'Floor',
      rowNumber: 1,
      seatNumber: 2,
      label: 'Floor-A2',
      priceGbp: 120,
      status: 'HELD'
    },
    {
      id: '77777777-7777-7777-7777-777777777777',
      sectionId: '88888888-8888-8888-8888-888888888888',
      sectionName: 'Upper Tier',
      rowNumber: 1,
      seatNumber: 1,
      label: 'Upper-A1',
      priceGbp: 60,
      status: 'BOOKED'
    }
  ];

  const eventsApiStub = {
    getEvent: () => of(mockEvent),
    getSeatMap: () => of(mockSeats)
  };

  let originalWebSocket: typeof WebSocket;

  beforeEach(() => {
    originalWebSocket = window.WebSocket;
    FakeWebSocket.instances = [];
    (window as unknown as { WebSocket: unknown }).WebSocket = FakeWebSocket;
  });

  afterEach(() => {
    (window as unknown as { WebSocket: unknown }).WebSocket = originalWebSocket;
  });

  async function setup(
    bookingApiStub: Partial<BookingApiService>,
    eventsApiOverride: Partial<EventsApiService> = eventsApiStub
  ) {
    await TestBed.configureTestingModule({
      imports: [SeatMapComponent],
      providers: [
        provideRouter([]),
        { provide: EventsApiService, useValue: eventsApiOverride },
        { provide: BookingApiService, useValue: bookingApiStub },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: mockEvent.id }) } }
        }
      ]
    }).compileComponents();
    return TestBed.createComponent(SeatMapComponent);
  }

  function findRectByLabel(fixture: ReturnType<typeof TestBed.createComponent>, label: string): SVGRectElement {
    const rects = Array.from(fixture.nativeElement.querySelectorAll('rect')) as SVGRectElement[];
    const match = rects.find((r) => r.querySelector('title')?.textContent?.startsWith(label));
    if (!match) {
      throw new Error(`No rect found for label ${label}`);
    }
    return match;
  }

  it('should create', async () => {
    const fixture = await setup({});
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the event header, legend, and one rect per seat grouped by section', async () => {
    const fixture = await setup({});
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain(mockEvent.title);
    expect(compiled.textContent).toContain('Available');
    expect(compiled.textContent).toContain('Held');
    expect(compiled.textContent).toContain('Booked');
    expect(compiled.textContent).toContain('Floor');
    expect(compiled.textContent).toContain('Upper Tier');

    const rects = compiled.querySelectorAll('rect');
    expect(rects.length).toBe(mockSeats.length);

    const heldRect = Array.from(rects).find((r) => r.getAttribute('class')?.includes('seat-held'));
    expect(heldRect?.getAttribute('fill')).toBe('#f2b544');

    const bookedRect = Array.from(rects).find((r) => r.getAttribute('class')?.includes('seat-booked'));
    expect(bookedRect?.getAttribute('fill')).toBe('#5c5468');
  });

  it('renders each section heading with its per-seat price', async () => {
    const fixture = await setup({});
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const headings = Array.from(compiled.querySelectorAll('h2')).map((h) => h.textContent);
    expect(headings.some((text) => text?.includes('Floor') && text?.includes('£120'))).toBeTrue();
    expect(headings.some((text) => text?.includes('Upper Tier') && text?.includes('£60'))).toBeTrue();
  });

  it('wraps each section grid in a horizontally-scrollable container so the page never overflows', async () => {
    const fixture = await setup({});
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const scrollContainers = compiled.querySelectorAll('.overflow-x-auto');
    expect(scrollContainers.length).toBe(2); // one per section
    scrollContainers.forEach((container) => {
      expect(container.querySelector('svg')).toBeTruthy();
    });
  });

  it('clicking an AVAILABLE seat holds it and navigates to the booking page', async () => {
    const mockBooking: Booking = {
      id: '99999999-9999-9999-9999-999999999999',
      eventId: mockEvent.id,
      eventTitle: mockEvent.title,
      seatId: mockSeats[0].id,
      seatLabel: mockSeats[0].label,
      userId: '00000000-0000-0000-0000-000000000099',
      status: 'HELD',
      totalAmountGbp: mockSeats[0].priceGbp,
      holdExpiresAt: new Date(Date.now() + 600_000).toISOString(),
      confirmedAt: null,
      cancelledAt: null,
      expiredAt: null,
      ticketReference: null,
      createdAt: new Date().toISOString()
    };
    const holdSeat = jasmine.createSpy().and.returnValue(of(mockBooking));

    const fixture = await setup({ holdSeat });
    fixture.detectChanges();

    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    const availableRect = Array.from(fixture.nativeElement.querySelectorAll('rect')).find((r) =>
      (r as SVGRectElement).getAttribute('class')?.includes('seat-available')
    ) as SVGRectElement;
    availableRect.dispatchEvent(new Event('click'));
    fixture.detectChanges();

    expect(holdSeat).toHaveBeenCalledWith(mockEvent.id, mockSeats[0].id);
    expect(navigateSpy).toHaveBeenCalledWith(['/bookings', mockBooking.id]);
  });

  it('shows an inline error and stays on the page if the seat was already taken', async () => {
    const holdSeat = jasmine.createSpy().and.returnValue(throwError(() => new Error('conflict')));

    const fixture = await setup({ holdSeat });
    fixture.detectChanges();

    const availableRect = Array.from(fixture.nativeElement.querySelectorAll('rect')).find((r) =>
      (r as SVGRectElement).getAttribute('class')?.includes('seat-available')
    ) as SVGRectElement;
    availableRect.dispatchEvent(new Event('click'));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('just taken by someone else');
  });

  it('opens a WebSocket to the event seat channel and patches a seat live when a message arrives', async () => {
    const fixture = await setup({});
    fixture.detectChanges();

    expect(FakeWebSocket.instances.length).toBe(1);
    const ws = FakeWebSocket.instances[0];
    expect(ws.url).toContain(`/bookings/ws/events/${mockEvent.id}/seats`);

    // Floor-A1 starts AVAILABLE — simulate the server pushing that it's now HELD.
    ws.onmessage!({ data: JSON.stringify({ seatId: mockSeats[0].id, status: 'HELD' }) } as MessageEvent);
    fixture.detectChanges();

    const patchedRect = findRectByLabel(fixture, 'Floor-A1');
    expect(patchedRect.getAttribute('class')).toContain('seat-held');
    expect(patchedRect.getAttribute('fill')).toBe('#f2b544');

    // The seat that was already HELD in the initial data is untouched by the push.
    const untouchedRect = findRectByLabel(fixture, 'Floor-A2');
    expect(untouchedRect.getAttribute('class')).toContain('seat-held');
  });

  it('shows an error message instead of an infinite loading state when the initial load fails', async () => {
    const failingEventsApi: Partial<EventsApiService> = {
      getEvent: () => throwError(() => new Error('network down')),
      getSeatMap: () => of(mockSeats)
    };
    const fixture = await setup({}, failingEventsApi);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Could not load the seat map');
    expect(compiled.textContent).not.toContain('Loading seat map');
  });
});
