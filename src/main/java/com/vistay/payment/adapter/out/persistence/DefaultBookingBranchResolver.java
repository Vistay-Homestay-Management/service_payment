package com.vistay.payment.adapter.out.persistence;

import com.vistay.payment.application.BookingBranchResolver;
import com.vistay.payment.application.BookingProjectionClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of BookingBranchResolver.
 * Resolves customer and assignment relationships authoritatively from Booking Service.
 * Booking does not currently own a branch relationship, so branch partition checks remain fail-closed.
 * Fails closed if the external client is unavailable or if booking does not exist.
 * Local booking charge projections are NEVER treated as authoritative for branch ownership.
 */
@Component
public class DefaultBookingBranchResolver implements BookingBranchResolver {

    private final BookingProjectionClient bookingProjectionClient;

    public DefaultBookingBranchResolver(BookingProjectionClient bookingProjectionClient) {
        this.bookingProjectionClient = bookingProjectionClient;
    }

    @Override
    public Optional<UUID> resolveBranchId(UUID bookingId) {
        return bookingProjectionClient.getBranchId(bookingId);
    }

    @Override
    public boolean isBookingAssignedTo(UUID bookingId, UUID staffId) {
        return bookingProjectionClient.isBookingAssignedTo(bookingId, staffId);
    }

    @Override
    public boolean isBookingOwnedBy(UUID bookingId, UUID staffId) {
        return bookingProjectionClient.isBookingOwnedBy(bookingId, staffId);
    }

    @Override
    public Optional<UUID> resolveCustomerId(UUID bookingId) {
        return bookingProjectionClient.getCustomerId(bookingId);
    }
}
