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

    private BookingChargeService service;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BookingChargeService(chargeRepository);
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
        assertThat(response.outstandingBalance()).isEqualByComparingTo("500000.00");
        assertThat(response.status()).isEqualTo(ChargeStatus.OPEN);
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

        assertThat(response.chargeCode()).isEqualTo("CHG-BK-202610-001");
        verify(chargeRepository, never()).save(any());
    }

    @Test
    @DisplayName("SCRUM-371: Thanh toán một phần (Partial Payment) cập nhật giảm open balance, lưu transaction ID và chuyển PARTIALLY_PAID")
    void applyPayment_Partial_Success() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-PARTIAL",
                bookingId,
                customerId,
                ChargeType.FULL_PAYMENT,
                new BigDecimal("1000000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Tổng tiền phòng",
                adminUserId,
                "IDEM-PARTIAL"
        );

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(chargeRepository.save(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingChargeResponse response = service.applyPayment(
                charge.getId(),
                new ApplyPaymentToChargeCommand(new BigDecimal("400000.00"), transactionId)
        );

        assertThat(response.status()).isEqualTo(ChargeStatus.PARTIALLY_PAID);
        assertThat(response.paidAmount()).isEqualByComparingTo("400000.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("600000.00");
        assertThat(response.lastPaymentTransactionId()).isEqualTo(transactionId);
    }

    @Test
    @DisplayName("SCRUM-371: Thanh toán toàn bộ xóa hết công nợ mở (outstandingBalance = 0) và chuyển PAID")
    void applyPayment_Full_Success() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-FULL",
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("300000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc phòng",
                adminUserId,
                "IDEM-FULL"
        );

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(chargeRepository.save(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingChargeResponse response = service.applyPayment(
                charge.getId(),
                new ApplyPaymentToChargeCommand(new BigDecimal("300000.00"), transactionId)
        );

        assertThat(response.status()).isEqualTo(ChargeStatus.PAID);
        assertThat(response.paidAmount()).isEqualByComparingTo("300000.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("0.00");
        assertThat(response.lastPaymentTransactionId()).isEqualTo(transactionId);
    }

    @Test
    @DisplayName("SCRUM-371: Thanh toán vượt quá số dư công nợ còn lại bị ném lỗi")
    void applyPayment_Overpayment_ThrowsException() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-OVER",
                bookingId,
                customerId,
                ChargeType.DEPOSIT,
                new BigDecimal("200000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Cọc phòng",
                adminUserId,
                "IDEM-OVER"
        );

        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));

        assertThatThrownBy(() -> service.applyPayment(
                charge.getId(),
                new ApplyPaymentToChargeCommand(new BigDecimal("250000.00"), transactionId)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds outstanding balance");
    }

    @Test
    @DisplayName("SCRUM-371: Miễn giảm công nợ (Waive) bảo toàn bất biến original = paid + waived + outstanding")
    void waiveCharge_Success() {
        BookingCharge charge = new BookingCharge(
                "CHG-BK-WAIVE",
                bookingId,
                customerId,
                ChargeType.EXTRA_SERVICE,
                new BigDecimal("200000.00"),
                "VND",
                Instant.now().plus(24, ChronoUnit.HOURS),
                "Phí dịch vụ đặc biệt",
                adminUserId,
                "IDEM-WAIVE"
        );

        // Thanh toán trước 50k
        charge.applyPayment(new BigDecimal("50000.00"), transactionId);
        // Còn lại 150k, miễn giảm 150k
        when(chargeRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
        when(chargeRepository.save(any(BookingCharge.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingChargeResponse response = service.waiveCharge(
                charge.getId(),
                new WaiveBookingChargeCommand(new BigDecimal("150000.00"), adminUserId, "Chính sách ưu đãi khách VIP")
        );

        assertThat(response.paidAmount()).isEqualByComparingTo("50000.00");
        assertThat(response.waivedAmount()).isEqualByComparingTo("150000.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("0.00");
        // Bất biến: paid (50k) + waived (150k) + outstanding (0k) = original (200k)
        assertThat(response.paidAmount().add(response.waivedAmount()).add(response.outstandingBalance()))
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
                new CancelBookingChargeCommand("Khách yêu cầu hủy")
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
