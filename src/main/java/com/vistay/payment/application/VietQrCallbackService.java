package com.vistay.payment.application;

import com.vistay.payment.domain.CallbackTransaction;
import com.vistay.payment.domain.PaymentIntentRepository;
import com.vistay.payment.domain.OutboxEvent;
import com.vistay.payment.domain.OutboxEventRepository;
import com.vistay.payment.domain.ProviderTransaction;
import com.vistay.payment.domain.ProviderTransactionRepository;
import com.vistay.payment.domain.ReconciliationItem;
import com.vistay.payment.domain.ReconciliationItemRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VietQrCallbackService {
    private final PaymentIntentRepository intents;
    private final ProviderTransactionRepository transactions;
    private final ReconciliationItemRepository reconciliation;
    private final CallbackSecurityService security;
    private final OutboxEventRepository outbox;

    public VietQrCallbackService(PaymentIntentRepository intents,
                                 ProviderTransactionRepository transactions,
                                 ReconciliationItemRepository reconciliation,
                                 CallbackSecurityService security,
                                 OutboxEventRepository outbox) {
        this.intents = intents;
        this.transactions = transactions;
        this.reconciliation = reconciliation;
        this.security = security;
        this.outbox = outbox;
    }

    @Transactional
    public CallbackResult receive(CallbackTransaction value) {
        validate(value);
        var fingerprint = hash(value);
        var existing = transactions.findByProviderAndReferenceNumber("VIETQR", value.referenceNumber());
        if (existing.isPresent()) return new CallbackResult(true, existing.get().getMatchStatus());
        if (!security.reserveReplayFingerprint(fingerprint)) return new CallbackResult(true, "DUPLICATE");

        var intent = value.orderId() == null ? null
            : intents.findByProviderAndProviderOrderId("VIETQR", value.orderId()).orElse(null);
        String mismatch = mismatch(intent, value);
        if (mismatch == null) {
            intent.confirm(value.transactionTime());
            intents.save(intent);
            var transaction = transactions.save(ProviderTransaction.matched(value, intent, fingerprint));
            outbox.save(OutboxEvent.paymentTransaction(transaction, intent, value, "MATCHED", null));
            return new CallbackResult(false, "MATCHED");
        }
        var transaction = transactions.save(ProviderTransaction.unmatched(value, mismatch, fingerprint));
        reconciliation.save(ReconciliationItem.open(transaction.getId(), reasonCode(mismatch), mismatch));
        outbox.save(OutboxEvent.paymentTransaction(transaction, intent, value, "UNMATCHED", mismatch));
        return new CallbackResult(false, "UNMATCHED");
    }

    private static String mismatch(com.vistay.payment.domain.PaymentIntent intent, CallbackTransaction value) {
        if (intent == null) return "No payment intent matches provider order id";
        if (!"CREDIT".equals(value.transactionType())) return "Only CREDIT transactions can confirm payment";
        if (intent.getAmount().compareTo(value.amount()) != 0) return "Provider amount differs from finance instruction amount";
        if (value.transactionTime().isAfter(Instant.now().plusSeconds(300))) return "Transaction timestamp is in the future";
        return null;
    }

    private static String reasonCode(String mismatch) {
        if (mismatch.startsWith("No payment")) return "ORDER_NOT_FOUND";
        if (mismatch.startsWith("Provider amount")) return "AMOUNT_MISMATCH";
        if (mismatch.startsWith("Only CREDIT")) return "INVALID_DIRECTION";
        return "INVALID_TIMESTAMP";
    }

    private static void validate(CallbackTransaction value) {
        if (value.referenceNumber() == null || value.referenceNumber().isBlank() || value.referenceNumber().length() > 180)
            throw new IllegalArgumentException("referenceNumber is required and limited to 180 characters");
        if (value.amount() == null || value.amount().signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        if (value.transactionTime() == null) throw new IllegalArgumentException("transactionTime is required");
        if (!"CREDIT".equals(value.transactionType()) && !"DEBIT".equals(value.transactionType()))
            throw new IllegalArgumentException("transactionType must be CREDIT or DEBIT");
    }

    private String hash(CallbackTransaction value) {
        return CallbackSecurityService.sha256(String.join("\u001f",
            safe(value.referenceNumber()), safe(value.transactionId()), safe(value.orderId()),
            value.amount().stripTrailingZeros().toPlainString(), safe(value.transactionType()),
            safe(value.content()), value.transactionTime().toString()));
    }

    private static String safe(String value) { return value == null ? "" : value; }

    public record CallbackResult(boolean duplicate, String matchStatus) {}
}
