import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { EventPage } from './event.model';
import { EventsApiService } from './events-api.service';

@Component({
  selector: 'app-event-list',
  standalone: true,
  imports: [DatePipe, RouterLink],
  templateUrl: './event-list.component.html',
  styleUrl: './event-list.component.css'
})
export class EventListComponent {
  private readonly eventsApi = inject(EventsApiService);

  readonly eventPage = signal<EventPage | null>(null);
  readonly loadError = signal<string | null>(null);

  constructor() {
    this.eventsApi.listEvents().subscribe({
      next: (eventPage) => this.eventPage.set(eventPage),
      error: () => this.loadError.set('Could not load events — please try again.')
    });
  }
}
