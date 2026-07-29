export type BookingStatus = 'HELD' | 'PAYMENT_PENDING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED';

export interface Booking {
  id: string;
  eventId: string;
  eventTitle: string;
  seatId: string;
  seatLabel: string;
  userId: string;
  status: BookingStatus;
  totalAmountGbp: number;
  holdExpiresAt: string | null;
  confirmedAt: string | null;
  cancelledAt: string | null;
  expiredAt: string | null;
  ticketReference: string | null;
  createdAt: string;
}
