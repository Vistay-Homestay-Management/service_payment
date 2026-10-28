package com.vistay.payment.application;

import com.vistay.payment.domain.BookingCharge;
import com.vistay.payment.domain.BookingChargeRepository;
import com.vistay.payment.domain.ChargeStatus;
import com.vistay.payment.domain.ChargeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private FinancePaymentInstructionClient financeClient;

    private BookingChargeService service;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BookingChargeService(chargeRepository, financeClient);
    }

    @Test
    @DisplayName("SCRUM-371: Khởi tạo khoản cọc đặt phòng (Deposit Charge) với công nợ mở (Open Balance) thành công")
    void createCharge_Success() {
        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-202610-001",
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tiền cọc giữ phòng 50%",
                adminUserId,
                "IDEM-CHG-001"
        );

        when(chargeRepository.findByIdempotencyKey("IDEM-CHG-001")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-BK-202610-001")).thenReturn(false);
        when(chargeRepository.save(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingChargeResponse response = service.createCharge(cmd);

        assertThat(response).isNotNull();
        assertThat(response.chargeCode()).isEqualTo("CHG-BK-202610-001");
        assertThat(response.bookingId()).isEqualTo(bookingId);
        assertThat(response.chargeType()).isEqualTo(ChargeType.DEPOSIT);
        assertThat(response.originalAmount()).isEqualByComparingTo("500000.00");
        assertThat(response.paidAmount()).isEqualByComparingTo("0.00");
        assertThat(response.waivedAmount()).isEqualByComparingTo("0.00");
        assertThat(response.voidedAmount()).isEqualByComparingTo("0.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("500000.00");
        assertThat(response.status()).isEqualTo(ChargeStatus.OPEN);
    }

    @Test
    @DisplayName("SCRUM-371: Khởi tạo lệnh thu xác thực qua Finance Payment Instruction (SSOT)")
    void createCharge_WithFinanceInstruction_Success() {
        UUID financeRequestId = UUID.randomUUID();
        FinancePaymentInstruction instruction = new FinancePaymentInstruction(
                financeRequestId,
                bookingId,
                customerId,
                new BigDecimal("1500000.00"),
                "VND",
                "Tiền phòng deluxe",
                "REF-01",
                Instant.now().plus(48, ChronoUnit.HOURS)
        );

        when(financeClient.get(financeRequestId)).thenReturn(instruction);
        when(chargeRepository.findByIdempotencyKey("IDEM-FIN-001")).thenReturn(Optional.empty());
        when(chargeRepository.existsByChargeCode("CHG-FIN-001")).thenReturn(false);
        when(chargeRepository.save(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-FIN-001",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.FULL_PAYMENT,
                new BigDecimal("1500000.00"),
                "VND",
                Instant.now().plus(48, ChronoUnit.HOURS),
                "Thu tiền phòng deluxe",
                adminUserId,
                "IDEM-FIN-001"
        );

        BookingChargeResponse response = service.createCharge(cmd, adminUserId);

        assertThat(response).isNotNull();
        assertThat(response.financePaymentRequestId()).isEqualTo(financeRequestId);
        assertThat(response.originalAmount()).isEqualByComparingTo("1500000.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("1500000.00");
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối tạo lệnh thu nếu số tiền không khớp Finance Payment Instruction")
    void createCharge_FinanceMismatchAmount_ThrowsException() {
        UUID financeRequestId = UUID.randomUUID();
        FinancePaymentInstruction instruction = new FinancePaymentInstruction(
                financeRequestId,
                bookingId,
                customerId,
                new BigDecimal("1500000.00"),
                "VND",
                "Tiền phòng",
                "REF-01",
                Instant.now().plus(48, ChronoUnit.HOURS)
        );

        when(financeClient.get(financeRequestId)).thenReturn(instruction);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-FIN-002",
                financeRequestId,
                bookingId,
                customerId,
                ChargeType.FULL_PAYMENT,
                new BigDecimal("2000000.00"), // Sai lệch số tiền so với Finance
                "VND",
                Instant.now().plus(48, ChronoUnit.HOURS),
                "Sai số tiền",
                adminUserId,
                "IDEM-FIN-002"
        );

        assertThatThrownBy(() -> service.createCharge(cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match Finance instruction amount");
    }

    @Test
    @DisplayName("SCRUM-371: Cơ chế Idempotency trả về đúng bản ghi đã tồn tại khi gửi trùng idempotency key")
    void createCharge_IdempotentReplay() {
        BookingCharge existing = new BookingCharge(
                "CHG-BK-202610-001",
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tiền cọc giữ phòng",
                adminUserId,
                "IDEM-CHG-001"
        );

        when(chargeRepository.findByIdempotencyKey("IDEM-CHG-001")).thenReturn(Optional.of(existing));

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-BK-202610-001",
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tiền cọc giữ phòng",
                adminUserId,
                "IDEM-CHG-001"
        );

        BookingChargeResponse response = service.createCharge(cmd);

        assertThat(response).isNotNull();
        assertThat(response.chargeCode()).isEqualTo("CHG-BK-202610-001");
        verify(chargeRepository, never()).save(any());
    }

    @Test
    @DisplayName("SCRUM-371: Ghi nhận thanh toán một phần (Partially Paid) và cập nhật số dư công nợ còn lại")
    void applyPayment_PartialPayment_Success() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-202610-002",
                bookingId,
                customerId,
                ChargeType.FULL_PAYMENT,
                new BigDecimal("1000000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tiền thuê homestay trọn gói",
                adminUserId,
                "IDEM-CHG-002"
        );

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(chargeRepository.save(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        ApplyPaymentToChargeCommand cmd = new ApplyPaymentToChargeCommand(
                new BigDecimal("400000.00"),
                transactionId,
                "Thanh toán đợt 1 qua VietQR"
        );

        BookingChargeResponse response = service.applyPayment(charge.getId(), cmd);

        assertThat(response.status()).isEqualTo(ChargeStatus.PARTIALLY_PAID);
        assertThat(response.paidAmount()).isEqualByComparingTo("400000.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("600000.00");
        assertThat(response.lastPaymentTransactionId()).isEqualTo(transactionId);
    }

    @Test
    @DisplayName("SCRUM-371: Ghi nhận thanh toán toàn bộ (Fully Paid) và đóng số dư công nợ về 0")
    void applyPayment_FullPayment_Success() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-202610-003",
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("300000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc dịch vụ",
                adminUserId,
                "IDEM-CHG-003"
        );

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(chargeRepository.save(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        ApplyPaymentToChargeCommand cmd = new ApplyPaymentToChargeCommand(
                new BigDecimal("300000.00"),
                transactionId,
                "Thanh toán đầy đủ"
        );

        BookingChargeResponse response = service.applyPayment(charge.getId(), cmd);

        assertThat(response.status()).isEqualTo(ChargeStatus.PAID);
        assertThat(response.paidAmount()).isEqualByComparingTo("300000.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("SCRUM-371: Từ chối thanh toán số tiền vượt quá số dư công nợ còn lại")
    void applyPayment_ExceedingOutstandingBalance_ThrowsException() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-202610-004",
                bookingId,
                customerId,
                ChargeType.PENALTY,
                new BigDecimal("200000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Phạt trễ giờ",
                adminUserId,
                "IDEM-CHG-004"
        );

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));

        ApplyPaymentToChargeCommand cmd = new ApplyPaymentToChargeCommand(
                new BigDecimal("250000.00"), // Lớn hơn 200,000
                transactionId,
                "Thanh toán dư"
        );

        assertThatThrownBy(() -> service.applyPayment(charge.getId(), cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds outstanding balance");
    }

    @Test
    @DisplayName("SCRUM-371: Miễn giảm một phần khoản thu (Waive) và tất toán khi kết hợp thanh toán (SETTLED)")
    void waiveCharge_PartialThenSettle_Success() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-WAIVE-SETTLE",
                bookingId,
                customerId,
                ChargeType.DAMAGE_DEPOSIT,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc hư hại tài sản",
                adminUserId,
                "IDEM-WAIVE-SETTLE"
        );

        // Khách thanh toán trước 200k
        charge.applyPayment(new BigDecimal("200000.00"), transactionId);

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(chargeRepository.save(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        // Quản trị viên miễn giảm nốt 300k còn lại
        WaiveBookingChargeCommand cmd = new WaiveBookingChargeCommand(
                new BigDecimal("300000.00"),
                adminUserId,
                "Khách quen, miễn giảm phần hư hại nhẹ"
        );

        BookingChargeResponse response = service.waiveCharge(charge.getId(), cmd, adminUserId);

        assertThat(response.status()).isEqualTo(ChargeStatus.SETTLED);
        assertThat(response.paidAmount()).isEqualByComparingTo("200000.00");
        assertThat(response.waivedAmount()).isEqualByComparingTo("300000.00");
        assertThat(response.voidedAmount()).isEqualByComparingTo("0.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("0.00");
        assertThat(response.waiverReason()).isEqualTo("Khách quen, miễn giảm phần hư hại nhẹ");
        assertThat(response.waivedByAdminUserId()).isEqualTo(adminUserId);

        // Bất biến: paid (200k) + waived (300k) + voided (0) + outstanding (0) = original (500k)
        assertThat(response.paidAmount().add(response.waivedAmount()).add(response.voidedAmount()).add(response.outstandingBalance()))
                .isEqualByComparingTo(response.originalAmount());
    }

    @Test
    @DisplayName("SCRUM-371: Hủy khoản thu (Cancel) khi chưa thanh toán - chuyển sang voidedAmount mà không làm tăng waivedAmount")
    void cancelCharge_Unpaid_Success() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-CANCEL-OK",
                bookingId,
                customerId,
                ChargeType.EXTRA_SERVICE,
                new BigDecimal("800000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Dịch vụ BBQ sân vườn",
                adminUserId,
                "IDEM-CANCEL-OK"
        );

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(chargeRepository.save(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        CancelBookingChargeCommand cmd = new CancelBookingChargeCommand("Khách hủy đặt tiệc BBQ trước 24h");
        BookingChargeResponse response = service.cancelCharge(charge.getId(), cmd, adminUserId);

        assertThat(response.status()).isEqualTo(ChargeStatus.CANCELLED);
        assertThat(response.paidAmount()).isEqualByComparingTo("0.00");
        assertThat(response.waivedAmount()).isEqualByComparingTo("0.00"); // Không bị ghi đè thành 800k!
        assertThat(response.voidedAmount()).isEqualByComparingTo("800000.00"); // Ghi nhận đúng bản chất voided
        assertThat(response.outstandingBalance()).isEqualByComparingTo("0.00");
        assertThat(response.cancellationReason()).isEqualTo("Khách hủy đặt tiệc BBQ trước 24h");
        assertThat(response.cancelledByAdminUserId()).isEqualTo(adminUserId);

        // Bất biến: paid (0) + waived (0) + voided (800k) + outstanding (0) = original (800k)
        assertThat(response.paidAmount().add(response.waivedAmount()).add(response.voidedAmount()).add(response.outstandingBalance()))
                .isEqualByComparingTo(response.originalAmount());
    }

    @Test
    @DisplayName("SCRUM-371: Không cho phép hủy charge nếu đã phát sinh tiền thanh toán")
    void cancelCharge_WithExistingPayment_ThrowsException() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-CANCEL-FAIL",
                bookingId,
                customerId,
                ChargeType.FULL_PAYMENT,
                new BigDecimal("1000000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tiền phòng",
                adminUserId,
                "IDEM-CANCEL-FAIL"
        );
        charge.applyPayment(new BigDecimal("300000.00"), transactionId);

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));

        assertThatThrownBy(() -> service.cancelCharge(
                charge.getId(),
                new CancelBookingChargeCommand("Khách yêu cầu hủy"),
                adminUserId
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel charge with existing paid amount");
    }

    @Test
    @DisplayName("SCRUM-371: Tổng hợp số dư công nợ mở (Open Balance Summary) chính xác trên nhiều khoản charge")
    void getBookingOpenBalanceSummary_Success() {
        BookingCharge charge1 = new BookingCharge(
                "CHG-01", bookingId, customerId, ChargeType.DEPOSIT,
                new BigDecimal("500000.00"), "VND", Instant.now(), "Cọc", adminUserId, "ID1");
        charge1.applyPayment(new BigDecimal("500000.00"), transactionId); // Đã trả hết (PAID, balance 0)

        BookingCharge charge2 = new BookingCharge(
                "CHG-02", bookingId, customerId, ChargeType.FULL_PAYMENT,
                new BigDecimal("1200000.00"), "VND", Instant.now(), "Tiền phòng còn lại", adminUserId, "ID2");
        charge2.applyPayment(new BigDecimal("200000.00"), transactionId); // Đã trả 200k, còn 1000k

        BookingCharge charge3 = new BookingCharge(
                "CHG-03", bookingId, customerId, ChargeType.DAMAGE_DEPOSIT,
                new BigDecimal("300000.00"), "VND", Instant.now(), "Cọc tài sản", adminUserId, "ID3");
        charge3.waive(new BigDecimal("100000.00"), adminUserId, "Giảm trừ cọc"); // Miễn giảm 100k, còn 200k

        when(chargeRepository.findByBookingId(bookingId)).thenReturn(List.of(charge1, charge2, charge3));

        BookingOpenBalanceSummaryResponse summary = service.getBookingOpenBalanceSummary(bookingId);

        assertThat(summary.bookingId()).isEqualTo(bookingId);
        assertThat(summary.totalOriginalCharges()).isEqualByComparingTo("2000000.00"); // 500k + 1200k + 300k
        assertThat(summary.totalPaidAmount()).isEqualByComparingTo("700000.00"); // 500k + 200k
        assertThat(summary.totalWaivedAmount()).isEqualByComparingTo("100000.00"); // 100k
        assertThat(summary.totalOpenBalance()).isEqualByComparingTo("1200000.00"); // 1000k + 200k
        assertThat(summary.activeChargeCount()).isEqualTo(2); // charge2 & charge3
    }
}
