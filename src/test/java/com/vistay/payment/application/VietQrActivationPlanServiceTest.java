package com.vistay.payment.application;

import com.vistay.payment.domain.PaymentActivationMode;
import com.vistay.payment.domain.VietQrActivationPlan;
import com.vistay.payment.domain.VietQrActivationPlanRepository;
import com.vistay.payment.domain.VietQrPlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VietQrActivationPlanServiceTest {

    @Mock
    private VietQrActivationPlanRepository planRepository;

    private VietQrActivationPlanService service;

    private final String creatorActor = "ADMIN:101";
    private final String checkerActor = "ADMIN:202";

    @BeforeEach
    void setUp() {
        service = new VietQrActivationPlanService(planRepository);
    }

    @Nested
    @DisplayName("Plan Creation & Validation")
    class CreationTests {

        @Test
        @DisplayName("Create plan successfully with creator identity")
        void createPlan_Success() {
            CreateActivationPlanCommand cmd = new CreateActivationPlanCommand(
                    "PLAN-2026-001",
                    "MERCHANT-01",
                    "ViStay Merchant",
                    "970422",
                    "MB Bank",
                    "0987654321",
                    "VISTAY CORP",
                    PaymentActivationMode.CALLBACK_ENABLED
            );

            when(planRepository.existsByPlanCode("PLAN-2026-001")).thenReturn(false);
            when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(i -> i.getArgument(0));

            VietQrActivationPlanResponse response = service.createPlan(cmd, creatorActor);

            assertThat(response).isNotNull();
            assertThat(response.planCode()).isEqualTo("PLAN-2026-001");
            assertThat(response.merchantCode()).isEqualTo("MERCHANT-01");
            assertThat(response.createdByAdminUserId()).isEqualTo("ADMIN:101");
            assertThat(response.status()).isEqualTo(VietQrPlanStatus.DRAFT);
            assertThat(response.targetActivationMode()).isEqualTo(PaymentActivationMode.CALLBACK_ENABLED);
        }

        @Test
        @DisplayName("Duplicate plan code is rejected (replay/duplicate check)")
        void createPlan_DuplicateCode_ThrowsException() {
            CreateActivationPlanCommand cmd = new CreateActivationPlanCommand(
                    "PLAN-2026-001",
                    "MERCHANT-01",
                    "ViStay Merchant",
                    "970422",
                    "MB Bank",
                    "0987654321",
                    "VISTAY CORP",
                    PaymentActivationMode.GENERATE_ONLY
            );

            when(planRepository.existsByPlanCode("PLAN-2026-001")).thenReturn(true);

            assertThatThrownBy(() -> service.createPlan(cmd, creatorActor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }
    }

    @Nested
    @DisplayName("UAT Evidence & Callback Gates")
    class GateTests {

        @Test
        @DisplayName("Record UAT evidence records timestamp and flag")
        void recordUatEvidence_Success() {
            VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.CALLBACK_ENABLED);
            UUID planId = plan.getId();

            when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
            when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(i -> i.getArgument(0));

            RecordUatEvidenceCommand cmd = new RecordUatEvidenceCommand("UAT-PASS-99", "Verified all payment scenarios");
            VietQrActivationPlanResponse response = service.recordUatEvidence(planId, cmd, creatorActor);

            assertThat(response.uatVerified()).isTrue();
            assertThat(response.uatTestReference()).isEqualTo("UAT-PASS-99");
            assertThat(response.uatVerifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("Configure callback enforces public HTTPS URL")
        void configureCallback_HttpsEnforced() {
            VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.CALLBACK_ENABLED);
            UUID planId = plan.getId();

            when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

            // Non-HTTPS URL must fail
            ConfigureCallbackCommand insecureCmd = new ConfigureCallbackCommand("http://api.vistay.vn/webhook", true);
            assertThatThrownBy(() -> service.configureCallback(planId, insecureCmd, creatorActor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTPS");

            // HTTPS URL must succeed
            when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(i -> i.getArgument(0));
            ConfigureCallbackCommand secureCmd = new ConfigureCallbackCommand("https://api.vistay.vn/webhook", true);
            VietQrActivationPlanResponse response = service.configureCallback(planId, secureCmd, creatorActor);

            assertThat(response.callbackUrl()).isEqualTo("https://api.vistay.vn/webhook");
            assertThat(response.callbackSecretConfigured()).isTrue();
        }
    }

    @Nested
    @DisplayName("Maker-Checker Approval & Callback Activation")
    class MakerCheckerTests {

        @Test
        @DisplayName("Creator cannot approve their own plan (Maker-Checker violation)")
        void approvePlan_CreatorCannotApprove_ThrowsException() {
            VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.GENERATE_ONLY);
            plan.submitForApproval();
            UUID planId = plan.getId();

            when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

            assertThatThrownBy(() -> service.approvePlan(planId, creatorActor))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Maker-Checker violation");
        }

        @Test
        @DisplayName("Cannot approve CALLBACK_ENABLED plan without UAT evidence verified")
        void approvePlan_CallbackEnabled_MissingUat_ThrowsException() {
            VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.CALLBACK_ENABLED);
            plan.configureCallback("https://pay.vistay.vn/callback", true);
            plan.submitForApproval();
            UUID planId = plan.getId();

            when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

            assertThatThrownBy(() -> service.approvePlan(planId, checkerActor))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UAT test evidence has not been verified");
        }

        @Test
        @DisplayName("Cannot approve CALLBACK_ENABLED plan without callback secret configured")
        void approvePlan_CallbackEnabled_MissingSecret_ThrowsException() {
            VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.CALLBACK_ENABLED);
            plan.recordUatEvidence("UAT-01", "Notes");
            plan.configureCallback("https://pay.vistay.vn/callback", false);
            plan.submitForApproval();
            UUID planId = plan.getId();

            when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

            assertThatThrownBy(() -> service.approvePlan(planId, checkerActor))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Callback webhook secret is not configured");
        }

        @Test
        @DisplayName("Checker can approve CALLBACK_ENABLED plan when all gates are met")
        void approvePlan_AllGatesMet_Success() {
            VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.CALLBACK_ENABLED);
            plan.recordUatEvidence("UAT-01", "Notes");
            plan.configureCallback("https://pay.vistay.vn/callback", true);
            plan.submitForApproval();
            UUID planId = plan.getId();

            when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
            when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(i -> i.getArgument(0));

            VietQrActivationPlanResponse response = service.approvePlan(planId, checkerActor);

            assertThat(response.status()).isEqualTo(VietQrPlanStatus.APPROVED);
            assertThat(response.approvedByAdminUserId()).isEqualTo(checkerActor);
            assertThat(response.approvedAt()).isNotNull();
        }

        @Test
        @DisplayName("Cannot activate plan unless it is APPROVED")
        void activatePlan_NotApproved_ThrowsException() {
            VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.GENERATE_ONLY);
            UUID planId = plan.getId();

            when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

            assertThatThrownBy(() -> service.activatePlan(planId, checkerActor))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only APPROVED plans can be activated");
        }

        @Test
        @DisplayName("Activate approved plan successfully")
        void activatePlan_Success() {
            VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.GENERATE_ONLY);
            plan.submitForApproval();
            plan.approve(checkerActor);
            UUID planId = plan.getId();

            when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
            when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(i -> i.getArgument(0));

            VietQrActivationPlanResponse response = service.activatePlan(planId, "ADMIN:303");

            assertThat(response.status()).isEqualTo(VietQrPlanStatus.ACTIVE);
        }

        @Test
        @DisplayName("Creator cannot reject their own plan")
        void rejectPlan_CreatorCannotReject_ThrowsException() {
            VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.GENERATE_ONLY);
            plan.submitForApproval();
            UUID planId = plan.getId();

            when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

            RejectActivationPlanCommand cmd = new RejectActivationPlanCommand("Invalid account");
            assertThatThrownBy(() -> service.rejectPlan(planId, cmd, creatorActor))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Maker-Checker violation");
        }
    }

    private VietQrActivationPlan createSamplePlan(PaymentActivationMode mode) {
        return new VietQrActivationPlan(
                "PLAN-SAMPLE-" + UUID.randomUUID().toString().substring(0, 8),
                "MERCHANT-01",
                "ViStay Merchant",
                "970422",
                "MB Bank",
                "0987654321",
                "VISTAY CORP",
                mode,
                creatorActor
        );
    }
}

