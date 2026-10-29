package com.vistay.payment.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative client interface for resolving booking metadata (such as branch affiliation and staff assignment)
 * from upstream service_booking or synchronized platform booking projections.
 */
public interface BookingProjectionClient {
    Optional<UUID> getBranchId(UUID bookingId);

    default Optional<UUID> getPropertyId(UUID bookingId) {
        return Optional.empty();
    }

    default boolean isBookingAssignedTo(UUID bookingId, UUID staffId) {
        return false;
    }

    default boolean isBookingOwnedBy(UUID bookingId, UUID staffId) {
        return false;
    }

    default Optional<UUID> getCustomerId(UUID bookingId) {
        return Optional.empty();
    }
}
