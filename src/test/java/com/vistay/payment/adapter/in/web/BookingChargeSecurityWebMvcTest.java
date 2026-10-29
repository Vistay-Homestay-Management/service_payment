package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.BookingBranchResolver;
import com.vistay.payment.application.BookingChargeResponse;
import com.vistay.payment.application.BookingChargeService;
import com.vistay.payment.config.SecurityConfig;
import com.vistay.payment.domain.ChargeStatus;
import com.vistay.payment.domain.ChargeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = {
        BookingChargeSecurityWebMvcTest.TestWebConfig.class,
        BookingChargeController.class,
        SecurityConfig.class
    },
    properties = {
        "spring.main.banner-mode=off",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080/realms/test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8080/certs",
        "vistay.security.audience=vistay-services"
    }
)
class BookingChargeSecurityWebMvcTest {

    @Configuration
    @EnableWebMvc
    @Import({BookingChargeController.class, SecurityConfig.class})
    static class TestWebConfig {}

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @MockitoBean
    private BookingChargeService bookingChargeService;

    @MockitoBean
    private BookingBranchResolver bookingBranchResolver;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID chargeId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID branchA = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID staffUserId = UUID.randomUUID();
    private final UUID financeRequestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private BookingChargeResponse sampleResponse(UUID branchId) {
        return new BookingChargeResponse(
                chargeId, "CHG-SEC-001", financeRequestId, bookingId, branchId,
                customerId, ChargeType.DEPOSIT, new BigDecimal("500000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500000.00"),
                "VND", Instant.now(), ChargeStatus.OPEN, "Deposit", null, null,
                null, null, null, staffUserId,
                "IDEM-SEC-001", 0, Instant.now(), Instant.now()
        );
    }

    @Test
    @DisplayName("Spring Security: Request without Bearer token returns 401 Unauthorized via SecurityFilterChain")
    void requestWithoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/payment/booking-charges/{id}", chargeId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Spring Security: POST createCharge without SCOPE_payment:write returns 403 Forbidden via @PreAuthorize")
    void postCreateCharge_withoutRequiredAuthority_returns403ViaPreAuthorize() throws Exception {
        String json = """
            {
              "chargeCode": "CHG-TEST-SEC",
              "financePaymentRequestId": "%s",
              "bookingId": "%s",
              "branchId": "%s",
              "customerId": "%s",
              "chargeType": "DEPOSIT",
              "originalAmount": 500000.00,
              "currency": "VND",
              "dueDate": "%s",
              "description": "Deposit",
              "idempotencyKey": "IDEM-SEC-1"
            }
        """.formatted(financeRequestId, bookingId, branchA, customerId, Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/v1/payment/booking-charges")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_payment:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Spring Security: Internal applyPayment endpoint without SCOPE_internal:finance returns 403 Forbidden via @PreAuthorize")
    void postApplyPayment_withoutInternalScope_returns403ViaPreAuthorize() throws Exception {
        String json = """
            {
              "financeAllocationId": "%s",
              "paymentTransactionId": "%s",
              "paymentAmount": 500000.00,
              "note": "Payment"
            }
        """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/api/v1/payment/booking-charges/{id}/apply-payment", chargeId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_payment:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Spring Security: Internal applyPayment endpoint with SCOPE_internal:finance succeeds 200 OK")
    void postApplyPayment_withInternalFinanceScope_returns200() throws Exception {
        when(bookingChargeService.applyPayment(eq(chargeId), any(), any()))
                .thenReturn(sampleResponse(branchA));

        String json = """
            {
              "financeAllocationId": "%s",
              "paymentTransactionId": "%s",
              "paymentAmount": 500000.00,
              "note": "Payment"
            }
        """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/api/v1/payment/booking-charges/{id}/apply-payment", chargeId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_internal:finance")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeCode").value("CHG-SEC-001"));
    }

    @Test
    @DisplayName("F-01: Staff with BRANCH scope attempting createCharge when booking branch is unresolvable fails-closed with 403")
    void postCreateCharge_branchScope_unresolvableBookingBranch_returns403() throws Exception {
        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.empty());

        String json = """
            {
              "chargeCode": "CHG-TEST-SEC",
              "financePaymentRequestId": "%s",
              "bookingId": "%s",
              "branchId": "%s",
              "customerId": "%s",
              "chargeType": "DEPOSIT",
              "originalAmount": 500000.00,
              "currency": "VND",
              "dueDate": "%s",
              "description": "Deposit",
              "idempotencyKey": "IDEM-SEC-2"
            }
        """.formatted(financeRequestId, bookingId, branchA, customerId, Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/v1/payment/booking-charges")
                        .with(jwt()
                                .jwt(builder -> builder
                                        .subject("STAFF:" + staffUserId)
                                        .claim("data_scope", "BRANCH")
                                        .claim("bld", List.of(branchA.toString())))
                                .authorities(new SimpleGrantedAuthority("SCOPE_payment:write"),
                                             new SimpleGrantedAuthority("ROLE_RECEPTIONIST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("F-02: Staff with BRANCH scope accessing charge with NULL branch fails-closed with 403 Forbidden")
    void getChargeById_branchScope_nullBranchOnCharge_returns403() throws Exception {
        when(bookingChargeService.getChargeById(chargeId)).thenReturn(sampleResponse(null));

        mockMvc.perform(get("/api/v1/payment/booking-charges/{id}", chargeId)
                        .with(jwt()
                                .jwt(builder -> builder
                                        .subject("STAFF:" + staffUserId)
                                        .claim("data_scope", "BRANCH")
                                        .claim("bld", List.of(branchA.toString())))
                                .authorities(new SimpleGrantedAuthority("SCOPE_payment:read"),
                                             new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Spring Security: Staff with valid BRANCH scope and matching branch succeeds 200 OK")
    void getChargeById_branchScope_matchingBranch_returns200() throws Exception {
        when(bookingChargeService.getChargeById(chargeId)).thenReturn(sampleResponse(branchA));

        mockMvc.perform(get("/api/v1/payment/booking-charges/{id}", chargeId)
                        .with(jwt()
                                .jwt(builder -> builder
                                        .subject("STAFF:" + staffUserId)
                                        .claim("data_scope", "BRANCH")
                                        .claim("bld", List.of(branchA.toString())))
                                .authorities(new SimpleGrantedAuthority("SCOPE_payment:read"),
                                             new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value(branchA.toString()));
    }
}

