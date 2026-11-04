package com.vistay.payment.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VietQrPresentationRepository extends JpaRepository<VietQrPresentation, UUID> {
    Optional<VietQrPresentation> findFirstByPaymentIntentIdAndSupersededAtIsNull(UUID paymentIntentId);
}
