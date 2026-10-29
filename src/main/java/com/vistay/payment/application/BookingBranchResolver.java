package com.vistay.payment.application;

import java.util.Optional;
import java.util.UUID;

public interface BookingBranchResolver {
    Optional<UUID> resolveBranchId(UUID bookingId);

    default boolean isBookingAssignedTo(UUID bookingId, UUID staffId) {
        return false;
    }
}
