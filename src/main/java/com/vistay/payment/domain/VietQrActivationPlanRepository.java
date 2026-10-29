package com.vistay.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VietQrActivationPlanRepository extends JpaRepository<VietQrActivationPlan, UUID> {

    Optional<VietQrActivationPlan> findByPlanCode(String planCode);

    boolean existsByPlanCode(String planCode);

    List<VietQrActivationPlan> findByStatus(VietQrPlanStatus status);

    List<VietQrActivationPlan> findByMerchantCode(String merchantCode);

    Optional<VietQrActivationPlan> findFirstByStatusOrderByUpdatedAtDesc(VietQrPlanStatus status);
}

