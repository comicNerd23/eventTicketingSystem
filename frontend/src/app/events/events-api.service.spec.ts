import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../environments/environment';
import { Event, EventPage, Seat } from './event.model';
import { EventsApiService } from './events-api.service';

describe('EventsApiService', () => {
  let service: EventsApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(EventsApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('listEvents defaults to page 0 with size 10', () => {
    const mockPage: EventPage = {
      content: [
        {
          id: '11111111-1111-1111-1111-111111111111',
          title: 'Test Event',
          description: null,
          category: 'CONCERT',
          venueId: '22222222-2222-2222-2222-222222222222',
          venueName: 'Test Venue',
          city: 'London',
          startsAt: '2026-09-15T19:30:00Z',
          endsAt: '2026-09-15T22:30:00Z',
          status: 'PUBLISHED',
          organizerId: '33333333-3333-3333-3333-333333333333',
          totalSeats: 650,
          availableSeats: 650,
          imageUrl: null,
          createdAt: '2026-07-29T00:00:00Z'
        }
      ],
      totalElements: 1,
      totalPages: 1,
      page: 0,
      size: 9
    };

    service.listEvents().subscribe((page) => {
      expect(page).toEqual(mockPage);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/events?page=0&size=9`);
    expect(req.request.method).toBe('GET');
    req.flush(mockPage);
  });

  it('listEvents requests the given page', () => {
    service.listEvents(2).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/events?page=2&size=9`);
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 3, page: 2, size: 9 });
  });

  it('getEvent fetches a single event by id', () => {
    const mockEvent: Event = {
      id: '11111111-1111-1111-1111-111111111111',
      title: 'Test Event',
      description: null,
      category: 'CONCERT',
      venueId: '22222222-2222-2222-2222-222222222222',
      venueName: 'Test Venue',
      city: 'London',
      startsAt: '2026-09-15T19:30:00Z',
      endsAt: '2026-09-15T22:30:00Z',
      status: 'PUBLISHED',
      organizerId: '33333333-3333-3333-3333-333333333333',
      totalSeats: 650,
      availableSeats: 650,
      imageUrl: null,
      createdAt: '2026-07-29T00:00:00Z'
    };

    service.getEvent(mockEvent.id).subscribe((event) => {
      expect(event).toEqual(mockEvent);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/events/${mockEvent.id}`);
    expect(req.request.method).toBe('GET');
    req.flush(mockEvent);
  });

  it('getSeatMap fetches the seat array for an event', () => {
    const eventId = '11111111-1111-1111-1111-111111111111';
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
      }
    ];

    service.getSeatMap(eventId).subscribe((seats) => {
      expect(seats).toEqual(mockSeats);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/events/${eventId}/seats`);
    expect(req.request.method).toBe('GET');
    req.flush(mockSeats);
  });
});
