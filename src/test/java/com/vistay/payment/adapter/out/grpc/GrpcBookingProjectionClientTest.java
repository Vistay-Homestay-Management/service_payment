package com.vistay.payment.adapter.out.grpc;

import com.vistay.contracts.booking.v1.GetBookingAuthorizationRecordResponse;
import com.vistay.contracts.booking.v1.BookingPartitionResolutionStatus;
import com.vistay.payment.application.BookingProjectionException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcBookingProjectionClientTest {

    @Test
    void resolvesDirectAndActiveAttributionAssignments() {
        var bookingId = UUID.randomUUID();
        var direct = UUID.randomUUID();
        var attributed = UUID.randomUUID();
        var customer = UUID.randomUUID();
        var property = UUID.randomUUID();
        var record = GetBookingAuthorizationRecordResponse.newBuilder()
            .setBookingId(bookingId.toString())
            .setCustomerId(customer.toString())
            .setPropertyId(property.toString())
            .setAssignedAdminUserId(direct.toString())
            .addActiveAttributedAdminUserIds(attributed.toString())
            .build();
        var client = new GrpcBookingProjectionClient(ignored -> Optional.of(record));

        assertThat(client.isBookingAssignedTo(bookingId, direct)).isTrue();
        assertThat(client.isBookingAssignedTo(bookingId, attributed)).isTrue();
        assertThat(client.isBookingAssignedTo(bookingId, UUID.randomUUID())).isFalse();
        assertThat(client.getCustomerId(bookingId)).contains(customer);
        assertThat(client.getPropertyId(bookingId)).contains(property);
        assertThat(client.isBookingOwnedBy(bookingId, direct)).isFalse();
    }

    @Test
    void missingBookingIsDeniedWithoutInventingRelationships() {
        var client = new GrpcBookingProjectionClient(ignored -> Optional.empty());

        assertThat(client.isBookingAssignedTo(UUID.randomUUID(), UUID.randomUUID())).isFalse();
        assertThat(client.getCustomerId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void dependencyFailureFailsClosedAsUnavailable() {
        var client = new GrpcBookingProjectionClient(ignored -> {
            throw new BookingProjectionException("unavailable", new RuntimeException());
        });

        assertThatThrownBy(() -> client.isBookingAssignedTo(
            UUID.randomUUID(), UUID.randomUUID()))
            .isInstanceOf(BookingProjectionException.class);
    }

    @Test
    void resolvesAuthoritativeBranchForPartitionScope() {
        var bookingId = UUID.randomUUID();
        var branchId = UUID.randomUUID();
        var record = GetBookingAuthorizationRecordResponse.newBuilder()
            .setBookingId(bookingId.toString())
            .setPropertyId(UUID.randomUUID().toString())
            .setPartitionResolutionStatus(
                BookingPartitionResolutionStatus.BOOKING_PARTITION_RESOLUTION_STATUS_MAPPED)
            .setBranchId(branchId.toString())
            .setPartitionMembershipVersion(3)
            .build();

        assertThat(new GrpcBookingProjectionClient(ignored -> Optional.of(record))
            .getBranchId(bookingId)).contains(branchId);
    }

    @Test
    void missingMappingDeniesWhileUnavailableProjectionReturnsServiceFailure() {
        var bookingId = UUID.randomUUID();
        var missing = GetBookingAuthorizationRecordResponse.newBuilder()
            .setBookingId(bookingId.toString())
            .setPropertyId(UUID.randomUUID().toString())
            .setPartitionResolutionStatus(
                BookingPartitionResolutionStatus.BOOKING_PARTITION_RESOLUTION_STATUS_MISSING)
            .build();
        assertThat(new GrpcBookingProjectionClient(ignored -> Optional.of(missing))
            .getBranchId(bookingId)).isEmpty();

        var unavailable = missing.toBuilder().setPartitionResolutionStatus(
            BookingPartitionResolutionStatus.BOOKING_PARTITION_RESOLUTION_STATUS_UNAVAILABLE).build();
        assertThatThrownBy(() -> new GrpcBookingProjectionClient(ignored -> Optional.of(unavailable))
            .getBranchId(bookingId)).isInstanceOf(BookingProjectionException.class);
    }

}
