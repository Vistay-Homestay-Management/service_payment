package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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
        UUID actorId = extractUserId(jwt);
        BookingChargeResponse response = bookingChargeService.createCharge(command, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/apply-payment")
    @PreAuthorize("hasAnyAuthority('SCOPE_internal:finance', 'ROLE_SYSTEM')")
    public ResponseEntity<BookingChargeResponse> applyPayment(
            @PathVariable UUID id,
            @Valid @RequestBody ApplyPaymentToChargeCommand command) {
        return ResponseEntity.ok(bookingChargeService.applyPayment(id, command));
    }

    @PostMapping("/{id}/waive")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:write', 'ROLE_ADMIN', 'ROLE_FINANCE')")
    public ResponseEntity<BookingChargeResponse> waiveCharge(
            @PathVariable UUID id,
            @Valid @RequestBody WaiveBookingChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = extractUserId(jwt);
        return ResponseEntity.ok(bookingChargeService.waiveCharge(id, command, actorId));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:write', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE')")
    public ResponseEntity<BookingChargeResponse> cancelCharge(
            @PathVariable UUID id,
            @Valid @RequestBody CancelBookingChargeCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = extractUserId(jwt);
        return ResponseEntity.ok(bookingChargeService.cancelCharge(id, command, actorId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE', 'ROLE_USER')")
    public ResponseEntity<BookingChargeResponse> getChargeById(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingChargeService.getChargeById(id));
    }

    @GetMapping("/code/{chargeCode}")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE', 'ROLE_USER')")
    public ResponseEntity<BookingChargeResponse> getChargeByCode(@PathVariable String chargeCode) {
        return ResponseEntity.ok(bookingChargeService.getChargeByCode(chargeCode));
    }

    @GetMapping("/booking/{bookingId}")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE', 'ROLE_USER')")
    public ResponseEntity<List<BookingChargeResponse>> getChargesByBooking(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingChargeService.getChargesByBooking(bookingId));
    }

    @GetMapping("/booking/{bookingId}/open-balance")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_FINANCE', 'ROLE_USER')")
    public ResponseEntity<BookingOpenBalanceSummaryResponse> getBookingOpenBalance(
            @PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingChargeService.getBookingOpenBalanceSummary(bookingId));
    }

    private UUID extractUserId(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return null;
        }
    }
}
