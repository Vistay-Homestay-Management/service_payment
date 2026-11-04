package com.vistay.payment.application;

import java.util.UUID;

/** Authoritative record relationships used only for Payment Intent authorization. */
public record PaymentIntentAccess(UUID bookingId, UUID customerId) {}
