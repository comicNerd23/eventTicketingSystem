import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

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
    size: 10
  };

  function setup(eventsApiStub: Partial<EventsApiService>) {
    TestBed.configureTestingModule({
      imports: [EventListComponent],
      providers: [provideRouter([]), { provide: EventsApiService, useValue: eventsApiStub }]
    });
    return TestBed.createComponent(EventListComponent);
  }

  it('should create', () => {
    const fixture = setup({ listEvents: () => of(mockPage) });
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the event title, category badge from the stubbed service', () => {
    const fixture = setup({ listEvents: () => of(mockPage) });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Coldplay: Music of the Spheres Tour');
    expect(compiled.textContent).toContain('The O2 Arena, London');
    expect(compiled.querySelector('.category')?.textContent).toContain('CONCERT');
  });

  it('shows an error message instead of an infinite loading state when listEvents fails', () => {
    const fixture = setup({ listEvents: () => throwError(() => new Error('network down')) });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Could not load events');
    expect(compiled.textContent).not.toContain('Loading events');
  });

  it('renders a loading skeleton instead of plain text while the request is pending', () => {
    const fixture = setup({ listEvents: () => new Subject<EventPage>() });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.skeleton')).toBeTruthy();
    expect(compiled.textContent).not.toContain('Loading events');
  });

  it('hides pagination controls when there is only one page', () => {
    const fixture = setup({ listEvents: () => of(mockPage) });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.pagination')).toBeFalsy();
  });

  it('shows pagination controls and requests the next page on click', () => {
    const multiPage: EventPage = { ...mockPage, totalPages: 3, page: 0 };
    const listEvents = jasmine.createSpy().and.returnValue(of(multiPage));
    const fixture = setup({ listEvents });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Page 1 / 3');

    const [previousButton, nextButton] = Array.from(compiled.querySelectorAll('.pagination button')) as HTMLButtonElement[];
    expect(previousButton.disabled).toBeTrue();
    expect(nextButton.disabled).toBeFalse();

    nextButton.click();

    expect(listEvents).toHaveBeenCalledWith(1);
  });
});
