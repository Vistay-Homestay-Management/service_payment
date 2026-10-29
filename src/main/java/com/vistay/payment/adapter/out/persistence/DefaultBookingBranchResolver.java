package com.vistay.payment.adapter.out.persistence;

import com.vistay.payment.application.BookingBranchResolver;
import com.vistay.payment.application.BookingProjectionClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of BookingBranchResolver.
 * Resolves branch ID and assignment authoritatively from BookingProjectionClient (upstream booking service).
 * Fails closed if the external client is unavailable or if booking does not exist.
 * Local booking charge projections are NEVER treated as authoritative for branch ownership.
 */
@Component
public class DefaultBookingBranchResolver implements BookingBranchResolver {

    private final BookingProjectionClient bookingProjectionClient;

    @Autowired
    public DefaultBookingBranchResolver(
            @Autowired(required = false) BookingProjectionClient bookingProjectionClient) {
        this.bookingProjectionClient = bookingProjectionClient;
    }

    @Override
    public Optional<UUID> resolveBranchId(UUID bookingId) {
        if (bookingProjectionClient == null) {
            return Optional.empty();
        }
        return bookingProjectionClient.getBranchId(bookingId);
    }

    @Override
    public boolean isBookingAssignedTo(UUID bookingId, UUID staffId) {
        if (bookingProjectionClient == null) {
            return false;
        }
        return bookingProjectionClient.isBookingAssignedTo(bookingId, staffId);
    }

    @Override
    public boolean isBookingOwnedBy(UUID bookingId, UUID staffId) {
        if (bookingProjectionClient == null) {
            return false;
        }
        return bookingProjectionClient.isBookingOwnedBy(bookingId, staffId);
    }

    @Override
    public Optional<UUID> resolveCustomerId(UUID bookingId) {
        if (bookingProjectionClient == null) {
            return Optional.empty();
        }
        return bookingProjectionClient.getCustomerId(bookingId);
    }
}
