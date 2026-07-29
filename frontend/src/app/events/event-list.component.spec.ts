import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { EventListComponent } from './event-list.component';
import { EventPage } from './event.model';
import { EventsApiService } from './events-api.service';

describe('EventListComponent', () => {
  const mockPage: EventPage = {
    content: [
      {
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

  beforeEach(async () => {
    const eventsApiStub = { listEvents: () => of(mockPage) };

    await TestBed.configureTestingModule({
      imports: [EventListComponent],
      providers: [provideRouter([]), { provide: EventsApiService, useValue: eventsApiStub }]
    }).compileComponents();
  });

  it('should create', () => {
    const fixture = TestBed.createComponent(EventListComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the event title from the stubbed service', () => {
    const fixture = TestBed.createComponent(EventListComponent);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Coldplay: Music of the Spheres Tour');
    expect(compiled.textContent).toContain('The O2 Arena, London');
  });
});
