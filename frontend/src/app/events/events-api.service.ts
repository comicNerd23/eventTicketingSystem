import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { EventPage } from './event.model';

@Injectable({ providedIn: 'root' })
export class EventsApiService {
  private readonly http = inject(HttpClient);

  listEvents(): Observable<EventPage> {
    return this.http.get<EventPage>(`${environment.apiBaseUrl}/events`);
  }
}
