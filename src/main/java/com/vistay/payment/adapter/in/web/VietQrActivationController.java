package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.*;
import com.vistay.payment.domain.VietQrPlanStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payment/vietqr/activation-plans")
public class VietQrActivationController {

    private final VietQrActivationPlanService activationPlanService;
    private final BookingChargeAuthorizationService authorizationService;

    public VietQrActivationController(
            VietQrActivationPlanService activationPlanService,
            BookingChargeAuthorizationService authorizationService) {
        this.activationPlanService = activationPlanService;
        this.authorizationService = authorizationService;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:write', 'ROLE_ADMIN')")
    public ResponseEntity<VietQrActivationPlanResponse> createPlan(
            @Valid @RequestBody CreateActivationPlanCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = authorizationService.extractActorId(jwt);
        if (!actorId.equals(command.createdByAdminUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated actor does not match plan creator");
        }
        VietQrActivationPlanResponse response = activationPlanService.createPlan(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/uat")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:write', 'ROLE_ADMIN')")
    public ResponseEntity<VietQrActivationPlanResponse> recordUatEvidence(
            @PathVariable UUID id,
            @Valid @RequestBody RecordUatEvidenceCommand command) {
        return ResponseEntity.ok(activationPlanService.recordUatEvidence(id, command));
    }

    @PostMapping("/{id}/callback")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:write', 'ROLE_ADMIN')")
    public ResponseEntity<VietQrActivationPlanResponse> configureCallback(
            @PathVariable UUID id,
            @Valid @RequestBody ConfigureCallbackCommand command) {
        return ResponseEntity.ok(activationPlanService.configureCallback(id, command));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:write', 'ROLE_ADMIN')")
    public ResponseEntity<VietQrActivationPlanResponse> submitForApproval(
            @PathVariable UUID id,
            @Valid @RequestBody SubmitActivationPlanCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = authorizationService.extractActorId(jwt);
        if (!actorId.equals(command.submittedByAdminUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated actor does not match submitter");
        }
        return ResponseEntity.ok(activationPlanService.submitForApproval(id, command));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:write', 'ROLE_ADMIN')")
    public ResponseEntity<VietQrActivationPlanResponse> approvePlan(
            @PathVariable UUID id,
            @Valid @RequestBody ApproveActivationPlanCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = authorizationService.extractActorId(jwt);
        if (!actorId.equals(command.approvedByAdminUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated actor does not match approver");
        }
        return ResponseEntity.ok(activationPlanService.approvePlan(id, command));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:write', 'ROLE_ADMIN')")
    public ResponseEntity<VietQrActivationPlanResponse> activatePlan(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID operatorId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = authorizationService.extractActorId(jwt);
        if (operatorId != null && !operatorId.equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Supplied operatorId does not match authenticated actor");
        }
        return ResponseEntity.ok(activationPlanService.activatePlan(id, actorId));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:write', 'ROLE_ADMIN')")
    public ResponseEntity<VietQrActivationPlanResponse> rejectPlan(
            @PathVariable UUID id,
            @Valid @RequestBody RejectActivationPlanCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = authorizationService.extractActorId(jwt);
        if (!actorId.equals(command.reviewedByAdminUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated actor does not match reviewer");
        }
        return ResponseEntity.ok(activationPlanService.rejectPlan(id, command));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:write', 'ROLE_ADMIN')")
    public ResponseEntity<VietQrActivationPlanResponse> suspendPlan(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID operatorId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = authorizationService.extractActorId(jwt);
        if (operatorId != null && !operatorId.equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Supplied operatorId does not match authenticated actor");
        }
        return ResponseEntity.ok(activationPlanService.suspendPlan(id, actorId, reason));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:read', 'SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_FINANCE')")
    public ResponseEntity<VietQrActivationPlanResponse> getPlanById(@PathVariable UUID id) {
        return ResponseEntity.ok(activationPlanService.getPlanById(id));
    }

    @GetMapping("/code/{planCode}")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:read', 'SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_FINANCE')")
    public ResponseEntity<VietQrActivationPlanResponse> getPlanByCode(@PathVariable String planCode) {
        return ResponseEntity.ok(activationPlanService.getPlanByCode(planCode));
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:read', 'SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_FINANCE')")
    public ResponseEntity<VietQrActivationPlanResponse> getActivePlan() {
        return activationPlanService.getActivePlan()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SCOPE_payment:activation:read', 'SCOPE_payment:read', 'ROLE_ADMIN', 'ROLE_FINANCE')")
    public ResponseEntity<List<VietQrActivationPlanResponse>> listPlans(
            @RequestParam(required = false) VietQrPlanStatus status) {
        return ResponseEntity.ok(activationPlanService.listPlans(status));
    }
}
