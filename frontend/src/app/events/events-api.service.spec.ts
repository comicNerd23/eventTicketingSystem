import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../environments/environment';
import { EventPage } from './event.model';
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

  it('listEvents fetches from the gateway events endpoint', () => {
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
      size: 20
    };

    service.listEvents().subscribe((page) => {
      expect(page).toEqual(mockPage);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/events`);
    expect(req.request.method).toBe('GET');
    req.flush(mockPage);
  });
});
