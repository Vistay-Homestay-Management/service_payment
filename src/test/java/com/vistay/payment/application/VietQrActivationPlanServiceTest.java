package com.vistay.payment.application;

import com.vistay.payment.domain.PaymentActivationMode;
import com.vistay.payment.domain.VietQrActivationPlan;
import com.vistay.payment.domain.VietQrActivationPlanRepository;
import com.vistay.payment.domain.VietQrPlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private final UUID makerAdminId = UUID.randomUUID();
    private final UUID checkerAdminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VietQrActivationPlanService(planRepository);
    }

    @Test
    @DisplayName("SCRUM-319: Tạo kế hoạch kích hoạt VietQR merchant & tài khoản ngân hàng ở trạng thái DRAFT")
    void createPlan_Success() {
        CreateActivationPlanCommand cmd = new CreateActivationPlanCommand(
                "PLAN-VIETQR-MB-001",
                "MERCHANT-VISTAY-MAIN",
                "Công ty TNHH Vistay Homestay",
                "970422",
                "MB Bank",
                "0987654321",
                "CONG TY TNHH VISTAY",
                PaymentActivationMode.GENERATE_ONLY,
                makerAdminId
        );

        when(planRepository.existsByPlanCode("PLAN-VIETQR-MB-001")).thenReturn(false);
        when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        VietQrActivationPlanResponse response = service.createPlan(cmd);

        assertThat(response).isNotNull();
        assertThat(response.planCode()).isEqualTo("PLAN-VIETQR-MB-001");
        assertThat(response.merchantCode()).isEqualTo("MERCHANT-VISTAY-MAIN");
        assertThat(response.bankBin()).isEqualTo("970422");
        assertThat(response.status()).isEqualTo(VietQrPlanStatus.DRAFT);
        assertThat(response.targetActivationMode()).isEqualTo(PaymentActivationMode.GENERATE_ONLY);
        assertThat(response.uatVerified()).isFalse();
        assertThat(response.createdByAdminUserId()).isEqualTo(makerAdminId);
    }

    @Test
    @DisplayName("SCRUM-319: Trùng mã plan_code phải bị từ chối")
    void createPlan_DuplicateCode_ThrowsException() {
        CreateActivationPlanCommand cmd = new CreateActivationPlanCommand(
                "PLAN-VIETQR-MB-001",
                "MERCHANT-VISTAY-MAIN",
                "Tên merchant",
                "970422",
                "MB Bank",
                "0987654321",
                "CONG TY TNHH VISTAY",
                PaymentActivationMode.GENERATE_ONLY,
                makerAdminId
        );

        when(planRepository.existsByPlanCode("PLAN-VIETQR-MB-001")).thenReturn(true);

        assertThatThrownBy(() -> service.createPlan(cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("SCRUM-319: Ghi nhận bằng chứng kiểm thử UAT sandbox thành công")
    void recordUatEvidence_Success() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.CALLBACK_ENABLED);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        VietQrActivationPlanResponse response = service.recordUatEvidence(
                plan.getId(),
                new RecordUatEvidenceCommand("UAT-TXN-20261028-001", "Giao dịch test QR 10,000 VND thành công qua cổng sandbox")
        );

        assertThat(response.uatVerified()).isTrue();
        assertThat(response.uatTestReference()).isEqualTo("UAT-TXN-20261028-001");
        assertThat(response.uatVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("SCRUM-319: Cấu hình URL Webhook callback và xác nhận secret")
    void configureCallback_Success() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.CALLBACK_ENABLED);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        VietQrActivationPlanResponse response = service.configureCallback(
                plan.getId(),
                new ConfigureCallbackCommand("https://api.vistay.vn/webhooks/vietqr/transactions", true)
        );

        assertThat(response.callbackUrl()).isEqualTo("https://api.vistay.vn/webhooks/vietqr/transactions");
        assertThat(response.callbackSecretConfigured()).isTrue();
    }

    @Test
    @DisplayName("SCRUM-319: Gửi kế hoạch kích hoạt phê duyệt chuyển sang PENDING_APPROVAL")
    void submitForApproval_Success() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.GENERATE_ONLY);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        VietQrActivationPlanResponse response = service.submitForApproval(
                plan.getId(),
                new SubmitActivationPlanCommand(makerAdminId)
        );

        assertThat(response.status()).isEqualTo(VietQrPlanStatus.PENDING_APPROVAL);
        assertThat(response.submittedAt()).isNotNull();
    }

    @Test
    @DisplayName("SCRUM-319: Người kiểm duyệt độc lập (Checker) duyệt thành công kế hoạch GENERATE_ONLY")
    void approvePlan_GenerateOnly_Success() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.GENERATE_ONLY);
        plan.submitForApproval(makerAdminId);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        VietQrActivationPlanResponse response = service.approvePlan(
                plan.getId(),
                new ApproveActivationPlanCommand(checkerAdminId)
        );

        assertThat(response.status()).isEqualTo(VietQrPlanStatus.APPROVED);
        assertThat(response.approvedByAdminUserId()).isEqualTo(checkerAdminId);
        assertThat(response.approvedAt()).isNotNull();
    }

    @Test
    @DisplayName("SCRUM-319: Vi phạm Maker-Checker (Người tạo tự duyệt) bị từ chối")
    void approvePlan_MakerCheckerViolation_ThrowsException() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.GENERATE_ONLY);
        plan.submitForApproval(makerAdminId);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.approvePlan(
                plan.getId(),
                new ApproveActivationPlanCommand(makerAdminId) // Tự duyệt
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Maker-Checker violation");
    }

    @Test
    @DisplayName("SCRUM-319: Duyệt kế hoạch CALLBACK_ENABLED thất bại nếu chưa xác minh UAT sandbox")
    void approvePlan_CallbackEnabled_WithoutUat_ThrowsException() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.CALLBACK_ENABLED);
        plan.configureCallback("https://api.vistay.vn/webhooks/vietqr", true);
        plan.submitForApproval(makerAdminId);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.approvePlan(
                plan.getId(),
                new ApproveActivationPlanCommand(checkerAdminId)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UAT test evidence has not been verified");
    }

    @Test
    @DisplayName("SCRUM-319: Duyệt kế hoạch CALLBACK_ENABLED thất bại nếu chưa cấu hình secret")
    void approvePlan_CallbackEnabled_WithoutSecret_ThrowsException() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.CALLBACK_ENABLED);
        plan.recordUatEvidence("UAT-001", "Passed");
        plan.configureCallback("https://api.vistay.vn/webhooks/vietqr", false); // Secret chưa cấu hình
        plan.submitForApproval(makerAdminId);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.approvePlan(
                plan.getId(),
                new ApproveActivationPlanCommand(checkerAdminId)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Callback webhook secret is not configured");
    }

    @Test
    @DisplayName("SCRUM-319: Duyệt kế hoạch CALLBACK_ENABLED thành công khi đủ UAT và Secret")
    void approvePlan_CallbackEnabled_Success() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.CALLBACK_ENABLED);
        plan.recordUatEvidence("UAT-PASS-100", "Kiểm thử đầu cuối thành công");
        plan.configureCallback("https://api.vistay.vn/webhooks/vietqr", true);
        plan.submitForApproval(makerAdminId);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        VietQrActivationPlanResponse response = service.approvePlan(
                plan.getId(),
                new ApproveActivationPlanCommand(checkerAdminId)
        );

        assertThat(response.status()).isEqualTo(VietQrPlanStatus.APPROVED);
    }

    @Test
    @DisplayName("SCRUM-319: Kích hoạt chính thức kế hoạch đã duyệt sang ACTIVE")
    void activatePlan_Success() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.GENERATE_ONLY);
        plan.submitForApproval(makerAdminId);
        plan.approve(checkerAdminId);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        VietQrActivationPlanResponse response = service.activatePlan(plan.getId(), checkerAdminId);

        assertThat(response.status()).isEqualTo(VietQrPlanStatus.ACTIVE);
    }

    @Test
    @DisplayName("SCRUM-319: Checker từ chối kế hoạch với lý do rõ ràng")
    void rejectPlan_Success() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.GENERATE_ONLY);
        plan.submitForApproval(makerAdminId);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        VietQrActivationPlanResponse response = service.rejectPlan(
                plan.getId(),
                new RejectActivationPlanCommand(checkerAdminId, "Số tài khoản ngân hàng thụ hưởng chưa khớp với hồ sơ GPKD")
        );

        assertThat(response.status()).isEqualTo(VietQrPlanStatus.REJECTED);
        assertThat(response.rejectionReason()).isEqualTo("Số tài khoản ngân hàng thụ hưởng chưa khớp với hồ sơ GPKD");
    }

    @Test
    @DisplayName("SCRUM-319: Tạm ngừng (Suspend) kế hoạch đang hoạt động")
    void suspendPlan_Success() {
        VietQrActivationPlan plan = createSamplePlan(PaymentActivationMode.GENERATE_ONLY);
        plan.submitForApproval(makerAdminId);
        plan.approve(checkerAdminId);
        plan.activate(checkerAdminId);

        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(VietQrActivationPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        VietQrActivationPlanResponse response = service.suspendPlan(
                plan.getId(),
                checkerAdminId,
                "Bảo trì định kỳ hệ thống ngân hàng"
        );

        assertThat(response.status()).isEqualTo(VietQrPlanStatus.SUSPENDED);
    }

    private VietQrActivationPlan createSamplePlan(PaymentActivationMode mode) {
        return new VietQrActivationPlan(
                "PLAN-SAMPLE-01",
                "MERCHANT-01",
                "Vistay Corp",
                "970422",
                "MB Bank",
                "0123456789",
                "VISTAY CORP",
                mode,
                makerAdminId
        );
    }
}

