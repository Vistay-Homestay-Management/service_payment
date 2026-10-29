package com.vistay.payment.adapter.out.grpc;

import com.vistay.contracts.booking.v1.BookingAuthorizationServiceGrpc;
import com.vistay.contracts.booking.v1.BookingPartitionResolutionStatus;
import com.vistay.contracts.booking.v1.GetBookingAuthorizationRecordRequest;
import com.vistay.contracts.booking.v1.GetBookingAuthorizationRecordResponse;
import com.vistay.payment.application.BookingProjectionClient;
import com.vistay.payment.application.BookingProjectionException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

@Component
public class GrpcBookingProjectionClient implements BookingProjectionClient {
    private final Lookup lookup;

    public GrpcBookingProjectionClient(
        GrpcChannelFactory channels,
        @Value("${vistay.booking.authorization-deadline:2s}") Duration deadline
    ) {
        this(remoteLookup(
            BookingAuthorizationServiceGrpc.newBlockingStub(channels.createChannel("booking")),
            deadline));
    }

    GrpcBookingProjectionClient(Lookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Optional<UUID> getBranchId(UUID bookingId) {
        return lookup(bookingId).flatMap(record -> {
            if (record.getPartitionResolutionStatus()
                == BookingPartitionResolutionStatus.BOOKING_PARTITION_RESOLUTION_STATUS_MISSING) {
                return Optional.empty();
            }
            if (record.getPartitionResolutionStatus()
                != BookingPartitionResolutionStatus.BOOKING_PARTITION_RESOLUTION_STATUS_MAPPED
                || !record.hasBranchId()) {
                throw new BookingProjectionException(
                    "Authoritative Property-to-Branch projection is unavailable",
                    new IllegalStateException("Booking projection did not resolve a branch"));
            }
            try {
                return Optional.of(UUID.fromString(record.getBranchId()));
            } catch (IllegalArgumentException malformedContract) {
                throw new BookingProjectionException(
                    "Authoritative Property-to-Branch projection is malformed", malformedContract);
            }
        });
    }

    @Override
    public Optional<UUID> getPropertyId(UUID bookingId) {
        return lookup(bookingId).map(record -> UUID.fromString(record.getPropertyId()));
    }

    @Override
    public boolean isBookingAssignedTo(UUID bookingId, UUID staffId) {
        return lookup(bookingId).map(record ->
            (record.hasAssignedAdminUserId()
                && staffId.toString().equals(record.getAssignedAdminUserId()))
            || record.getActiveAttributedAdminUserIdsList().contains(staffId.toString())
        ).orElse(false);
    }

    @Override
    public boolean isBookingOwnedBy(UUID bookingId, UUID staffId) {
        // Creator identity is audit provenance, not business ownership.
        return false;
    }

    @Override
    public Optional<UUID> getCustomerId(UUID bookingId) {
        return lookup(bookingId)
            .filter(GetBookingAuthorizationRecordResponse::hasCustomerId)
            .map(record -> UUID.fromString(record.getCustomerId()));
    }

    private Optional<GetBookingAuthorizationRecordResponse> lookup(UUID bookingId) {
        return lookup.find(bookingId);
    }

    private static Lookup remoteLookup(
        BookingAuthorizationServiceGrpc.BookingAuthorizationServiceBlockingStub stub,
        Duration deadline
    ) {
        return bookingId -> {
        try {
            return Optional.of(stub
                .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .getBookingAuthorizationRecord(GetBookingAuthorizationRecordRequest.newBuilder()
                    .setBookingId(bookingId.toString())
                    .build()));
        } catch (StatusRuntimeException failure) {
            if (failure.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw new BookingProjectionException(
                "Authoritative Booking projection is unavailable", failure);
        }
        };
    }

    @FunctionalInterface
    interface Lookup {
        Optional<GetBookingAuthorizationRecordResponse> find(UUID bookingId);
    }
}
