package com.vistay.payment.application;

import com.vistay.payment.domain.PaymentActivationMode;
import com.vistay.payment.domain.PaymentIntent;
import com.vistay.payment.domain.PaymentIntentRepository;
import com.vistay.payment.domain.VietQrPresentation;
import com.vistay.payment.domain.VietQrPresentationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentIntentService {
    private final PaymentIntentRepository intents;
    private final VietQrPresentationRepository presentations;
    private final FinancePaymentInstructionClient finance;
    private final VietQrClient vietQr;
    private final PaymentActivationMode activationMode;

    public PaymentIntentService(PaymentIntentRepository intents,
                                VietQrPresentationRepository presentations,
                                FinancePaymentInstructionClient finance,
                                VietQrClient vietQr,
                                @Value("${vistay.payment.activation-mode:DISABLED}") PaymentActivationMode activationMode) {
        this.intents = intents;
        this.presentations = presentations;
        this.finance = finance;
        this.vietQr = vietQr;
        this.activationMode = activationMode;
    }

    @Transactional
    public PaymentIntentView create(UUID financeRequestId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        var existing = intents.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getFinancePaymentRequestId().equals(financeRequestId)) {
                throw new IllegalArgumentException("Idempotency key belongs to another finance request");
            }
            return view(existing.get());
        }
        if (activationMode == PaymentActivationMode.DISABLED) {
            throw new IllegalStateException("Payment activation mode is DISABLED");
        }
        var instruction = finance.get(financeRequestId);
        if (instruction.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Finance payment instruction is expired");
        }
        var intent = PaymentIntent.create(
            instruction.financePaymentRequestId(), instruction.bookingId(), instruction.customerId(),
            instruction.amount(), instruction.currency(), instruction.purpose(), instruction.payerReference(),
            instruction.expiresAt(), idempotencyKey, activationMode);
        try {
            intents.saveAndFlush(intent);
        } catch (DataIntegrityViolationException race) {
            return intents.findByIdempotencyKey(idempotencyKey).map(this::view).orElseThrow(() -> race);
        }
        var qr = vietQr.generate(intent.getAmount(), intent.getProviderOrderId());
        var presentation = presentations.save(VietQrPresentation.create(
            intent.getId(), qr.providerRequestId(), qr.qrPayload(), qr.qrImageDataUri(),
            qr.maskedAccount(), intent.getProviderOrderId(), intent.getExpiresAt()));
        intent.markQrReady();
        intents.save(intent);
        return PaymentIntentView.from(intent, presentation);
    }

    @Transactional(readOnly = true)
    public PaymentIntentView get(UUID id) {
        return view(intents.findById(id).orElseThrow(() -> new IllegalArgumentException("Payment intent not found")));
    }

    @Transactional(readOnly = true)
    public PaymentIntentAccess access(UUID id) {
        var intent = intents.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Payment intent not found"));
        return new PaymentIntentAccess(intent.getBookingId(), intent.getCustomerId());
    }

    @Transactional(readOnly = true)
    public PaymentIntentAccess accessForCreate(UUID financeRequestId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        var existing = intents.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getFinancePaymentRequestId().equals(financeRequestId)) {
                throw new IllegalArgumentException("Idempotency key belongs to another finance request");
            }
            return new PaymentIntentAccess(
                existing.get().getBookingId(), existing.get().getCustomerId());
        }
        var instruction = finance.get(financeRequestId);
        return new PaymentIntentAccess(instruction.bookingId(), instruction.customerId());
    }

    @Transactional
    public PaymentIntentView cancel(UUID id, long expectedVersion, String reason) {
        var intent = intents.findById(id).orElseThrow(() -> new IllegalArgumentException("Payment intent not found"));
        intent.cancel(reason, expectedVersion);
        return view(intents.save(intent));
    }

    private PaymentIntentView view(PaymentIntent intent) {
        var qr = presentations.findFirstByPaymentIntentIdAndSupersededAtIsNull(intent.getId()).orElse(null);
        return PaymentIntentView.from(intent, qr);
    }

    private static void validateIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("Idempotency key must contain 1-160 characters");
        }
    }
}
