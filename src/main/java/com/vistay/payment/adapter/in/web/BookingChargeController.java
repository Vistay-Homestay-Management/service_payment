package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payment/booking-charges")
public class BookingChargeController {

    private final BookingChargeService bookingChargeService;
    private final BookingBranchResolver bookingBranchResolver;
    private final BookingChargeAuthorizationService authorizationService;

    public BookingChargeController(
            BookingChargeService bookingChargeService,
            @Autowired(required = false) BookingBranchResolver bookingBranchResolver,
            BookingChargeAuthorizationService authorizationService) {
        this.bookingChargeService = bookingChargeService;
        this.bookingBranchResolver = bookingBranchResolver;
        this.authorizationService = authorizationService;
    }

    @PostMapping
    public ResponseEntity<BookingChargeResponse> createCharge(
            @Valid @RequestBody CreateBookingChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = authorizationService.requireActorId(jwt, "payment:create");

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
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Authoritative booking branch cannot be resolved for booking: " + command.bookingId());
            }
        } else {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Authoritative booking branch resolver is not available: fail-closed");
        }

        authorizationService.assertCreateDataScope(
                resolvedBranchId, command.bookingId(), actorId, jwt, "payment:create");
        BookingChargeResponse response = bookingChargeService.createCharge(command, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/apply-payment")
    @PreAuthorize("hasAnyAuthority('SCOPE_internal:finance', 'ROLE_SYSTEM')")
    public ResponseEntity<BookingChargeResponse> applyPayment(
            @PathVariable UUID id,
            @Valid @RequestBody ApplyPaymentToChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = authorizationService.extractOptionalActorId(jwt);
        return ResponseEntity.ok(bookingChargeService.applyPayment(id, command, actorId));
    }

    @PostMapping("/{id}/waive")
    public ResponseEntity<BookingChargeResponse> waiveCharge(
            @PathVariable UUID id,
            @Valid @RequestBody WaiveBookingChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = authorizationService.requireActorId(jwt, "payment:reconcile");
        BookingChargeResponse charge = bookingChargeService.getChargeById(id);
        authorizationService.assertWriteDataScope(charge.branchId(), charge.bookingId(),
                charge.createdByAdminUserId(), actorId, jwt, "payment:reconcile");
        return ResponseEntity.ok(bookingChargeService.waiveCharge(id, command, actorId));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<BookingChargeResponse> cancelCharge(
            @PathVariable UUID id,
            @Valid @RequestBody CancelBookingChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = authorizationService.requireActorId(jwt, "payment:cancel");
        BookingChargeResponse charge = bookingChargeService.getChargeById(id);
        authorizationService.assertWriteDataScope(charge.branchId(), charge.bookingId(),
                charge.createdByAdminUserId(), actorId, jwt, "payment:cancel");
        return ResponseEntity.ok(bookingChargeService.cancelCharge(id, command, actorId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingChargeResponse> getChargeById(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        authorizationService.requirePermission(jwt, "payment:read");
        BookingChargeResponse response = bookingChargeService.getChargeById(id);
        authorizationService.assertReadDataScope(response, jwt);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/code/{chargeCode}")
    public ResponseEntity<BookingChargeResponse> getChargeByCode(
            @PathVariable String chargeCode,
            @AuthenticationPrincipal Jwt jwt) {
        authorizationService.requirePermission(jwt, "payment:read");
        BookingChargeResponse response = bookingChargeService.getChargeByCode(chargeCode);
        authorizationService.assertReadDataScope(response, jwt);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<List<BookingChargeResponse>> getChargesByBooking(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal Jwt jwt) {
        authorizationService.assertBookingAccess(bookingId, jwt);
        List<BookingChargeResponse> charges = bookingChargeService.getChargesByBooking(bookingId);
        for (BookingChargeResponse charge : charges) {
            authorizationService.assertReadDataScope(charge, jwt);
        }
        return ResponseEntity.ok(charges);
    }

    @GetMapping("/booking/{bookingId}/open-balance")
    public ResponseEntity<BookingOpenBalanceSummaryResponse> getBookingOpenBalance(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal Jwt jwt) {
        authorizationService.assertBookingAccess(bookingId, jwt);
        BookingOpenBalanceSummaryResponse summary = bookingChargeService.getBookingOpenBalanceSummary(bookingId);
        for (BookingChargeResponse charge : summary.charges()) {
            authorizationService.assertReadDataScope(charge, jwt);
        }
        return ResponseEntity.ok(summary);
    }
}
