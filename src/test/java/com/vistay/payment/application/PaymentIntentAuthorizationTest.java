package com.vistay.payment.application;

import com.vistay.payment.application.PaymentAuthorizationClient.AccountKind;
import com.vistay.payment.application.PaymentAuthorizationClient.Scope;
import com.vistay.payment.application.PaymentAuthorizationClient.Snapshot;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentIntentAuthorizationTest {
    @Mock
    private BookingBranchResolver bookingResolver;
    @Mock
    private PaymentAuthorizationClient client;

    @Test
    void customerOwnScopeIsBoundToTheAuthoritativeCustomerRelationship() {
        var accountId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var jwt = jwt(accountId, "CUSTOMER");
        when(client.require(AccountKind.CUSTOMER, accountId, "payment:read"))
            .thenReturn(snapshot(customerId, "payment:read", Scope.OWN));
        var service = new BookingChargeAuthorizationService(bookingResolver, client);

        assertDoesNotThrow(() -> service.assertPaymentIntentAccess(
            new PaymentIntentAccess(UUID.randomUUID(), customerId), jwt, "payment:read"));

        var denied = assertThrows(ResponseStatusException.class,
            () -> service.assertPaymentIntentAccess(
                new PaymentIntentAccess(UUID.randomUUID(), UUID.randomUUID()),
                jwt,
                "payment:read"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
    }

    @Test
    void assignedStaffMustHaveAnAuthoritativeBookingAssignment() {
        var accountId = UUID.randomUUID();
        var profileId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var jwt = jwt(accountId, "ADMIN");
        when(client.require(AccountKind.ADMIN, accountId, "payment:cancel"))
            .thenReturn(snapshot(profileId, "payment:cancel", Scope.ASSIGNED));
        when(bookingResolver.isBookingAssignedTo(bookingId, profileId))
            .thenReturn(true, false);
        var service = new BookingChargeAuthorizationService(bookingResolver, client);

        assertDoesNotThrow(() -> service.assertPaymentIntentAccess(
            new PaymentIntentAccess(bookingId, UUID.randomUUID()), jwt, "payment:cancel"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.assertPaymentIntentAccess(
                new PaymentIntentAccess(bookingId, UUID.randomUUID()), jwt, "payment:cancel"))
            .getStatusCode());
    }

    @Test
    void partitionScopeFailsClosedWhenBookingBranchCannotBeResolved() {
        var accountId = UUID.randomUUID();
        var profileId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var jwt = jwt(accountId, "ADMIN");
        when(client.require(AccountKind.ADMIN, accountId, "payment:create"))
            .thenReturn(snapshot(profileId, "payment:create", Scope.PARTITION));
        when(bookingResolver.resolveBranchId(bookingId)).thenReturn(java.util.Optional.empty());

        var denied = assertThrows(ResponseStatusException.class,
            () -> new BookingChargeAuthorizationService(bookingResolver, client)
                .assertPaymentIntentAccess(
                    new PaymentIntentAccess(bookingId, UUID.randomUUID()),
                    jwt,
                    "payment:create"));

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
    }

    @Test
    void bookingDependencyFailureIsReportedAsServiceUnavailable() {
        var accountId = UUID.randomUUID();
        var profileId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var jwt = jwt(accountId, "ADMIN");
        when(client.require(AccountKind.ADMIN, accountId, "payment:read"))
            .thenReturn(snapshot(profileId, "payment:read", Scope.ASSIGNED));
        when(bookingResolver.isBookingAssignedTo(bookingId, profileId))
            .thenThrow(new BookingProjectionException("unavailable", new RuntimeException()));

        var denied = assertThrows(ResponseStatusException.class,
            () -> new BookingChargeAuthorizationService(bookingResolver, client)
                .assertPaymentIntentAccess(
                    new PaymentIntentAccess(bookingId, UUID.randomUUID()),
                    jwt,
                    "payment:read"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, denied.getStatusCode());
    }

    private static Jwt jwt(UUID accountId, String accountType) {
        var jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(accountId.toString());
        when(jwt.getClaimAsString("account_type")).thenReturn(accountType);
        return jwt;
    }

    private static Snapshot snapshot(
            UUID profileId,
            String permission,
            Scope scope) {
        return new Snapshot(true, profileId, 1,
            Map.of(permission, Set.of(scope)), Map.of());
    }
}
