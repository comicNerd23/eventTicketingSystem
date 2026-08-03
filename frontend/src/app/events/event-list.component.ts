import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { EventPage } from './event.model';
import { EventsApiService } from './events-api.service';

const TILE_COLORS: Record<string, string> = {
  CONCERT: 'bg-n-pink',
  SPORTS: 'bg-n-teal',
  THEATRE: 'bg-n-purple',
  COMEDY: 'bg-n-yellow'
};
const DEFAULT_TILE_COLOR = 'bg-n-slate';

const TILE_ROTATIONS = ['', '-rotate-1', 'rotate-1'];

@Component({
  selector: 'app-event-list',
  standalone: true,
  imports: [DatePipe, RouterLink],
  templateUrl: './event-list.component.html',
  styleUrl: './event-list.component.css'
})
export class EventListComponent {
  private readonly eventsApi = inject(EventsApiService);

  tileColorClass(category: string): string {
    return TILE_COLORS[category] ?? DEFAULT_TILE_COLOR;
  }

  tileRotationClass(index: number): string {
    return TILE_ROTATIONS[index % TILE_ROTATIONS.length];
  }

  readonly eventPage = signal<EventPage | null>(null);
  readonly loadError = signal<string | null>(null);

  constructor() {
    this.loadPage(0);
  }

  nextPage(): void {
    const page = this.eventPage();
    if (page && page.page + 1 < page.totalPages) {
      this.loadPage(page.page + 1);
    }
  }

  previousPage(): void {
    const page = this.eventPage();
    if (page && page.page > 0) {
      this.loadPage(page.page - 1);
    }
  }

  private loadPage(page: number): void {
    this.eventsApi.listEvents(page).subscribe({
      next: (eventPage) => this.eventPage.set(eventPage),
      error: () => this.loadError.set('Could not load events — please try again.')
    });
  }
}
