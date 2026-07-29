import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { Event, EventPage, Seat } from './event.model';

@Injectable({ providedIn: 'root' })
export class EventsApiService {
  private readonly http = inject(HttpClient);

  listEvents(): Observable<EventPage> {
    return this.http.get<EventPage>(`${environment.apiBaseUrl}/events`);
  }

  getEvent(eventId: string): Observable<Event> {
    return this.http.get<Event>(`${environment.apiBaseUrl}/events/${eventId}`);
  }

  getSeatMap(eventId: string): Observable<Seat[]> {
    return this.http.get<Seat[]>(`${environment.apiBaseUrl}/events/${eventId}/seats`);
  }
}
