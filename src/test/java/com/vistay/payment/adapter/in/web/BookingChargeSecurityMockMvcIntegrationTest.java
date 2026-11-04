package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.BookingBranchResolver;
import com.vistay.payment.application.BookingChargeAuthorizationService;
import com.vistay.payment.application.BookingChargeResponse;
import com.vistay.payment.application.BookingChargeService;
import com.vistay.payment.application.PaymentAuthorizationClient;
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
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BookingChargeSecurityMockMvcIntegrationTest {

    @Mock
    private BookingChargeService bookingChargeService;

    @Mock
    private BookingBranchResolver bookingBranchResolver;

    @Mock
    private PaymentAuthorizationClient paymentAuthorizationClient;

    private BookingChargeAuthorizationService authorizationService;
    private BookingChargeController controller;

    private MockMvc mockMvc;

    private final UUID chargeId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID branchA = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID staffUserId = UUID.randomUUID();
    private final UUID financeRequestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        authorizationService = new BookingChargeAuthorizationService(
                bookingBranchResolver, paymentAuthorizationClient);
        controller = new BookingChargeController(bookingChargeService, bookingBranchResolver, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                        return (auth != null && auth.getPrincipal() instanceof Jwt jwt) ? jwt : null;
                    }
                })
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private RequestPostProcessor withJwt(Jwt jwt, List<GrantedAuthority> authorities) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, authorities));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    private Jwt createJwt(String subject, Map<String, Object> extraClaims) {
        String[] subjectParts = subject.split(":", 2);
        PaymentAuthorizationClient.AccountKind accountType = subjectParts.length == 2
                ? PaymentAuthorizationClient.AccountKind.valueOf(subjectParts[0].replace("STAFF", "ADMIN"))
                : PaymentAuthorizationClient.AccountKind.ADMIN;
        UUID accountId = UUID.fromString(subjectParts.length == 2 ? subjectParts[1] : subject);
        Map<String, Set<PaymentAuthorizationClient.Scope>> grants = new LinkedHashMap<>();
        Map<String, Set<PaymentAuthorizationClient.PartitionBinding>> partitions = new LinkedHashMap<>();
        if (accountType == PaymentAuthorizationClient.AccountKind.CUSTOMER) {
            grants.put("payment:read", Set.of(PaymentAuthorizationClient.Scope.OWN));
        }
        Object rawGrants = extraClaims == null ? null : extraClaims.get("grants");
        if (rawGrants instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> grant)) continue;
                String permission = Objects.toString(grant.get("p"), "");
                String rawScope = Objects.toString(grant.get("s"), "");
                if (permission.isBlank() || rawScope.isBlank()) continue;
                if ("BRANCH".equals(rawScope)) {
                    grants.put(permission, Set.of(PaymentAuthorizationClient.Scope.PARTITION));
                    List<String> branches = extraClaims.get("bld") instanceof List<?> values
                            ? values.stream().map(Object::toString).toList() : List.of();
                    partitions.put(permission, branches.stream()
                            .map(branch -> new PaymentAuthorizationClient.PartitionBinding(
                                    "BRANCH", UUID.fromString(branch)))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
                } else {
                    grants.put(permission, Set.of(PaymentAuthorizationClient.Scope.valueOf(rawScope)));
                }
            }
        }
        PaymentAuthorizationClient.Snapshot snapshot = new PaymentAuthorizationClient.Snapshot(
                true, accountId, 1L, Map.copyOf(grants), Map.copyOf(partitions));
        org.mockito.Mockito.lenient().when(paymentAuthorizationClient.get(accountType, accountId))
                .thenReturn(snapshot);
        org.mockito.Mockito.lenient().when(paymentAuthorizationClient.require(
                        eq(accountType), eq(accountId), any(String.class)))
                .thenAnswer(invocation -> {
                    String permission = invocation.getArgument(2);
                    if (snapshot.scopes(permission).isEmpty()) {
                        throw new PaymentAuthorizationClient.AuthorizationException(
                                "FORBIDDEN_PERMISSION", "Missing permission " + permission);
                    }
                    return snapshot;
                });
        var builder = Jwt.withTokenValue("mock-jwt-token")
                .header("alg", "none")
                .subject(accountId.toString())
                .claim("account_type", accountType.name())
                .claim("aud", List.of("vistay-services"));
        if (extraClaims != null) {
            extraClaims.forEach(builder::claim);
        }
        return builder.build();
    }

    private BookingChargeResponse sampleResponse(UUID ownerCustomerId, UUID branchId, UUID createdBy) {
        return new BookingChargeResponse(
                chargeId, "CHG-MVC-001", financeRequestId, bookingId, branchId,
                ownerCustomerId, ChargeType.DEPOSIT, new BigDecimal("500000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500000.00"),
                "VND", Instant.now(), ChargeStatus.OPEN, "Deposit", null, null,
                null, null, null, createdBy != null ? createdBy : staffUserId,
                "IDEM-MVC-001", 0, Instant.now(), Instant.now()
        );
    }

    @Test
    @DisplayName("HTTP MockMvc: Token có SCOPE_payment:create nhưng không có Data Scope -> 403 Forbidden")
    void postCreateCharge_WithoutDataScope_Returns403() throws Exception {
        Jwt jwt = createJwt("ADMIN:" + staffUserId, Map.of("scope", "payment:create"));
        String json = """
            {
              "chargeCode": "CHG-TEST-MVC",
              "financePaymentRequestId": "%s",
              "bookingId": "%s",
              "branchId": "%s",
              "customerId": "%s",
              "chargeType": "DEPOSIT",
              "originalAmount": 500000.00,
              "currency": "VND",
              "dueDate": "%s",
              "description": "Deposit",
              "idempotencyKey": "IDEM-MVC-1"
            }
        """.formatted(financeRequestId, bookingId, branchA, customerId, Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/v1/payment/booking-charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(withJwt(jwt, List.of(new SimpleGrantedAuthority("SCOPE_payment:create")))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("HTTP MockMvc: Nhân viên BRANCH scope tạo charge cho đúng chi nhánh được cấp -> 201 Created")
    void postCreateCharge_WithValidBranchScope_Returns201() throws Exception {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:create", "s", "BRANCH")),
                "bld", List.of(branchA.toString())
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        String json = """
            {
              "chargeCode": "CHG-TEST-MVC-OK",
              "financePaymentRequestId": "%s",
              "bookingId": "%s",
              "branchId": "%s",
              "customerId": "%s",
              "chargeType": "DEPOSIT",
              "originalAmount": 500000.00,
              "currency": "VND",
              "dueDate": "%s",
              "description": "Deposit",
              "idempotencyKey": "IDEM-MVC-2"
            }
        """.formatted(financeRequestId, bookingId, branchA, customerId, Instant.now().plusSeconds(3600));

        when(bookingBranchResolver.resolveBranchId(bookingId)).thenReturn(Optional.of(branchA));
        when(bookingChargeService.createCharge(any(), eq(staffUserId)))
                .thenReturn(sampleResponse(customerId, branchA, staffUserId));

        mockMvc.perform(post("/api/v1/payment/booking-charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(withJwt(jwt, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chargeCode").value("CHG-MVC-001"))
                .andExpect(jsonPath("$.branchId").value(branchA.toString()));
    }

    @Test
    @DisplayName("HTTP MockMvc: Nhân viên BRANCH scope tạo charge với branchId null -> 403 Forbidden")
    void postCreateCharge_BranchScopeWithNullBranch_Returns403() throws Exception {
        Map<String, Object> claims = Map.of(
                "grants", List.of(Map.of("p", "payment:create", "s", "BRANCH")),
                "bld", List.of(branchA.toString())
        );
        Jwt jwt = createJwt("ADMIN:" + staffUserId, claims);
        String json = """
            {
              "chargeCode": "CHG-TEST-MVC-NULL",
              "financePaymentRequestId": "%s",
              "bookingId": "%s",
              "customerId": "%s",
              "chargeType": "DEPOSIT",
              "originalAmount": 500000.00,
              "currency": "VND",
              "dueDate": "%s",
              "description": "Deposit",
              "idempotencyKey": "IDEM-MVC-3"
            }
        """.formatted(financeRequestId, bookingId, customerId, Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/v1/payment/booking-charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(withJwt(jwt, List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("HTTP MockMvc: Khách hàng truy cập đúng khoản thu của mình -> 200 OK")
    void getChargeById_CustomerOwnCharge_Returns200() throws Exception {
        Jwt jwt = createJwt("CUSTOMER:" + customerId, null);
        when(bookingChargeService.getChargeById(chargeId))
                .thenReturn(sampleResponse(customerId, branchA, staffUserId));

        mockMvc.perform(get("/api/v1/payment/booking-charges/" + chargeId)
                        .with(withJwt(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId.toString()));
    }

    @Test
    @DisplayName("HTTP MockMvc: Khách hàng truy cập khoản thu của khách khác -> 403 Forbidden")
    void getChargeById_CustomerOtherCharge_Returns403() throws Exception {
        UUID otherCustomerId = UUID.randomUUID();
        Jwt jwt = createJwt("CUSTOMER:" + customerId, null);
        when(bookingChargeService.getChargeById(chargeId))
                .thenReturn(sampleResponse(otherCustomerId, branchA, staffUserId));

        mockMvc.perform(get("/api/v1/payment/booking-charges/" + chargeId)
                        .with(withJwt(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                .andExpect(status().isForbidden());
    }
}
