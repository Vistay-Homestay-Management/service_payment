package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.*;
import com.vistay.payment.domain.VietQrPlanStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payment/vietqr/activation-plans")
public class VietQrActivationController {

    private final VietQrActivationPlanService activationPlanService;

    public VietQrActivationController(VietQrActivationPlanService activationPlanService) {
        this.activationPlanService = activationPlanService;
    }

    @PostMapping
    public ResponseEntity<VietQrActivationPlanResponse> createPlan(
            @Valid @RequestBody CreateActivationPlanCommand command) {
        VietQrActivationPlanResponse response = activationPlanService.createPlan(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/uat")
    public ResponseEntity<VietQrActivationPlanResponse> recordUatEvidence(
            @PathVariable UUID id,
            @Valid @RequestBody RecordUatEvidenceCommand command) {
        return ResponseEntity.ok(activationPlanService.recordUatEvidence(id, command));
    }

    @PostMapping("/{id}/callback")
    public ResponseEntity<VietQrActivationPlanResponse> configureCallback(
            @PathVariable UUID id,
            @Valid @RequestBody ConfigureCallbackCommand command) {
        return ResponseEntity.ok(activationPlanService.configureCallback(id, command));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<VietQrActivationPlanResponse> submitForApproval(
            @PathVariable UUID id,
            @Valid @RequestBody SubmitActivationPlanCommand command) {
        return ResponseEntity.ok(activationPlanService.submitForApproval(id, command));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<VietQrActivationPlanResponse> approvePlan(
            @PathVariable UUID id,
            @Valid @RequestBody ApproveActivationPlanCommand command) {
        return ResponseEntity.ok(activationPlanService.approvePlan(id, command));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<VietQrActivationPlanResponse> activatePlan(
            @PathVariable UUID id,
            @RequestParam UUID operatorId) {
        return ResponseEntity.ok(activationPlanService.activatePlan(id, operatorId));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<VietQrActivationPlanResponse> rejectPlan(
            @PathVariable UUID id,
            @Valid @RequestBody RejectActivationPlanCommand command) {
        return ResponseEntity.ok(activationPlanService.rejectPlan(id, command));
    }

    @PostMapping("/{id}/suspend")
    public ResponseEntity<VietQrActivationPlanResponse> suspendPlan(
            @PathVariable UUID id,
            @RequestParam UUID operatorId,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(activationPlanService.suspendPlan(id, operatorId, reason));
    }

    @GetMapping("/{id}")
    public ResponseEntity<VietQrActivationPlanResponse> getPlanById(@PathVariable UUID id) {
        return ResponseEntity.ok(activationPlanService.getPlanById(id));
    }

    @GetMapping("/code/{planCode}")
    public ResponseEntity<VietQrActivationPlanResponse> getPlanByCode(@PathVariable String planCode) {
        return ResponseEntity.ok(activationPlanService.getPlanByCode(planCode));
    }

    @GetMapping("/active")
    public ResponseEntity<VietQrActivationPlanResponse> getActivePlan() {
        return activationPlanService.getActivePlan()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<VietQrActivationPlanResponse>> listPlans(
            @RequestParam(required = false) VietQrPlanStatus status) {
        return ResponseEntity.ok(activationPlanService.listPlans(status));
    }
}

