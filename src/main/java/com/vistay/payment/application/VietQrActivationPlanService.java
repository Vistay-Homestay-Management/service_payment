package com.vistay.payment.application;

import com.vistay.payment.domain.VietQrActivationPlan;
import com.vistay.payment.domain.VietQrActivationPlanRepository;
import com.vistay.payment.domain.VietQrPlanStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class VietQrActivationPlanService {

    private final VietQrActivationPlanRepository planRepository;

    public VietQrActivationPlanService(VietQrActivationPlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public VietQrActivationPlanResponse createPlan(CreateActivationPlanCommand cmd, String creatorActorId) {
        if (planRepository.existsByPlanCode(cmd.planCode())) {
            throw new IllegalArgumentException("Activation plan code already exists: " + cmd.planCode());
        }

        VietQrActivationPlan plan = new VietQrActivationPlan(
                cmd.planCode(),
                cmd.merchantCode(),
                cmd.merchantName(),
                cmd.bankBin(),
                cmd.bankName(),
                cmd.bankAccountNumber(),
                cmd.bankAccountName(),
                cmd.targetActivationMode(),
                creatorActorId
        );

        return VietQrActivationPlanResponse.from(planRepository.save(plan));
    }

    public VietQrActivationPlanResponse recordUatEvidence(UUID planId, RecordUatEvidenceCommand cmd, String actorId) {
        VietQrActivationPlan plan = findPlanOrThrow(planId);
        plan.recordUatEvidence(cmd.testReference(), cmd.notes());
        return VietQrActivationPlanResponse.from(planRepository.save(plan));
    }

    public VietQrActivationPlanResponse configureCallback(UUID planId, ConfigureCallbackCommand cmd, String actorId) {
        VietQrActivationPlan plan = findPlanOrThrow(planId);
        plan.configureCallback(cmd.callbackUrl(), cmd.secretConfigured());
        return VietQrActivationPlanResponse.from(planRepository.save(plan));
    }

    public VietQrActivationPlanResponse submitForApproval(UUID planId, String actorId) {
        VietQrActivationPlan plan = findPlanOrThrow(planId);
        plan.submitForApproval();
        return VietQrActivationPlanResponse.from(planRepository.save(plan));
    }

    public VietQrActivationPlanResponse approvePlan(UUID planId, String approverActorId) {
        VietQrActivationPlan plan = findPlanOrThrow(planId);
        plan.approve(approverActorId);
        return VietQrActivationPlanResponse.from(planRepository.save(plan));
    }

    public VietQrActivationPlanResponse activatePlan(UUID planId, String operatorActorId) {
        VietQrActivationPlan plan = findPlanOrThrow(planId);
        plan.activate(operatorActorId);
        return VietQrActivationPlanResponse.from(planRepository.save(plan));
    }

    public VietQrActivationPlanResponse rejectPlan(UUID planId, RejectActivationPlanCommand cmd, String reviewerActorId) {
        VietQrActivationPlan plan = findPlanOrThrow(planId);
        plan.reject(reviewerActorId, cmd.reason());
        return VietQrActivationPlanResponse.from(planRepository.save(plan));
    }

    public VietQrActivationPlanResponse suspendPlan(UUID planId, SuspendActivationPlanCommand cmd, String operatorActorId) {
        VietQrActivationPlan plan = findPlanOrThrow(planId);
        String reason = cmd != null ? cmd.reason() : null;
        plan.suspend(operatorActorId, reason);
        return VietQrActivationPlanResponse.from(planRepository.save(plan));
    }

    @Transactional(readOnly = true)
    public VietQrActivationPlanResponse getPlanById(UUID id) {
        return VietQrActivationPlanResponse.from(findPlanOrThrow(id));
    }

    @Transactional(readOnly = true)
    public VietQrActivationPlanResponse getPlanByCode(String planCode) {
        return planRepository.findByPlanCode(planCode)
                .map(VietQrActivationPlanResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Plan not found for code: " + planCode));
    }

    @Transactional(readOnly = true)
    public Optional<VietQrActivationPlanResponse> getActivePlan() {
        return planRepository.findFirstByStatusOrderByUpdatedAtDesc(VietQrPlanStatus.ACTIVE)
                .map(VietQrActivationPlanResponse::from);
    }

    @Transactional(readOnly = true)
    public List<VietQrActivationPlanResponse> listPlans(VietQrPlanStatus status) {
        List<VietQrActivationPlan> list = (status != null)
                ? planRepository.findByStatus(status)
                : planRepository.findAll();
        return list.stream().map(VietQrActivationPlanResponse::from).toList();
    }

    private VietQrActivationPlan findPlanOrThrow(UUID id) {
        return planRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("VietQR activation plan not found with id: " + id));
    }
}

