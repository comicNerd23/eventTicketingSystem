import { Routes } from '@angular/router';

import { BookingStatusComponent } from './bookings/booking-status.component';
import { EventListComponent } from './events/event-list.component';
import { SeatMapComponent } from './events/seat-map.component';
import { NotFoundComponent } from './not-found.component';

export const routes: Routes = [
  { path: '', component: EventListComponent },
  { path: 'events/:id', component: SeatMapComponent },
  { path: 'bookings/:id', component: BookingStatusComponent },
  { path: '**', component: NotFoundComponent }
];
