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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingChargeControllerTest {

    @Mock
    private BookingChargeService bookingChargeService;

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

        // Khoản thu thuộc branchB
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
    @DisplayName("P0-03 Fail-closed Identity: JWT sub không phải UUID -> 401 Unauthorized")
    void getChargeById_InvalidSubClaim_Throws401() {
        Jwt jwt = createJwt("invalid-sub-string", null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);

        when(bookingChargeService.getChargeById(chargeId)).thenReturn(createSampleChargeResponse(customerId, branchA, staffUserId));

        assertThatThrownBy(() -> controller.getChargeById(chargeId, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(rse.getReason()).contains("Principal subject is not a valid UUID");
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
}
