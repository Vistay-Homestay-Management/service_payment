package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.*;
import com.vistay.payment.domain.VietQrPlanStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static com.vistay.payment.adapter.in.web.VietQrActivationSecurity.*;

@RestController
@RequestMapping("/api/v1/payment/vietqr/activation-plans")
public class VietQrActivationController {

    private final VietQrActivationPlanService activationPlanService;

    public VietQrActivationController(VietQrActivationPlanService activationPlanService) {
        this.activationPlanService = activationPlanService;
    }

    @PostMapping
    public ResponseEntity<VietQrActivationPlanResponse> createPlan(
            @Valid @RequestBody CreateActivationPlanCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_CREATE);
        String actorId = extractActorIdentity(jwt);
        VietQrActivationPlanResponse response = activationPlanService.createPlan(command, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/uat")
    public ResponseEntity<VietQrActivationPlanResponse> recordUatEvidence(
            @PathVariable UUID id,
            @Valid @RequestBody RecordUatEvidenceCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_UAT);
        String actorId = extractActorIdentity(jwt);
        return ResponseEntity.ok(activationPlanService.recordUatEvidence(id, command, actorId));
    }

    @PostMapping("/{id}/callback")
    public ResponseEntity<VietQrActivationPlanResponse> configureCallback(
            @PathVariable UUID id,
            @Valid @RequestBody ConfigureCallbackCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_CALLBACK);
        String actorId = extractActorIdentity(jwt);
        return ResponseEntity.ok(activationPlanService.configureCallback(id, command, actorId));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<VietQrActivationPlanResponse> submitForApproval(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_SUBMIT);
        String actorId = extractActorIdentity(jwt);
        return ResponseEntity.ok(activationPlanService.submitForApproval(id, actorId));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<VietQrActivationPlanResponse> approvePlan(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_APPROVE);
        String actorId = extractActorIdentity(jwt);
        try {
            return ResponseEntity.ok(activationPlanService.approvePlan(id, actorId));
        } catch (IllegalStateException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
        }
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<VietQrActivationPlanResponse> activatePlan(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_ACTIVATE);
        String actorId = extractActorIdentity(jwt);
        try {
            return ResponseEntity.ok(activationPlanService.activatePlan(id, actorId));
        } catch (IllegalStateException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<VietQrActivationPlanResponse> rejectPlan(
            @PathVariable UUID id,
            @Valid @RequestBody RejectActivationPlanCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_APPROVE);
        String actorId = extractActorIdentity(jwt);
        try {
            return ResponseEntity.ok(activationPlanService.rejectPlan(id, command, actorId));
        } catch (IllegalStateException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
        }
    }

    @PostMapping("/{id}/suspend")
    public ResponseEntity<VietQrActivationPlanResponse> suspendPlan(
            @PathVariable UUID id,
            @RequestBody(required = false) SuspendActivationPlanCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_SUSPEND);
        String actorId = extractActorIdentity(jwt);
        try {
            return ResponseEntity.ok(activationPlanService.suspendPlan(id, command, actorId));
        } catch (IllegalStateException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<VietQrActivationPlanResponse> getPlanById(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_READ);
        return ResponseEntity.ok(activationPlanService.getPlanById(id));
    }

    @GetMapping("/code/{planCode}")
    public ResponseEntity<VietQrActivationPlanResponse> getPlanByCode(
            @PathVariable String planCode,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_READ);
        return ResponseEntity.ok(activationPlanService.getPlanByCode(planCode));
    }

    @GetMapping("/active")
    public ResponseEntity<VietQrActivationPlanResponse> getActivePlan(
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_READ);
        return activationPlanService.getActivePlan()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<VietQrActivationPlanResponse>> listPlans(
            @RequestParam(required = false) VietQrPlanStatus status,
            @AuthenticationPrincipal Jwt jwt) {
        assertAuthenticatedAndHasGrant(jwt, PERM_PLAN_READ);
        return ResponseEntity.ok(activationPlanService.listPlans(status));
    }
}
