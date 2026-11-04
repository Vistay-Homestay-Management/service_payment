package com.vistay.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingChargeAllocationRepository extends JpaRepository<BookingChargeAllocation, UUID> {

    Optional<BookingChargeAllocation> findByFinanceAllocationId(UUID financeAllocationId);

    boolean existsByFinanceAllocationId(UUID financeAllocationId);

    List<BookingChargeAllocation> findByChargeId(UUID chargeId);

    List<BookingChargeAllocation> findByPaymentTransactionId(UUID paymentTransactionId);
}

