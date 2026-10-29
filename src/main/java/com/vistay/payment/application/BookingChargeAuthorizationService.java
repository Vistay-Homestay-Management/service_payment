package com.vistay.payment.application;

import com.vistay.payment.application.PaymentAuthorizationClient.AccountKind;
import com.vistay.payment.application.PaymentAuthorizationClient.AuthorizationException;
import com.vistay.payment.application.PaymentAuthorizationClient.Scope;
import com.vistay.payment.application.PaymentAuthorizationClient.Snapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Enforces Payment record scopes using the authoritative User Service snapshot. */
@Service
public class BookingChargeAuthorizationService {
    private static final String PARTITION_BRANCH = "BRANCH";

    private final BookingBranchResolver bookingBranchResolver;
    private final PaymentAuthorizationClient authorizations;

    public BookingChargeAuthorizationService(
            @Autowired(required = false) BookingBranchResolver bookingBranchResolver,
            PaymentAuthorizationClient authorizations) {
        this.bookingBranchResolver = bookingBranchResolver;
        this.authorizations = authorizations;
    }

    public UUID extractActorId(Jwt jwt) {
        Principal principal = principal(jwt);
        Snapshot snapshot = snapshot(principal);
        if (!snapshot.active() || snapshot.profileId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ViStay profile is inactive");
        }
        return snapshot.profileId();
    }

    public UUID requireActorId(Jwt jwt, String permission) {
        Principal principal = principal(jwt);
        return require(principal, permission).profileId();
    }

    public void requirePermission(Jwt jwt, String permission) {
        require(principal(jwt), permission);
    }

    public UUID extractOptionalActorId(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        try {
            return extractActorId(jwt);
        } catch (ResponseStatusException ignored) {
            return null;
        }
    }

    public String extractCorrelationId(Jwt jwt) {
        String mdc = org.slf4j.MDC.get("correlation_id");
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        if (jwt != null) {
            for (String claim : List.of("correlation_id", "cid", "trace_id")) {
                String value = jwt.getClaimAsString(claim);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return UUID.randomUUID().toString();
    }

    public String requireAll(Jwt jwt, String permission) {
        Principal principal = principal(jwt);
        Snapshot snapshot = require(principal, permission);
        if (!snapshot.scopes(permission).contains(Scope.ALL)) {
            throw forbidden(permission + " requires ALL scope");
        }
        return snapshot.profileId().toString();
    }

    public void assertReadDataScope(BookingChargeResponse charge, Jwt jwt) {
        if (hasInternalFinanceAuthority()) {
            return;
        }
        Principal principal = principal(jwt);
        Snapshot snapshot = require(principal, "payment:read");

        if (principal.accountType() == AccountKind.CUSTOMER) {
            if (!snapshot.scopes("payment:read").contains(Scope.OWN)
                    || !snapshot.profileId().equals(charge.customerId())) {
                throw forbidden("Customer cannot access charges belonging to another customer");
            }
            return;
        }

        assertStaffRecordScope(snapshot, "payment:read", charge.branchId(), charge.bookingId());
    }

    public void assertCreateDataScope(
            UUID branchId,
            UUID bookingId,
            UUID actorId,
            Jwt jwt,
            String permission) {
        if (hasInternalFinanceAuthority()) {
            return;
        }
        Principal principal = principal(jwt);
        Snapshot snapshot = require(principal, permission);
        if (!snapshot.profileId().equals(actorId)) {
            throw forbidden("Authenticated profile does not match command actor");
        }
        assertStaffRecordScope(snapshot, permission, branchId, bookingId);
    }

    public void assertWriteDataScope(
            UUID branchId,
            UUID bookingId,
            UUID ignoredCreatedByAdminUserId,
            UUID actorId,
            Jwt jwt,
            String permission) {
        if (hasInternalFinanceAuthority()) {
            return;
        }
        Principal principal = principal(jwt);
        Snapshot snapshot = require(principal, permission);
        if (!snapshot.profileId().equals(actorId)) {
            throw forbidden("Authenticated profile does not match command actor");
        }
        assertStaffRecordScope(snapshot, permission, branchId, bookingId);
    }

    public void assertPaymentIntentAccess(
            PaymentIntentAccess access,
            Jwt jwt,
            String permission) {
        if (access == null || access.bookingId() == null) {
            throw forbidden("Payment intent has no authoritative booking relationship");
        }
        Principal principal = principal(jwt);
        Snapshot snapshot = require(principal, permission);
        var scopes = snapshot.scopes(permission);

        if (principal.accountType() == AccountKind.CUSTOMER) {
            if (!scopes.contains(Scope.OWN)
                    || access.customerId() == null
                    || !snapshot.profileId().equals(access.customerId())) {
                throw forbidden("Customer cannot access a payment intent owned by another customer");
            }
            return;
        }

        UUID branchId = resolveBranchForPartition(snapshot, permission, access.bookingId());
        assertStaffRecordScope(snapshot, permission, branchId, access.bookingId());
    }

    public void assertBookingAccess(UUID bookingId, Jwt jwt) {
        if (hasInternalFinanceAuthority()) {
            return;
        }
        Principal principal = principal(jwt);
        Snapshot snapshot = require(principal, "payment:read");

        if (principal.accountType() == AccountKind.CUSTOMER) {
            requireResolver();
            Optional<UUID> bookingCustomer = bookingBranchResolver.resolveCustomerId(bookingId);
            if (!snapshot.scopes("payment:read").contains(Scope.OWN)
                    || bookingCustomer.isEmpty()
                    || !bookingCustomer.get().equals(snapshot.profileId())) {
                throw forbidden("Customer cannot access bookings belonging to another customer");
            }
            return;
        }

        UUID branchId = resolveBranchForPartition(snapshot, "payment:read", bookingId);
        assertStaffRecordScope(snapshot, "payment:read", branchId, bookingId);
    }

    private void assertStaffRecordScope(
            Snapshot snapshot,
            String permission,
            UUID branchId,
            UUID bookingId) {
        var scopes = snapshot.scopes(permission);
        if (scopes.contains(Scope.ALL)) {
            return;
        }
        if (scopes.contains(Scope.PARTITION) && branchId != null
                && snapshot.partitions(permission).stream().anyMatch(binding ->
                        PARTITION_BRANCH.equals(binding.partitionType())
                                && branchId.equals(binding.partitionId()))) {
            return;
        }
        if (scopes.contains(Scope.OWN)) {
            requireResolver();
            if (bookingBranchResolver.isBookingOwnedBy(bookingId, snapshot.profileId())) {
                return;
            }
        }
        if (scopes.contains(Scope.ASSIGNED)) {
            requireResolver();
            if (bookingBranchResolver.isBookingAssignedTo(bookingId, snapshot.profileId())) {
                return;
            }
        }
        throw forbidden("Record is outside the granted scope for " + permission);
    }

    private UUID resolveBranchForPartition(Snapshot snapshot, String permission, UUID bookingId) {
        if (!snapshot.scopes(permission).contains(Scope.PARTITION)) {
            return null;
        }
        requireResolver();
        return bookingBranchResolver.resolveBranchId(bookingId)
                .orElseThrow(() -> forbidden("Authoritative booking branch cannot be resolved"));
    }

    private void requireResolver() {
        if (bookingBranchResolver == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Authoritative booking resolver is unavailable: fail-closed");
        }
    }

    private Snapshot require(Principal principal, String permission) {
        try {
            return authorizations.require(principal.accountType(), principal.accountId(), permission);
        } catch (AuthorizationException denied) {
            HttpStatus status = denied.code().startsWith("AUTHORIZATION_")
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, denied.getMessage());
        }
    }

    private Snapshot snapshot(Principal principal) {
        try {
            return authorizations.get(principal.accountType(), principal.accountId());
        } catch (AuthorizationException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage());
        }
    }

    private Principal principal(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated principal");
        }
        UUID accountId;
        try {
            accountId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "JWT subject is not a ViStay account id");
        }
        String rawType = jwt.getClaimAsString("account_type");
        try {
            return new Principal(AccountKind.valueOf(rawType == null ? "" : rawType.trim().toUpperCase()), accountId);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "JWT account_type must be CUSTOMER, ADMIN or HOST");
        }
    }

    private boolean hasInternalFinanceAuthority() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "SCOPE_internal:finance".equals(authority.getAuthority())
                        || "ROLE_SYSTEM".equals(authority.getAuthority()));
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: " + reason);
    }

    private record Principal(AccountKind accountType, UUID accountId) {}
}
