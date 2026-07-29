export interface Event {
  id: string;
  title: string;
  description: string | null;
  category: string;
  venueId: string;
  venueName: string;
  city: string;
  startsAt: string;
  endsAt: string;
  status: string;
  organizerId: string;
  totalSeats: number;
  availableSeats: number;
  imageUrl: string | null;
  createdAt: string;
}

export interface EventPage {
  content: Event[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';

export interface Seat {
  id: string;
  sectionId: string;
  sectionName: string;
  rowNumber: number;
  seatNumber: number;
  label: string;
  priceGbp: number;
  status: SeatStatus;
}
