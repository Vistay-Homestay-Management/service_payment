package com.vistay.payment.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Dedicated authorization component for payment collection orders and open balance projections.
 * Strictly enforces ViStay S4 specification: (permission, scope) grant isolation.
 * Prohibits role-based data scope bypass, removes synthetic UUID synthesis, and prevents IDOR.
 */
@Service
public class BookingChargeAuthorizationService {

    private final BookingBranchResolver bookingBranchResolver;

    public BookingChargeAuthorizationService(
            @Autowired(required = false) BookingBranchResolver bookingBranchResolver) {
        this.bookingBranchResolver = bookingBranchResolver;
    }

    public UUID extractActorId(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or unauthenticated security principal");
        }

        // 1. Check authoritative identity claims
        for (String claimName : List.of("admin_user_id", "user_id", "account_id", "profile_id")) {
            String val = jwt.getClaimAsString(claimName);
            if (val != null && !val.isBlank()) {
                try {
                    return UUID.fromString(val.trim());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // 2. Check token subject (supporting namespaced format like ADMIN:<uuid> or STAFF:<uuid>)
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token subject is missing or blank");
        }
        sub = sub.trim();
        if (sub.contains(":")) {
            sub = sub.substring(sub.lastIndexOf(':') + 1);
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            // Fail-closed: Never synthesize fake UUIDs. Canonical identity must be a valid UUID.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Principal subject does not contain a canonical UUID identity: " + jwt.getSubject());
        }
    }

    public UUID extractOptionalActorId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return null;
        }
        try {
            return extractActorId(jwt);
        } catch (ResponseStatusException e) {
            return null;
        }
    }

    public UUID extractCustomerId(Jwt jwt) {
        String customerIdClaim = jwt.getClaimAsString("customer_id");
        if (customerIdClaim != null && !customerIdClaim.isBlank()) {
            try {
                return UUID.fromString(customerIdClaim.trim());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return extractActorId(jwt);
    }

    public String extractCorrelationId(Jwt jwt) {
        String mdc = org.slf4j.MDC.get("correlation_id");
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        if (jwt != null) {
            for (String claim : List.of("correlation_id", "cid", "trace_id")) {
                String val = jwt.getClaimAsString(claim);
                if (val != null && !val.isBlank()) {
                    return val.trim();
                }
            }
        }
        return UUID.randomUUID().toString();
    }

    public List<String> extractAssignedBranches(Jwt jwt) {
        List<String> bld = jwt.getClaimAsStringList("bld");
        if (bld != null && !bld.isEmpty()) {
            return bld;
        }
        List<String> branches = jwt.getClaimAsStringList("branches");
        return branches != null ? branches : Collections.emptyList();
    }

    public boolean hasDataScope(Jwt jwt, String requiredPermission, String targetScope) {
        if (jwt == null) {
            return false;
        }

        // Strictly check claim 'grants' as List<Map<String, Object>> (S4 ViStay specification)
        Object grantsObj = jwt.getClaims().get("grants");
        if (grantsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object pObj = map.get("p");
                    if (pObj == null) {
                        pObj = map.get("permission");
                    }
                    String p = Objects.toString(pObj, "").trim();

                    // Strict exact match: No wildcards allowed unless explicitly defined in platform contracts
                    if (requiredPermission.equalsIgnoreCase(p)) {
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
            return false;
        } else if (grantsObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String p = Objects.toString(entry.getKey(), "").trim();
                if (requiredPermission.equalsIgnoreCase(p)) {
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

        // Strict zero-trust: If grants claim is missing, fail-closed. No fallback to unstructured scopes.
        return false;
    }

    public void assertReadDataScope(BookingChargeResponse charge, Jwt jwt) {
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

        // Machine-to-machine internal service token
        if (hasAuthority(jwt, "SCOPE_internal:finance")) {
            return;
        }

        // 2. Staff Data Scope Evaluation: Must possess payment:read with matching data scope.
        // Role alone (including ROLE_ADMIN) does not bypass explicit grant checking.
        if (hasDataScope(jwt, "payment:read", "ALL")) {
            return;
        }

        UUID staffId = extractActorId(jwt);

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

    public void assertCreateDataScope(UUID branchId, UUID bookingId, UUID actorId, Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated security context");
        }
        if (hasAuthority(jwt, "SCOPE_internal:finance")) {
            return;
        }

        if (hasDataScope(jwt, "payment:write", "ALL")) {
            return;
        }

        if (hasDataScope(jwt, "payment:write", "BRANCH")) {
            if (branchId == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff with BRANCH scope cannot create charges without an assigned branch");
            }
            List<String> assignedBranches = extractAssignedBranches(jwt);
            if (!assignedBranches.contains(branchId.toString())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff is not authorized to modify charges for branch " + branchId);
            }
            return;
        }

        if (hasDataScope(jwt, "payment:write", "OWN")) {
            if (bookingBranchResolver == null || !bookingBranchResolver.isBookingOwnedBy(bookingId, actorId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff with OWN scope cannot create charges for a booking owned by another actor");
            }
            return;
        }

        if (hasDataScope(jwt, "payment:write", "ASSIGNED")) {
            if (bookingBranchResolver == null || !bookingBranchResolver.isBookingAssignedTo(bookingId, actorId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff with ASSIGNED scope is not assigned to booking " + bookingId);
            }
            return;
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Access denied: Missing valid write data scope (must have ALL, BRANCH, OWN, or ASSIGNED scope for payment:write)");
    }

    public void assertWriteDataScope(UUID branchId, UUID bookingId, UUID createdByAdminUserId, UUID actorId, Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated security context");
        }
        if (hasAuthority(jwt, "SCOPE_internal:finance")) {
            return;
        }

        // Must possess payment:write with matching data scope
        if (hasDataScope(jwt, "payment:write", "ALL")) {
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

    public void assertBookingAccess(UUID bookingId, Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated security context");
        }
        if (hasAuthority(jwt, "SCOPE_internal:finance")) {
            return;
        }
        if (hasDataScope(jwt, "payment:read", "ALL")) {
            return;
        }

        boolean isCustomer = hasRole(jwt, "ROLE_USER");
        if (isCustomer && !isStaffCaller()) {
            UUID customerId = extractCustomerId(jwt);
            if (bookingBranchResolver == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Authoritative booking resolver unavailable: fail-closed");
            }
            Optional<UUID> bookingCustomer = bookingBranchResolver.resolveCustomerId(bookingId);
            if (bookingCustomer.isEmpty() || !bookingCustomer.get().equals(customerId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Customer cannot access bookings belonging to another customer");
            }
            return;
        }

        UUID staffId = extractActorId(jwt);

        if (hasDataScope(jwt, "payment:read", "BRANCH")) {
            if (bookingBranchResolver == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Authoritative branch resolver unavailable");
            }
            Optional<UUID> branchOpt = bookingBranchResolver.resolveBranchId(bookingId);
            if (branchOpt.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authoritative booking branch cannot be resolved: " + bookingId);
            }
            List<String> assignedBranches = extractAssignedBranches(jwt);
            if (!assignedBranches.contains(branchOpt.get().toString())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Staff is not authorized for booking branch");
            }
            return;
        }

        if (hasDataScope(jwt, "payment:read", "ASSIGNED")) {
            if (bookingBranchResolver == null || !bookingBranchResolver.isBookingAssignedTo(bookingId, staffId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Staff is not assigned to booking " + bookingId);
            }
            return;
        }

        if (hasDataScope(jwt, "payment:read", "OWN")) {
            if (bookingBranchResolver == null || !bookingBranchResolver.isBookingOwnedBy(bookingId, staffId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: Staff with OWN scope is not the owner of booking " + bookingId);
            }
            return;
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Missing valid data scope for booking");
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
}

