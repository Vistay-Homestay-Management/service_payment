package com.vistay.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingChargeAdjustmentRepository extends JpaRepository<BookingChargeAdjustment, UUID> {

    List<BookingChargeAdjustment> findByChargeId(UUID chargeId);
}

