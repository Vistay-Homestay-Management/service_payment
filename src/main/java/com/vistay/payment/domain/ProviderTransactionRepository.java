package com.vistay.payment.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderTransactionRepository extends JpaRepository<ProviderTransaction, UUID> {
    Optional<ProviderTransaction> findByProviderAndReferenceNumber(String provider, String referenceNumber);
}
