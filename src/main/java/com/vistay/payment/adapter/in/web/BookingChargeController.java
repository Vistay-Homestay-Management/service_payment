package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/v1/payment/booking-charges")
public class BookingChargeController {

    private final BookingChargeService bookingChargeService;

    public BookingChargeController(BookingChargeService bookingChargeService) {
        this.bookingChargeService = bookingChargeService;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:write', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE')")
    public ResponseEntity<BookingChargeResponse> createCharge(
            @Valid @RequestBody CreateBookingChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = extractUserIdOrThrow(jwt);
        assertWriteDataScope(command.branchId(), actorId, actorId, jwt);
        BookingChargeResponse response = bookingChargeService.createCharge(command, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/apply-payment")
    @PreAuthorize("hasAnyAuthority('SCOPE_internal:finance', 'ROLE_SYSTEM')")
    public ResponseEntity<BookingChargeResponse> applyPayment(
            @PathVariable UUID id,
            @Valid @RequestBody ApplyPaymentToChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = extractOptionalUserId(jwt);
        return ResponseEntity.ok(bookingChargeService.applyPayment(id, command, actorId));
    }

    @PostMapping("/{id}/waive")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:write', 'ROLE_ADMIN', 'ROLE_FINANCE')")
    public ResponseEntity<BookingChargeResponse> waiveCharge(
            @PathVariable UUID id,
            @Valid @RequestBody WaiveBookingChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = extractUserIdOrThrow(jwt);
        BookingChargeResponse charge = bookingChargeService.getChargeById(id);
        assertWriteDataScope(charge.branchId(), charge.createdByAdminUserId(), actorId, jwt);
        return ResponseEntity.ok(bookingChargeService.waiveCharge(id, command, actorId));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:write', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE')")
    public ResponseEntity<BookingChargeResponse> cancelCharge(
            @PathVariable UUID id,
            @Valid @RequestBody CancelBookingChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = extractUserIdOrThrow(jwt);
        BookingChargeResponse charge = bookingChargeService.getChargeById(id);
        assertWriteDataScope(charge.branchId(), charge.createdByAdminUserId(), actorId, jwt);
        return ResponseEntity.ok(bookingChargeService.cancelCharge(id, command, actorId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE', 'ROLE_USER')")
    public ResponseEntity<BookingChargeResponse> getChargeById(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        BookingChargeResponse response = bookingChargeService.getChargeById(id);
        assertReadDataScope(response, jwt);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/code/{chargeCode}")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE', 'ROLE_USER')")
    public ResponseEntity<BookingChargeResponse> getChargeByCode(
            @PathVariable String chargeCode,
            @AuthenticationPrincipal Jwt jwt) {
        BookingChargeResponse response = bookingChargeService.getChargeByCode(chargeCode);
        assertReadDataScope(response, jwt);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/booking/{bookingId}")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE', 'ROLE_USER')")
    public ResponseEntity<List<BookingChargeResponse>> getChargesByBooking(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal Jwt jwt) {
        List<BookingChargeResponse> charges = bookingChargeService.getChargesByBooking(bookingId);
        for (BookingChargeResponse charge : charges) {
            assertReadDataScope(charge, jwt);
        }
        return ResponseEntity.ok(charges);
    }

    @GetMapping("/booking/{bookingId}/open-balance")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE', 'ROLE_USER')")
    public ResponseEntity<BookingOpenBalanceSummaryResponse> getBookingOpenBalance(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal Jwt jwt) {
        BookingOpenBalanceSummaryResponse summary = bookingChargeService.getBookingOpenBalanceSummary(bookingId);
        for (BookingChargeResponse charge : summary.charges()) {
            assertReadDataScope(charge, jwt);
        }
        return ResponseEntity.ok(summary);
    }

    private void assertReadDataScope(BookingChargeResponse charge, Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated security context");
        }

        // 1. Customer scope (ROLE_USER)
        boolean isCustomer = hasRole(jwt, "ROLE_USER");
        if (isCustomer && !isStaffCaller()) {
            UUID customerId = extractCustomerId(jwt);
            if (!customerId.equals(charge.customerId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Cannot access booking charges belonging to another customer");
            }
            return;
        }

        // 2. Staff Data Scope Evaluation (ALL, OWN, BRANCH)
        if (hasAllScope(jwt) || hasRole(jwt, "ROLE_ADMIN")) {
            return;
        }

        UUID staffId = extractUserIdOrThrow(jwt);

        if (hasScope(jwt, "OWN")) {
            if (charge.createdByAdminUserId() == null || !charge.createdByAdminUserId().equals(staffId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff with OWN scope cannot access charges created by another actor");
            }
            return;
        }

        if (hasScope(jwt, "BRANCH")) {
            List<String> assignedBranches = extractAssignedBranches(jwt);
            if (charge.branchId() != null && !assignedBranches.contains(charge.branchId().toString())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff is not authorized for branch " + charge.branchId());
            }
            return;
        }

        // Fallback for internal machine-to-machine service token
        if (hasRole(jwt, "ROLE_SYSTEM") || hasAuthority(jwt, "SCOPE_internal:finance")) {
            return;
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Missing valid data scope for charge");
    }

    private void assertWriteDataScope(UUID branchId, UUID createdByAdminUserId, UUID actorId, Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated security context");
        }
        if (hasAllScope(jwt) || hasRole(jwt, "ROLE_ADMIN") || hasRole(jwt, "ROLE_SYSTEM")) {
            return;
        }
        if (hasScope(jwt, "BRANCH")) {
            if (branchId == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff with BRANCH scope cannot modify charges without an assigned branch");
            }
            List<String> assignedBranches = extractAssignedBranches(jwt);
            if (!assignedBranches.contains(branchId.toString())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff is not authorized to modify charges for branch " + branchId);
            }
            return;
        }
        if (hasScope(jwt, "OWN")) {
            if (createdByAdminUserId != null && !createdByAdminUserId.equals(actorId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff with OWN scope cannot modify charges created by another actor");
            }
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Access denied: Missing valid write data scope (must have ALL, BRANCH, or OWN scope)");
    }

    private boolean isStaffCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_ADMIN".equals(role)
                    || "ROLE_FINANCE".equals(role)
                    || "ROLE_RECEPTIONIST".equals(role)
                    || "ROLE_SYSTEM".equals(role)
                    || "SCOPE_internal:finance".equals(role);
        });
    }

    private boolean hasRole(Jwt jwt, String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()))) {
            return true;
        }
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains(role.replace("ROLE_", ""));
    }

    private boolean hasAuthority(Jwt jwt, String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()))) {
            return true;
        }
        String scope = jwt.getClaimAsString("scope");
        return scope != null && scope.contains(authority.replace("SCOPE_", ""));
    }

    private boolean hasAllScope(Jwt jwt) {
        return hasScope(jwt, "ALL");
    }

    private boolean hasScope(Jwt jwt, String targetScope) {
        // 1. Check claim 'grants' formatted as List<Map<String, Object>> or Map<String, Object>
        Object grantsObj = jwt.getClaims().get("grants");
        if (grantsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object s = map.get("s");
                    Object scope = map.get("scope");
                    if (targetScope.equalsIgnoreCase(Objects.toString(s, ""))
                            || targetScope.equalsIgnoreCase(Objects.toString(scope, ""))) {
                        return true;
                    }
                }
            }
        } else if (grantsObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof Collection<?> coll && coll.contains(targetScope)) {
                    return true;
                }
                if (targetScope.equalsIgnoreCase(Objects.toString(val, ""))) {
                    return true;
                }
            }
        }
        // 2. Check claim 'scope' or 'data_scope'
        String dataScope = jwt.getClaimAsString("data_scope");
        return targetScope.equalsIgnoreCase(dataScope);
    }

    private List<String> extractAssignedBranches(Jwt jwt) {
        List<String> bld = jwt.getClaimAsStringList("bld");
        if (bld != null) {
            return bld;
        }
        List<String> branches = jwt.getClaimAsStringList("branches");
        return branches != null ? branches : Collections.emptyList();
    }

    private UUID extractCustomerId(Jwt jwt) {
        String customerIdClaim = jwt.getClaimAsString("customer_id");
        if (customerIdClaim != null && !customerIdClaim.isBlank()) {
            try {
                return UUID.fromString(customerIdClaim.trim());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return extractUserIdOrThrow(jwt);
    }

    private UUID extractUserIdOrThrow(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid authenticated principal");
        }
        String sub = jwt.getSubject().trim();
        if (sub.contains(":")) {
            sub = sub.substring(sub.lastIndexOf(':') + 1);
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Principal subject is not a valid UUID: " + jwt.getSubject());
        }
    }

    private UUID extractOptionalUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return null;
        }
        String sub = jwt.getSubject().trim();
        if (sub.contains(":")) {
            sub = sub.substring(sub.lastIndexOf(':') + 1);
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
