package com.vistay.payment.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BookingOpenBalanceSummaryResponse(
        UUID bookingId,
        BigDecimal totalOriginalCharges,
        BigDecimal totalPaidAmount,
        BigDecimal totalWaivedAmount,
        BigDecimal totalVoidedAmount,
        BigDecimal totalOpenBalance,
        String currency,
        int activeChargeCount,
        List<BookingChargeResponse> charges
) {
}
