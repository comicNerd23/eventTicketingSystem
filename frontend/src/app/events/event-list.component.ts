import { AsyncPipe, DatePipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import { EventPage } from './event.model';
import { EventsApiService } from './events-api.service';

@Component({
  selector: 'app-event-list',
  standalone: true,
  imports: [AsyncPipe, DatePipe, RouterLink],
  templateUrl: './event-list.component.html',
  styleUrl: './event-list.component.css'
})
export class EventListComponent {
  private readonly eventsApi = inject(EventsApiService);

  eventPage$: Observable<EventPage> = this.eventsApi.listEvents();
}
