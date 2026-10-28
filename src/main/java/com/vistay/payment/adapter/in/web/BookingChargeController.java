package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<BookingChargeResponse> createCharge(
            @Valid @RequestBody CreateBookingChargeCommand command) {
        BookingChargeResponse response = bookingChargeService.createCharge(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/apply-payment")
    public ResponseEntity<BookingChargeResponse> applyPayment(
            @PathVariable UUID id,
            @Valid @RequestBody ApplyPaymentToChargeCommand command) {
        return ResponseEntity.ok(bookingChargeService.applyPayment(id, command));
    }

    @PostMapping("/{id}/waive")
    public ResponseEntity<BookingChargeResponse> waiveCharge(
            @PathVariable UUID id,
            @Valid @RequestBody WaiveBookingChargeCommand command) {
        return ResponseEntity.ok(bookingChargeService.waiveCharge(id, command));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<BookingChargeResponse> cancelCharge(
            @PathVariable UUID id,
            @Valid @RequestBody CancelBookingChargeCommand command) {
        return ResponseEntity.ok(bookingChargeService.cancelCharge(id, command));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingChargeResponse> getChargeById(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingChargeService.getChargeById(id));
    }

    @GetMapping("/code/{chargeCode}")
    public ResponseEntity<BookingChargeResponse> getChargeByCode(@PathVariable String chargeCode) {
        return ResponseEntity.ok(bookingChargeService.getChargeByCode(chargeCode));
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<List<BookingChargeResponse>> getChargesByBooking(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingChargeService.getChargesByBooking(bookingId));
    }

    @GetMapping("/booking/{bookingId}/open-balance")
    public ResponseEntity<BookingOpenBalanceSummaryResponse> getBookingOpenBalance(
            @PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingChargeService.getBookingOpenBalanceSummary(bookingId));
    }
}
