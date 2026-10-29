package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/payment/booking-charges")
public class BookingChargeController {

    private final BookingChargeService bookingChargeService;
    private final BookingBranchResolver bookingBranchResolver;

    public BookingChargeController(
            BookingChargeService bookingChargeService,
            @Autowired(required = false) BookingBranchResolver bookingBranchResolver) {
        this.bookingChargeService = bookingChargeService;
        this.bookingBranchResolver = bookingBranchResolver;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:write', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE')")
    public ResponseEntity<BookingChargeResponse> createCharge(
            @Valid @RequestBody CreateBookingChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = extractUserIdOrThrow(jwt);

        if (hasDataScope(jwt, "payment:write", "BRANCH") && command.branchId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Access denied: Staff with BRANCH scope cannot modify charges without an assigned branch");
        }

        UUID resolvedBranchId = null;
        if (bookingBranchResolver != null) {
            Optional<UUID> authoritativeBranch = bookingBranchResolver.resolveBranchId(command.bookingId());
            if (authoritativeBranch.isPresent()) {
                resolvedBranchId = authoritativeBranch.get();
                if (command.branchId() != null && !command.branchId().equals(resolvedBranchId)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Requested branch ID (" + command.branchId()
                                    + ") conflicts with authoritative booking branch (" + resolvedBranchId + ")");
                }
            } else if (hasDataScope(jwt, "payment:write", "BRANCH")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Booking branch cannot be authoritatively resolved, BRANCH scope rejected");
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Authoritative booking branch cannot be resolved for booking: " + command.bookingId());
            }
        } else {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Authoritative booking branch resolver is not available: fail-closed");
        }

        assertWriteDataScope(resolvedBranchId, command.bookingId(), actorId, actorId, jwt);
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
        assertWriteDataScope(charge.branchId(), charge.bookingId(), charge.createdByAdminUserId(), actorId, jwt);
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
        assertWriteDataScope(charge.branchId(), charge.bookingId(), charge.createdByAdminUserId(), actorId, jwt);
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

        // Fallback for internal machine-to-machine service token
        if (hasRole(jwt, "ROLE_SYSTEM") || hasAuthority(jwt, "SCOPE_internal:finance")) {
            return;
        }

        // 2. Staff Data Scope Evaluation (ALL, OWN, BRANCH, ASSIGNED)
        // Must pair payment:read with scope
        if (hasDataScope(jwt, "payment:read", "ALL") || hasRole(jwt, "ROLE_ADMIN")) {
            return;
        }

        UUID staffId = extractUserIdOrThrow(jwt);

        if (hasDataScope(jwt, "payment:read", "OWN")) {
            if (charge.createdByAdminUserId() == null || !charge.createdByAdminUserId().equals(staffId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff with OWN scope cannot access charges created by another actor");
            }
            return;
        }

        if (hasDataScope(jwt, "payment:read", "BRANCH")) {
            if (charge.branchId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff with BRANCH scope cannot access charges without an assigned branch");
            }
            List<String> assignedBranches = extractAssignedBranches(jwt);
            if (!assignedBranches.contains(charge.branchId().toString())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff is not authorized for branch " + charge.branchId());
            }
            return;
        }

        if (hasDataScope(jwt, "payment:read", "ASSIGNED")) {
            if (bookingBranchResolver != null && bookingBranchResolver.isBookingAssignedTo(charge.bookingId(), staffId)) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Access denied: Staff with ASSIGNED scope is not assigned to booking " + charge.bookingId());
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Access denied: Missing valid data scope for payment:read (must have ALL, BRANCH, OWN, or ASSIGNED scope)");
    }

    private void assertWriteDataScope(UUID branchId, UUID bookingId, UUID createdByAdminUserId, UUID actorId, Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated security context");
        }
        if (hasRole(jwt, "ROLE_SYSTEM") || hasAuthority(jwt, "SCOPE_internal:finance")) {
            return;
        }
        if (hasDataScope(jwt, "payment:write", "ALL") || hasRole(jwt, "ROLE_ADMIN")) {
            return;
        }
        if (hasDataScope(jwt, "payment:write", "BRANCH")) {
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
        if (hasDataScope(jwt, "payment:write", "OWN")) {
            if (createdByAdminUserId != null && !createdByAdminUserId.equals(actorId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff with OWN scope cannot modify charges created by another actor");
            }
            return;
        }
        if (hasDataScope(jwt, "payment:write", "ASSIGNED")) {
            if (bookingBranchResolver != null && bookingBranchResolver.isBookingAssignedTo(bookingId, actorId)) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Access denied: Staff with ASSIGNED scope is not assigned to booking " + bookingId);
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Access denied: Missing valid write data scope (must have ALL, BRANCH, OWN, or ASSIGNED scope for payment:write)");
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
        if (scope != null) {
            String target = authority.startsWith("SCOPE_") ? authority.substring(6) : authority;
            for (String part : scope.split("\\s+")) {
                if (target.equalsIgnoreCase(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasDataScope(Jwt jwt, String requiredPermission, String targetScope) {
        if (jwt == null) {
            return false;
        }

        // 1. Check claim 'grants' formatted as List<Map<String, Object>> (S4 ViStay spec)
        Object grantsObj = jwt.getClaims().get("grants");
        if (grantsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object pObj = map.get("p");
                    if (pObj == null) {
                        pObj = map.get("permission");
                    }
                    String p = Objects.toString(pObj, "").trim();

                    if (permissionMatches(p, requiredPermission)) {
                        Object sObj = map.get("s");
                        if (sObj == null) {
                            sObj = map.get("scope");
                        }
                        String s = Objects.toString(sObj, "").trim();
                        if (targetScope.equalsIgnoreCase(s)) {
                            return true;
                        }
                    }
                }
            }
            // Strict grant enforcement: if 'grants' claim is present, do not fall through to unstructured claims
            return false;
        } else if (grantsObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String p = Objects.toString(entry.getKey(), "").trim();
                if (permissionMatches(p, requiredPermission)) {
                    Object val = entry.getValue();
                    if (val instanceof Collection<?> coll) {
                        for (Object elem : coll) {
                            if (targetScope.equalsIgnoreCase(Objects.toString(elem, "").trim())) {
                                return true;
                            }
                        }
                    } else if (targetScope.equalsIgnoreCase(Objects.toString(val, "").trim())) {
                        return true;
                    }
                }
            }
            return false;
        }

        // 2. Fallback for legacy tokens without 'grants' claim:
        // Must ensure caller has authority for the required permission first!
        if (!hasAuthority(jwt, "SCOPE_" + requiredPermission) && !hasAuthority(jwt, requiredPermission)) {
            return false;
        }

        String dataScope = jwt.getClaimAsString("data_scope");
        if (targetScope.equalsIgnoreCase(dataScope)) {
            return true;
        }
        String scope = jwt.getClaimAsString("scope");
        if (scope != null) {
            for (String part : scope.split("\\s+")) {
                if (targetScope.equalsIgnoreCase(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean permissionMatches(String grantPermission, String requiredPermission) {
        if (grantPermission.equalsIgnoreCase(requiredPermission) || "*".equals(grantPermission)) {
            return true;
        }
        if (grantPermission.endsWith(":*")) {
            String prefix = grantPermission.substring(0, grantPermission.length() - 1);
            return requiredPermission.startsWith(prefix);
        }
        return false;
    }

    private List<String> extractAssignedBranches(Jwt jwt) {
        List<String> bld = jwt.getClaimAsStringList("bld");
        if (bld != null && !bld.isEmpty()) {
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
            // For namespaced non-UUID identifiers (e.g. integer IDs or admin usernames),
            // deterministically map to consistent UUID to avoid 500 runtime crashes
            return UUID.nameUUIDFromBytes(("vistay:actor:" + sub).getBytes(StandardCharsets.UTF_8));
        }
    }

    private UUID extractOptionalUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return null;
        }
        return extractUserIdOrThrow(jwt);
    }
}
