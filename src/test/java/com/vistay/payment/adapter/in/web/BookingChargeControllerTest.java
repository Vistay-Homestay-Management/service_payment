package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.*;
import com.vistay.payment.domain.ChargeStatus;
import com.vistay.payment.domain.ChargeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingChargeControllerTest {

    @Mock
    private BookingChargeService bookingChargeService;

    @Mock
    private BookingBranchResolver bookingBranchResolver;

    @InjectMocks
    private BookingChargeController controller;

    private final UUID chargeId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID branchA = UUID.randomUUID();
    private final UUID branchB = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID financeRequestId = UUID.randomUUID();
    private final UUID staffUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Jwt createJwt(String subject, Map<String, Object> extraClaims) {
        var builder = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject(subject)
                .claim("aud", List.of("vistay-services"));
        if (extraClaims != null) {
            extraClaims.forEach(builder::claim);
        }
        return builder.build();
    }

    private BookingChargeResponse createSampleChargeResponse(UUID ownerCustomerId, UUID branchId, UUID createdBy) {
        return new BookingChargeResponse(
                chargeId,
                "CHG-BK-001",
                financeRequestId,
                bookingId,
                branchId,
                ownerCustomerId,
                ChargeType.DEPOSIT,
                new BigDecimal("500000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("500000.00"),
                "VND",
                Instant.now(),
                ChargeStatus.OPEN,
                "Cọc",
                null,
                null,
                null,
                null,
                null,
                createdBy != null ? createdBy : staffUserId,
                "IDEM-001",
                0,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("P0-03 Data Scope: ROLE_USER xem charge của chính mình -> 200 OK")
    void getChargeById_OwnCustomer_Returns200() {
        Jwt jwt = createJwt("CUSTOMER:" + customerId, null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchA, staffUserId));

        ResponseEntity<BookingChargeResponse> response = controller.getChargeById(chargeId, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().customerId()).isEqualTo(customerId);
    }

    @Test
    @DisplayName("P0-03 Data Scope: ROLE_USER xem charge của người khác -> 403 Forbidden")
    void getChargeById_DifferentCustomer_Throws403() {
        UUID otherCustomerId = UUID.randomUUID();
        Jwt jwt = createJwt("CUSTOMER:" + customerId, null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(otherCustomerId, branchA, staffUserId));

        assertThatThrownBy(() -> controller.getChargeById(chargeId, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Cannot access booking charges belonging to another customer");
                });
    }

    @Test
    @DisplayName("P0-02 Staff Data Scope: Nhân viên cơ sở A có BRANCH scope chỉ được xem khoản thu ở cơ sở A -> 200 OK")
    void getChargeById_StaffBranchScope_SameBranch_Returns200() {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:read", "s", "BRANCH")),
                "bld", List.of(branchA.toString())
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchA, staffUserId));

        ResponseEntity<BookingChargeResponse> response = controller.getChargeById(chargeId, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("F-02 Fix: Nhân viên BRANCH scope xem khoản thu có branchId == NULL -> 403 Forbidden")
    void getChargeById_StaffBranchScope_NullBranch_Throws403() {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:read", "s", "BRANCH")),
                "bld", List.of(branchA.toString())
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, null, staffUserId));

        assertThatThrownBy(() -> controller.getChargeById(chargeId, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Staff with BRANCH scope cannot access charges without an assigned branch");
                });
    }

    @Test
    @DisplayName("P0-02 Staff Data Scope: Nhân viên cơ sở A có BRANCH scope truy cập khoản thu ở cơ sở B -> 403 Forbidden")
    void getChargeById_StaffBranchScope_DifferentBranch_Throws403() {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:read", "s", "BRANCH")),
                "bld", List.of(branchA.toString())
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchB, staffUserId));

        assertThatThrownBy(() -> controller.getChargeById(chargeId, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Staff is not authorized for branch");
                });
    }

    @Test
    @DisplayName("P0-02 Staff Data Scope: Nhân viên có OWN scope chỉ được xem charge do mình tạo -> 200 OK")
    void getChargeById_StaffOwnScope_SameCreator_Returns200() {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:read", "s", "OWN"))
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchA, staffUserId));

        ResponseEntity<BookingChargeResponse> response = controller.getChargeById(chargeId, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("P0-02 Staff Data Scope: Nhân viên có OWN scope xem charge do người khác tạo -> 403 Forbidden")
    void getChargeById_StaffOwnScope_OtherCreator_Throws403() {
        UUID otherStaff = UUID.randomUUID();
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:read", "s", "OWN"))
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchA, otherStaff));

        assertThatThrownBy(() -> controller.getChargeById(chargeId, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Staff with OWN scope cannot access charges created by another actor");
                });
    }

    @Test
    @DisplayName("P0-01 S4 Grants Isolation: Có booking:read/ALL nhưng thiếu payment:read grant -> 403 Forbidden")
    void getChargeById_BookingReadScopeOnly_MissingPaymentReadGrant_Throws403() {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "booking:read", "s", "ALL"))
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchA, staffUserId));

        assertThatThrownBy(() -> controller.getChargeById(chargeId, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Missing valid data scope for payment:read");
                });
    }

    @Test
    @DisplayName("P0-01 ASSIGNED Scope: Nhân viên ASSIGNED scope truy cập booking được phân công -> 200 OK")
    void getChargeById_StaffAssignedScope_AssignedToBooking_Returns200() {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:read", "s", "ASSIGNED"))
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        when(bookingBranchResolver.isBookingAssignedTo(bookingId, staffUserId)).thenReturn(true);
        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchA, UUID.randomUUID()));

        ResponseEntity<BookingChargeResponse> response = controller.getChargeById(chargeId, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("P0-01 ASSIGNED Scope: Nhân viên ASSIGNED scope truy cập booking không được phân công -> 403 Forbidden")
    void getChargeById_StaffAssignedScope_NotAssignedToBooking_Throws403() {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:read", "s", "ASSIGNED"))
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        when(bookingBranchResolver.isBookingAssignedTo(bookingId, staffUserId)).thenReturn(false);
        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchA, UUID.randomUUID()));

        assertThatThrownBy(() -> controller.getChargeById(chargeId, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Staff with ASSIGNED scope is not assigned to booking");
                });
    }

    @Test
    @DisplayName("P0-03 Staff Global Override: ROLE_ADMIN có ALL scope xem được mọi cơ sở -> 200 OK")
    void getChargeById_AdminUser_Returns200() {
        Jwt jwt = createJwt("ADMIN:" + UUID.randomUUID(), null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchB, staffUserId));

        ResponseEntity<BookingChargeResponse> response = controller.getChargeById(chargeId, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("P0 Write Scope: Token có quyền write nhưng thiếu Data Scope (không ALL/BRANCH/OWN/ASSIGNED) -> 403 Forbidden")
    void createCharge_MissingDataScope_Throws403() {
        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.of(branchA));

        Jwt jwt = createJwt("ADMIN:" + staffUserId, Map.of("scope", "payment:write"));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("SCOPE_payment:write"))));
        SecurityContextHolder.setContext(context);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-TEST-W1", financeRequestId, bookingId, branchA, customerId,
                ChargeType.DEPOSIT, new BigDecimal("500000.00"), "VND",
                Instant.now().plusSeconds(3600), "Deposit", "IDEM-W1"
        );

        assertThatThrownBy(() -> controller.createCharge(cmd, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Missing valid write data scope");
                });
    }

    @Test
    @DisplayName("P0 Write Scope: Nhân viên BRANCH scope tạo charge với branchId null -> 403 Forbidden")
    void createCharge_BranchScope_NullBranchId_Throws403() {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:write", "s", "BRANCH")),
                "bld", List.of(branchA.toString())
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-TEST-W2", financeRequestId, bookingId, null, customerId,
                ChargeType.DEPOSIT, new BigDecimal("500000.00"), "VND",
                Instant.now().plusSeconds(3600), "Deposit", "IDEM-W2"
        );

        assertThatThrownBy(() -> controller.createCharge(cmd, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Staff with BRANCH scope cannot modify charges without an assigned branch");
                });
    }

    @Test
    @DisplayName("P0 Write Scope: Nhân viên BRANCH scope tạo charge cho branch khác quyền -> 403 Forbidden")
    void createCharge_BranchScope_WrongBranch_Throws403() {
        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.of(branchB));

        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:write", "s", "BRANCH")),
                "bld", List.of(branchA.toString())
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-TEST-W3", financeRequestId, bookingId, branchB, customerId,
                ChargeType.DEPOSIT, new BigDecimal("500000.00"), "VND",
                Instant.now().plusSeconds(3600), "Deposit", "IDEM-W3"
        );

        assertThatThrownBy(() -> controller.createCharge(cmd, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Staff is not authorized to modify charges for branch");
                });
    }

    @Test
    @DisplayName("P0 Write Scope: Nhân viên OWN scope tạo charge hợp lệ -> 201 Created")
    void createCharge_OwnScope_Valid_Returns201() {
        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.of(branchA));

        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:write", "s", "OWN"))
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-TEST-W4", financeRequestId, bookingId, branchA, customerId,
                ChargeType.DEPOSIT, new BigDecimal("500000.00"), "VND",
                Instant.now().plusSeconds(3600), "Deposit", "IDEM-W4"
        );

        when(bookingChargeService.createCharge(cmd, staffUserId)).thenReturn(createSampleChargeResponse(customerId, branchA, staffUserId));

        ResponseEntity<BookingChargeResponse> response = controller.createCharge(cmd, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("P0 Write Scope: Nhân viên ASSIGNED scope tạo charge khi được phân công booking -> 201 Created")
    void createCharge_AssignedScope_Assigned_Returns201() {
        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.of(branchA));
        when(bookingBranchResolver.isBookingAssignedTo(bookingId, staffUserId)).thenReturn(true);

        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:write", "s", "ASSIGNED"))
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-TEST-ASSIGNED", financeRequestId, bookingId, branchA, customerId,
                ChargeType.DEPOSIT, new BigDecimal("500000.00"), "VND",
                Instant.now().plusSeconds(3600), "Deposit", "IDEM-ASSIGNED"
        );

        when(bookingChargeService.createCharge(cmd, staffUserId)).thenReturn(createSampleChargeResponse(customerId, branchA, staffUserId));

        ResponseEntity<BookingChargeResponse> response = controller.createCharge(cmd, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("P0 Write Scope: Nhân viên ASSIGNED scope tạo charge khi KHÔNG được phân công booking -> 403 Forbidden")
    void createCharge_AssignedScope_NotAssigned_Throws403() {
        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.of(branchA));
        when(bookingBranchResolver.isBookingAssignedTo(bookingId, staffUserId)).thenReturn(false);

        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:write", "s", "ASSIGNED"))
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-TEST-NOT-ASSIGNED", financeRequestId, bookingId, branchA, customerId,
                ChargeType.DEPOSIT, new BigDecimal("500000.00"), "VND",
                Instant.now().plusSeconds(3600), "Deposit", "IDEM-NOT-ASSIGNED"
        );

        assertThatThrownBy(() -> controller.createCharge(cmd, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Staff with ASSIGNED scope is not assigned to booking");
                });
    }

    @Test
    @DisplayName("F-01 Fix: Client gửi branchId mâu thuẫn với authoritative booking branch -> 400 Bad Request")
    void createCharge_ConflictingBranch_Throws400() {
        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.of(branchA));

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-TEST-CONFLICT", financeRequestId, bookingId, branchB, customerId,
                ChargeType.DEPOSIT, new BigDecimal("500000.00"), "VND",
                Instant.now().plusSeconds(3600), "Deposit", "IDEM-CONFLICT"
        );

        Jwt jwt = createJwt("ADMIN:" + staffUserId, Map.of(
                "grants", List.of(Map.of("p", "payment:write", "s", "ALL"))
        ));

        assertThatThrownBy(() -> controller.createCharge(cmd, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("conflicts with authoritative booking branch");
                });
    }

    @Test
    @DisplayName("P0 Branch Authority: Booking branch không tồn tại trong Booking Service -> 400 Bad Request fail-closed")
    void createCharge_UnresolvableBookingBranch_Throws400() {
        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.empty());

        CreateBookingChargeCommand cmd = new CreateBookingChargeCommand(
                "CHG-TEST-UNRESOLVABLE", financeRequestId, bookingId, branchA, customerId,
                ChargeType.DEPOSIT, new BigDecimal("500000.00"), "VND",
                Instant.now().plusSeconds(3600), "Deposit", "IDEM-UNRESOLVED"
        );

        Jwt jwt = createJwt("ADMIN:" + staffUserId, Map.of(
                "grants", List.of(Map.of("p", "payment:write", "s", "ALL"))
        ));

        assertThatThrownBy(() -> controller.createCharge(cmd, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("Authoritative booking branch cannot be resolved");
                });
    }

    @Test
    @DisplayName("P0 Write Scope: Nhân viên OWN scope miễn giảm (waive) charge do người khác tạo -> 403 Forbidden")
    void waiveCharge_OwnScope_OtherCreator_Throws403() {
        UUID otherStaff = UUID.randomUUID();
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:write", "s", "OWN"))
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_FINANCE"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchA, otherStaff));

        WaiveBookingChargeCommand cmd = new WaiveBookingChargeCommand(new BigDecimal("50000.00"), "Customer loyalty discount");

        assertThatThrownBy(() -> controller.waiveCharge(chargeId, cmd, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Staff with OWN scope cannot modify charges created by another actor");
                });
    }

    @Test
    @DisplayName("P1 Read Scope: getChargesByBooking phát hiện bản ghi thứ hai vi phạm branch scope -> 403 Forbidden")
    void getChargesByBooking_SecondChargeViolatesBranchScope_Throws403() {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:read", "s", "BRANCH")),
                "bld", List.of(branchA.toString())
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        SecurityContextHolder.setContext(context);

        BookingChargeResponse charge1 = createSampleChargeResponse(customerId, branchA, staffUserId);
        BookingChargeResponse charge2 = createSampleChargeResponse(customerId, branchB, staffUserId);
        when(bookingChargeService.getChargesByBooking(bookingId)).thenReturn(List.of(charge1, charge2));

        assertThatThrownBy(() -> controller.getChargesByBooking(bookingId, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Staff is not authorized for branch");
                });
    }

    @Test
    @DisplayName("P1 Read Scope: getBookingOpenBalance phát hiện charge vi phạm customer scope -> 403 Forbidden")
    void getBookingOpenBalance_ChargeViolatesCustomerScope_Throws403() {
        UUID otherCustomer = UUID.randomUUID();
        Jwt jwt = createJwt("CUSTOMER:" + customerId, null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);

        BookingChargeResponse otherCharge = createSampleChargeResponse(otherCustomer, branchA, staffUserId);
        BookingOpenBalanceSummaryResponse summary = new BookingOpenBalanceSummaryResponse(
                bookingId, new BigDecimal("500000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("500000.00"), "VND", 1, List.of(otherCharge)
        );
        when(bookingChargeService.getBookingOpenBalanceSummary(bookingId)).thenReturn(summary);

        assertThatThrownBy(() -> controller.getBookingOpenBalance(bookingId, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Cannot access booking charges belonging to another customer");
                });
    }
}
