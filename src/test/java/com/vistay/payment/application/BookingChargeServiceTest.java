package com.vistay.payment.application;

import com.vistay.payment.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingChargeServiceTest {

    @Mock
    private BookingChargeRepository chargeRepository;

    @Mock
    private BookingChargeAllocationRepository allocationRepository;

    @Mock
    private BookingChargeAdjustmentRepository adjustmentRepository;

    @Mock
    private FinancePaymentInstructionClient financeClient;

    @Mock
    private FinanceAllocationClient financeAllocationClient;

    @Mock
    private BookingBranchResolver bookingBranchResolver;

    private BookingChargeService service;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID branchId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID financeRequestId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();
    private final UUID allocationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BookingChargeService(
                chargeRepository,
                allocationRepository,
                adjustmentRepository,
                financeClient,
                financeAllocationClient,
                null,
                bookingBranchResolver
        );
    }

    private FinancePaymentInstruction createValidInstruction() {
        return new FinancePaymentInstruction(
                financeRequestId,
                bookingId,
                customerId,
                new BigDecimal("500000.00"),
                "VND",
                "Tiền cọc giữ phòng 50%",
                "REF-001",
                Instant.now().plus(24, ChronoUnit.HOURS)
        );
    }

    private FinanceAllocationRecord createValidAllocationRecord(BigDecimal amount) {
        return new FinanceAllocationRecord(
                allocationId,
                financeRequestId,
                transactionId,
                amount,
                "VND",
                "CONFIRMED"
        );
    }

    @Test
    @DisplayName("SCRUM-371: Khởi tạo khoản cọc qua Finance Payment Instruction (SSOT) thành công")
    void createCharge_WithFinanceInstruction_Success() {
        FinancePaymentInstruction instruction = createValidInstruction();

        when(financeClient.get(financeRequestId)).thenReturn(instruction);
        when(chargeRepository.findByIdempotencyKey("IDEM-CHG-001")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-BK-202610-001")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(false);
        when(chargeRepository.saveAndFlush(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-202610-001",
                financeRequestId,
                bookingId,
                branchId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tiền cọc giữ phòng 50%",
                "IDEM-CHG-001"
        );

        BookingChargeResponse response = service.createCharge(cmd, adminUserId);

        assertThat(response).isNotNull();
        assertThat(response.chargeCode()).isEqualTo("CHG-BK-202610-001");
        assertThat(response.financePaymentRequestId()).isEqualTo(financeRequestId);
        assertThat(response.bookingId()).isEqualTo(bookingId);
        assertThat(response.branchId()).isEqualTo(branchId);
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.chargeType()).isEqualTo(ChargeType.DEPOSIT);
        assertThat(response.originalAmount()).isEqualByComparingTo("500000.00");
        assertThat(response.paidAmount()).isEqualByComparingTo("0.00");
        assertThat(response.waivedAmount()).isEqualByComparingTo("0.00");
        assertThat(response.voidedAmount()).isEqualByComparingTo("0.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("500000.00");
        assertThat(response.status()).isEqualTo(ChargeStatus.OPEN);
        assertThat(response.createdByAdminUserId()).isEqualTo(adminUserId);
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối tạo charge nếu không có authenticated actor ID")
    void createCharge_NullActorId_ThrowsSecurityException() {
        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-001",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc",
                "IDEM-001"
        );

        assertThatThrownBy(() -> service.createCharge(cmd, null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Authenticated actor ID is required");
    }

    @Test
    @DisplayName("SCRUM-371: Fail-closed nếu Finance Client không khả dụng")
    void createCharge_FinanceClientNull_ThrowsException() {
        BookingChargeService serviceWithoutFinance = new BookingChargeService(
                chargeRepository, allocationRepository, adjustmentRepository, null, null);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-001",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc",
                "IDEM-001"
        );

        when(chargeRepository.findByIdempotencyKey("IDEM-001")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-BK-001")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(false);

        assertThatThrownBy(() -> serviceWithoutFinance.createCharge(cmd, adminUserId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Finance client is unavailable");
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối tạo charge nếu Finance Instruction không tồn tại")
    void createCharge_FinanceInstructionNotFound_ThrowsException() {
        when(chargeRepository.findByIdempotencyKey("IDEM-001")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-BK-001")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(false);
        when(financeClient.get(financeRequestId)).thenReturn(null);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-001",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc",
                "IDEM-001"
        );

        assertThatThrownBy(() -> service.createCharge(cmd, adminUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Finance payment instruction not found");
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối tạo charge nếu Finance Instruction đã hết hạn")
    void createCharge_FinanceInstructionExpired_ThrowsException() {
        FinancePaymentInstruction expiredInstruction = new FinancePaymentInstruction(
                financeRequestId,
                bookingId,
                customerId,
                new BigDecimal("500000.00"),
                "VND",
                "Hết hạn",
                "REF",
                Instant.now().minus(1, ChronoUnit.HOURS)
        );

        when(chargeRepository.findByIdempotencyKey("IDEM-001")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-BK-001")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(false);
        when(financeClient.get(financeRequestId)).thenReturn(expiredInstruction);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-001",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc",
                "IDEM-001"
        );

        assertThatThrownBy(() -> service.createCharge(cmd, adminUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Finance payment instruction is expired");
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối tạo charge nếu số tiền không khớp Finance Instruction")
    void createCharge_FinanceMismatchAmount_ThrowsException() {
        FinancePaymentInstruction instruction = createValidInstruction();
        when(chargeRepository.findByIdempotencyKey("IDEM-001")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-BK-001")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(false);
        when(financeClient.get(financeRequestId)).thenReturn(instruction);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-001",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("600000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc",
                "IDEM-001"
        );

        assertThatThrownBy(() -> service.createCharge(cmd, adminUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match Finance instruction amount");
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối tạo charge nếu Customer ID không khớp Finance Instruction")
    void createCharge_FinanceMismatchCustomerId_ThrowsException() {
        FinancePaymentInstruction instruction = createValidInstruction();
        when(chargeRepository.findByIdempotencyKey("IDEM-001")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-BK-001")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(false);
        when(financeClient.get(financeRequestId)).thenReturn(instruction);

        UUID differentCustomerId = UUID.randomUUID();
        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-001",
                financeRequestId,
                bookingId,
                differentCustomerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc",
                "IDEM-001"
        );

        assertThatThrownBy(() -> service.createCharge(cmd, adminUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer ID does not match Finance instruction");
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối tạo charge nếu Booking ID không khớp Finance Instruction")
    void createCharge_FinanceMismatchBookingId_ThrowsException() {
        FinancePaymentInstruction instruction = createValidInstruction();
        when(chargeRepository.findByIdempotencyKey("IDEM-001")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-BK-001")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(false);
        when(financeClient.get(financeRequestId)).thenReturn(instruction);

        UUID differentBookingId = UUID.randomUUID();
        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-001",
                financeRequestId,
                differentBookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc",
                "IDEM-001"
        );

        assertThatThrownBy(() -> service.createCharge(cmd, adminUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Booking ID does not match Finance instruction");
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối tạo charge trùng Finance Payment Request ID (1:1 Relation)")
    void createCharge_DuplicateFinancePaymentRequestId_ThrowsException() {
        when(chargeRepository.findByIdempotencyKey("IDEM-001")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-BK-001")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(true);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-001",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc",
                "IDEM-001"
        );

        assertThatThrownBy(() -> service.createCharge(cmd, adminUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Booking charge already exists for Finance Payment Request");
    }

    @Test
    @DisplayName("SCRUM-371: Xử lý an toàn khi gặp xung đột Race Condition đồng thời trên PostgreSQL")
    void createCharge_ConcurrentRaceCondition_RecoversGracefully() {
        FinancePaymentInstruction instruction = createValidInstruction();
        BookingCharge existingWinner = new BookingCharge(
                "CHG-BK-RACE",
                financeRequestId,
                bookingId,
                branchId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc",
                adminUserId,
                "IDEM-RACE"
        );

        when(chargeRepository.findByIdempotencyKey("IDEM-RACE"))
                .thenReturn(Optional.empty()) // Lần đọc 1: chưa thấy
                .thenReturn(Optional.of(existingWinner)); // Lần đọc 2 sau khi đụng unique constraint
        when(chargeRepository.existsByChargeCode("CHG-BK-RACE")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(false);
        when(financeClient.get(financeRequestId)).thenReturn(instruction);
        when(chargeRepository.saveAndFlush(any(BookingCharge.class))).thenThrow(new DataIntegrityViolationException("unique constraint"));

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-RACE",
                financeRequestId,
                bookingId,
                branchId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc",
                "IDEM-RACE"
        );

        BookingChargeResponse response = service.createCharge(cmd, adminUserId);

        assertThat(response).isNotNull();
        assertThat(response.chargeCode()).isEqualTo("CHG-BK-RACE");
    }

    @Test
    @DisplayName("SCRUM-371: Xung đột Idempotency key khi gửi cùng key nhưng khác payload")
    void createCharge_IdempotencyKeyCollisionDifferentPayload_ThrowsException() {
        BookingCharge existing = new BookingCharge(
                "CHG-ORIGINAL",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc gốc",
                adminUserId,
                "IDEM-COLLISION"
        );

        when(chargeRepository.findByIdempotencyKey("IDEM-COLLISION")).thenReturn(Optional.of(existing));

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-ATTACK",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("1000000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc mới",
                "IDEM-COLLISION"
        );

        assertThatThrownBy(() -> service.createCharge(cmd, adminUserId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Idempotency key collision with differing payload");
    }

    @Test
    @DisplayName("SCRUM-371: Ghi nhận thanh toán có xác thực trực tiếp từ Finance Allocation SSOT thành công")
    void applyPayment_WithVerifiedFinanceAllocation_Success() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-202610-002",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.FULL_PAYMENT,
                new BigDecimal("1000000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tiền thuê homestay",
                adminUserId,
                "IDEM-CHG-002"
        );

        FinanceAllocationRecord allocationRecord = createValidAllocationRecord(new BigDecimal("400000.00"));
        when(allocationRepository.findByFinanceAllocationId(allocationId)).thenReturn(Optional.empty());
        when(financeAllocationClient.getAllocation(allocationId)).thenReturn(allocationRecord);
        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(chargeRepository.saveAndFlush(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        ApplyPaymentToChargeCommand cmd = new ApplyPaymentToChargeCommand(
                allocationId,
                transactionId,
                new BigDecimal("400000.00"),
                "Thanh toán đợt 1 qua VietQR"
        );

        BookingChargeResponse response = service.applyPayment(charge.getId(), cmd, adminUserId);

        assertThat(response.status()).isEqualTo(ChargeStatus.PARTIALLY_PAID);
        assertThat(response.paidAmount()).isEqualByComparingTo("400000.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("600000.00");
        assertThat(response.lastPaymentTransactionId()).isEqualTo(transactionId);

        verify(allocationRepository, times(1)).saveAndFlush(any(BookingChargeAllocation.class));
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối apply payment nếu Finance Allocation không tồn tại trên Finance")
    void applyPayment_FinanceAllocationNotFound_ThrowsException() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-002",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.FULL_PAYMENT,
                new BigDecimal("1000000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tiền phòng",
                adminUserId,
                "IDEM-002"
        );

        when(allocationRepository.findByFinanceAllocationId(allocationId)).thenReturn(Optional.empty());
        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(financeAllocationClient.getAllocation(allocationId)).thenReturn(null);

        ApplyPaymentToChargeCommand cmd = new ApplyPaymentToChargeCommand(
                allocationId,
                transactionId,
                new BigDecimal("400000.00"),
                "Ghi khống"
        );

        assertThatThrownBy(() -> service.applyPayment(charge.getId(), cmd, adminUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Finance allocation record not found");
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối apply payment nếu số tiền lệch bản ghi Finance Allocation")
    void applyPayment_FinanceAllocationAmountMismatch_ThrowsException() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-002",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.FULL_PAYMENT,
                new BigDecimal("1000000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tiền phòng",
                adminUserId,
                "IDEM-002"
        );

        // Finance ghi nhận 300k nhưng request gửi 400k
        FinanceAllocationRecord allocationRecord = createValidAllocationRecord(new BigDecimal("300000.00"));
        when(allocationRepository.findByFinanceAllocationId(allocationId)).thenReturn(Optional.empty());
        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(financeAllocationClient.getAllocation(allocationId)).thenReturn(allocationRecord);

        ApplyPaymentToChargeCommand cmd = new ApplyPaymentToChargeCommand(
                allocationId,
                transactionId,
                new BigDecimal("400000.00"),
                "Lệch số tiền"
        );

        assertThatThrownBy(() -> service.applyPayment(charge.getId(), cmd, adminUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match Finance allocation amount");
    }

    @Test
    @DisplayName("SCRUM-371: Idempotent replay của chính Finance Allocation ID trả về kết quả cũ không tăng tiền")
    void applyPayment_IdempotentReplaySameAllocation_Success() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-002",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.FULL_PAYMENT,
                new BigDecimal("1000000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tiền phòng",
                adminUserId,
                "IDEM-002"
        );
        charge.applyPayment(new BigDecimal("400000.00"), transactionId);

        BookingChargeAllocation existingAllocation = new BookingChargeAllocation(
                charge.getId(),
                allocationId,
                transactionId,
                new BigDecimal("400000.00"),
                "VND",
                adminUserId,
                "Thanh toán đợt 1"
        );

        when(allocationRepository.findByFinanceAllocationId(allocationId)).thenReturn(Optional.of(existingAllocation));
        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));

        ApplyPaymentToChargeCommand cmd = new ApplyPaymentToChargeCommand(
                allocationId,
                transactionId,
                new BigDecimal("400000.00"),
                "Thanh toán đợt 1"
        );

        BookingChargeResponse response = service.applyPayment(charge.getId(), cmd, adminUserId);

        assertThat(response.paidAmount()).isEqualByComparingTo("400000.00");
        verify(allocationRepository, never()).save(any());
    }

    @Test
    @DisplayName("SCRUM-371: Chặn gửi trùng Finance Allocation ID với payload khác nhau")
    void applyPayment_DuplicateAllocationIdDifferentPayload_ThrowsException() {
        UUID existingChargeId = UUID.randomUUID();
        BookingChargeAllocation existingAllocation = new BookingChargeAllocation(
                existingChargeId,
                allocationId,
                transactionId,
                new BigDecimal("400000.00"),
                "VND",
                adminUserId,
                "Đã dùng"
        );

        when(allocationRepository.findByFinanceAllocationId(allocationId)).thenReturn(Optional.of(existingAllocation));

        UUID anotherChargeId = UUID.randomUUID();
        ApplyPaymentToChargeCommand cmd = new ApplyPaymentToChargeCommand(
                allocationId,
                transactionId,
                new BigDecimal("500000.00"),
                "Cố gắng replay sai"
        );

        assertThatThrownBy(() -> service.applyPayment(anotherChargeId, cmd, adminUserId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate finance allocation ID already recorded");
    }

    @Test
    @DisplayName("SCRUM-371: Miễn giảm nhiều lần (Waiver) lưu vết đầy đủ trong Adjustment Ledger")
    void waiveCharge_MultipleWaivers_SavesAdjustmentHistory() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-WAIVE",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.DAMAGE_DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc hư hại",
                adminUserId,
                "IDEM-WAIVE"
        );

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(chargeRepository.saveAndFlush(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        WaiveBookingChargeCommand cmd1 = new WaiveBookingChargeCommand(new BigDecimal("100000.00"), "Giảm 1");
        service.waiveCharge(charge.getId(), cmd1, adminUserId);

        WaiveBookingChargeCommand cmd2 = new WaiveBookingChargeCommand(new BigDecimal("200000.00"), "Giảm 2");
        BookingChargeResponse response = service.waiveCharge(charge.getId(), cmd2, adminUserId);

        assertThat(response.waivedAmount()).isEqualByComparingTo("300000.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("200000.00");

        verify(adjustmentRepository, times(2)).save(any(BookingChargeAdjustment.class));
    }

    @Test
    @DisplayName("SCRUM-371: Hủy charge lưu vết CANCELLATION vào Adjustment Ledger kèm voidedAmount")
    void cancelCharge_SavesCancellationAdjustment_Success() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-CANCEL",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.EXTRA_SERVICE,
                new BigDecimal("800000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "BBQ",
                adminUserId,
                "IDEM-CANCEL"
        );

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(chargeRepository.saveAndFlush(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        CancelBookingChargeCommand cmd = new CancelBookingChargeCommand("Khách báo hủy");
        BookingChargeResponse response = service.cancelCharge(charge.getId(), cmd, adminUserId);

        assertThat(response.status()).isEqualTo(ChargeStatus.CANCELLED);
        assertThat(response.voidedAmount()).isEqualByComparingTo("800000.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("0.00");

        verify(adjustmentRepository, times(1)).save(any(BookingChargeAdjustment.class));
    }

    @Test
    @DisplayName("SCRUM-371: Tổng hợp Open Balance từ chối nếu có nhiều loại tiền tệ (Currency Mismatch)")
    void getBookingOpenBalanceSummary_MixedCurrencies_ThrowsException() {
        BookingCharge charge1 = new BookingCharge(
                "CHG-VND", financeRequestId, bookingId, customerId, ChargeType.DEPOSIT,
                new BigDecimal("500000.00"), "VND", Instant.now(), "Cọc VND", adminUserId, "ID1");

        UUID financeRequestId2 = UUID.randomUUID();
        BookingCharge charge2 = new BookingCharge(
                "CHG-USD", financeRequestId2, bookingId, customerId, ChargeType.DEPOSIT,
                new BigDecimal("100.00"), "USD", Instant.now(), "Cọc USD", adminUserId, "ID2");

        when(chargeRepository.findByBookingId(bookingId)).thenReturn(List.of(charge1, charge2));

        assertThatThrownBy(() -> service.getBookingOpenBalanceSummary(bookingId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple currencies found");
    }

    @Test
    @DisplayName("SCRUM-371: Tổng hợp Open Balance phản ánh đúng totalVoidedAmount và bảo toàn bất biến")
    void getBookingOpenBalanceSummary_WithCancelledCharge_IncludesTotalVoidedAmount() {
        BookingCharge charge1 = new BookingCharge(
                "CHG-01", financeRequestId, bookingId, customerId, ChargeType.DEPOSIT,
                new BigDecimal("500000.00"), "VND", Instant.now(), "Cọc", adminUserId, "ID1");
        charge1.applyPayment(new BigDecimal("500000.00"), transactionId);

        UUID financeRequestId2 = UUID.randomUUID();
        BookingCharge charge2 = new BookingCharge(
                "CHG-02", financeRequestId2, bookingId, customerId, ChargeType.FULL_PAYMENT,
                new BigDecimal("1200000.00"), "VND", Instant.now(), "Tiền phòng", adminUserId, "ID2");
        charge2.applyPayment(new BigDecimal("200000.00"), transactionId);

        UUID financeRequestId3 = UUID.randomUUID();
        BookingCharge charge3 = new BookingCharge(
                "CHG-03", financeRequestId3, bookingId, customerId, ChargeType.EXTRA_SERVICE,
                new BigDecimal("300000.00"), "VND", Instant.now(), "BBQ hủy", adminUserId, "ID3");
        charge3.cancel(adminUserId, "Hủy BBQ");

        when(chargeRepository.findByBookingId(bookingId)).thenReturn(List.of(charge1, charge2, charge3));

        BookingOpenBalanceSummaryResponse summary = service.getBookingOpenBalanceSummary(bookingId);

        assertThat(summary.totalOriginalCharges()).isEqualByComparingTo("2000000.00");
        assertThat(summary.totalPaidAmount()).isEqualByComparingTo("700000.00");
        assertThat(summary.totalWaivedAmount()).isEqualByComparingTo("0.00");
        assertThat(summary.totalVoidedAmount()).isEqualByComparingTo("300000.00");
        assertThat(summary.totalOpenBalance()).isEqualByComparingTo("1000000.00");
        assertThat(summary.activeChargeCount()).isEqualTo(1);

        BigDecimal totalAccounted = summary.totalPaidAmount()
                .add(summary.totalWaivedAmount())
                .add(summary.totalVoidedAmount())
                .add(summary.totalOpenBalance());
        assertThat(totalAccounted).isEqualByComparingTo(summary.totalOriginalCharges());
    }

    @Test
    @DisplayName("P1 Finance SSOT: Thiếu FinanceAllocationClient phải fail-closed ném IllegalStateException")
    void applyPayment_NullFinanceAllocationClient_ThrowsIllegalStateException() {
        BookingChargeService serviceWithoutAllocClient = new BookingChargeService(
                chargeRepository, allocationRepository, adjustmentRepository, financeClient, null
        );

        UUID chargeId = UUID.randomUUID();
        BookingCharge charge = new BookingCharge(
                "CHG-01", financeRequestId, bookingId, customerId, ChargeType.DEPOSIT,
                new BigDecimal("500000.00"), "VND", Instant.now(), "Deposit", adminUserId, "ID1"
        );
        when(chargeRepository.findById(chargeId)).thenReturn(Optional.of(charge));
        when(allocationRepository.findByFinanceAllocationId(allocationId)).thenReturn(Optional.empty());

        ApplyPaymentToChargeCommand cmd = new ApplyPaymentToChargeCommand(
                allocationId, transactionId, new BigDecimal("200000.00"), "Pay"
        );

        assertThatThrownBy(() -> serviceWithoutAllocClient.applyPayment(chargeId, cmd, adminUserId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Finance allocation client is required for SSOT verification: fail-closed");
    }

    @Test
    @DisplayName("P0 Branch Authority: branchId client gửi xung đột với BookingBranchResolver uy tín -> Ném IllegalArgumentException")
    void createCharge_ConflictingBranchId_ThrowsIllegalArgumentException() {
        UUID authoritativeBranch = UUID.randomUUID();
        UUID forgedBranch = UUID.randomUUID();

        FinancePaymentInstruction instruction = createValidInstruction();
        when(financeClient.get(financeRequestId)).thenReturn(instruction);
        when(chargeRepository.findByIdempotencyKey("IDEM-BRANCH-ERR")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-ERR")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(false);
        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.of(authoritativeBranch));

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-ERR", financeRequestId, bookingId, forgedBranch, customerId,
                ChargeType.DEPOSIT, new BigDecimal("500000.00"), "VND",
                Instant.now().plus(24, ChronoUnit.HOURS), "Cọc", "IDEM-BRANCH-ERR"
        );

        assertThatThrownBy(() -> service.createCharge(cmd, adminUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicts with authoritative booking branch");
    }

    @Test
    @DisplayName("P0 Branch Authority: BookingBranchResolver xác thực branch uy tín -> Áp dụng đúng branch resolved")
    void createCharge_AuthoritativeBranchResolved_AppliesCorrectBranch() {
        UUID authoritativeBranch = UUID.randomUUID();

        FinancePaymentInstruction instruction = createValidInstruction();
        when(financeClient.get(financeRequestId)).thenReturn(instruction);
        when(chargeRepository.findByIdempotencyKey("IDEM-BRANCH-OK")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-OK")).thenReturn(false);
        when(chargeRepository.existsByFinancePaymentRequestId(financeRequestId)).thenReturn(false);
        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.of(authoritativeBranch));
        when(chargeRepository.saveAndFlush(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        // Client supplies null branchId, resolver supplies authoritativeBranch
        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-OK", financeRequestId, bookingId, null, customerId,
                ChargeType.DEPOSIT, new BigDecimal("500000.00"), "VND",
                Instant.now().plus(24, ChronoUnit.HOURS), "Cọc", "IDEM-BRANCH-OK"
        );

        BookingChargeResponse response = service.createCharge(cmd, adminUserId);

        assertThat(response).isNotNull();
        assertThat(response.branchId()).isEqualTo(authoritativeBranch);
    }
}
