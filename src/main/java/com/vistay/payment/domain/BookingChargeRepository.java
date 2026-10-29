package com.vistay.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingChargeRepository extends JpaRepository<BookingCharge, UUID> {

    Optional<BookingCharge> findByChargeCode(String chargeCode);

    Optional<BookingCharge> findByIdempotencyKey(String idempotencyKey);

    Optional<BookingCharge> findByFinancePaymentRequestId(UUID financePaymentRequestId);

    boolean existsByChargeCode(String chargeCode);

    boolean existsByFinancePaymentRequestId(UUID financePaymentRequestId);

    List<BookingCharge> findByBookingId(UUID bookingId);

    List<BookingCharge> findByBookingIdAndStatus(UUID bookingId, ChargeStatus status);

    @Query("SELECT COALESCE(SUM(b.outstandingBalance), 0) FROM BookingCharge b " +
           "WHERE b.bookingId = :bookingId " +
           "AND b.status IN ('OPEN', 'PARTIALLY_PAID')")
    BigDecimal calculateTotalOpenBalanceForBooking(@Param("bookingId") UUID bookingId);
}

