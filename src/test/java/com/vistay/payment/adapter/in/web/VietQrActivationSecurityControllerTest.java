package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.*;
import com.vistay.payment.domain.PaymentActivationMode;
import com.vistay.payment.domain.VietQrPlanStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class VietQrActivationSecurityControllerTest {

    @Mock
    private VietQrActivationPlanService planService;

    @InjectMocks
    private VietQrActivationController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
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

    private RequestPostProcessor withJwt(String subject, String permission) {
        return request -> {
            var jwtBuilder = Jwt.withTokenValue("mock-jwt-token")
                    .header("alg", "none")
                    .subject(subject)
                    .claim("aud", List.of("vistay-services"))
                    .claim("account_type", "ADMIN")
                    .claim("grants", List.of(Map.of("p", permission, "s", "ALL")));

            Jwt jwt = jwtBuilder.build();
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("SCOPE_" + permission));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, authorities));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    @Test
    @DisplayName("Request without JWT token is rejected with 401 Unauthorized")
    void unauthenticatedRequest_Returns401() throws Exception {
        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "planCode": "PLAN-01",
                                  "merchantCode": "M01",
                                  "merchantName": "Merchant",
                                  "bankBin": "970422",
                                  "bankName": "MB",
                                  "bankAccountNumber": "123",
                                  "bankAccountName": "TEST",
                                  "targetActivationMode": "GENERATE_ONLY"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Request missing required grant is rejected with 403 Forbidden")
    void missingGrant_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans")
                        .with(withJwt("ADMIN:102", "some:unrelated:permission"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "planCode": "PLAN-01",
                                  "merchantCode": "M01",
                                  "merchantName": "Merchant",
                                  "bankBin": "970422",
                                  "bankName": "MB",
                                  "bankAccountNumber": "123",
                                  "bankAccountName": "TEST",
                                  "targetActivationMode": "GENERATE_ONLY"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Create plan extracts actor ID directly from JWT sub and ignores client spoofing")
    void createPlan_ExtractsActorFromJwt() throws Exception {
        UUID planId = UUID.randomUUID();
        VietQrActivationPlanResponse mockResponse = createMockResponse(planId, "ADMIN:102", VietQrPlanStatus.DRAFT);
        when(planService.createPlan(any(CreateActivationPlanCommand.class), eq("ADMIN:102")))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans")
                        .with(withJwt("ADMIN:102", "vietqr:plan:create"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "planCode": "PLAN-01",
                                  "merchantCode": "M01",
                                  "merchantName": "Merchant",
                                  "bankBin": "970422",
                                  "bankName": "MB",
                                  "bankAccountNumber": "123",
                                  "bankAccountName": "TEST",
                                  "targetActivationMode": "GENERATE_ONLY"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByAdminUserId").value("ADMIN:102"));

        verify(planService).createPlan(any(CreateActivationPlanCommand.class), eq("ADMIN:102"));
    }

    @Test
    @DisplayName("Approve grant alone cannot activate plan (Decoupled permissions)")
    void approveGrant_CannotActivate_Returns403() throws Exception {
        UUID planId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans/{id}/activate", planId)
                        .with(withJwt("ADMIN:202", "vietqr:plan:approve")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Activate grant alone cannot approve plan (Decoupled permissions)")
    void activateGrant_CannotApprove_Returns403() throws Exception {
        UUID planId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans/{id}/approve", planId)
                        .with(withJwt("ADMIN:303", "vietqr:plan:activate")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Approve plan passes authenticated checker actor identity from JWT")
    void approvePlan_PassesCheckerActor() throws Exception {
        UUID planId = UUID.randomUUID();
        VietQrActivationPlanResponse mockResponse = createMockResponse(planId, "ADMIN:102", VietQrPlanStatus.APPROVED);
        when(planService.approvePlan(planId, "ADMIN:202")).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans/{id}/approve", planId)
                        .with(withJwt("ADMIN:202", "vietqr:plan:approve")))
                .andExpect(status().isOk());

        verify(planService).approvePlan(planId, "ADMIN:202");
    }

    @Test
    @DisplayName("Maker-Checker violation in service returns 409 Conflict")
    void approvePlan_MakerCheckerViolation_Returns409() throws Exception {
        UUID planId = UUID.randomUUID();
        when(planService.approvePlan(planId, "ADMIN:102"))
                .thenThrow(new IllegalStateException("Maker-Checker violation: Creator cannot approve their own VietQR activation plan"));

        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans/{id}/approve", planId)
                        .with(withJwt("ADMIN:102", "vietqr:plan:approve")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Activate plan passes authenticated operator actor identity from JWT")
    void activatePlan_PassesOperatorActor() throws Exception {
        UUID planId = UUID.randomUUID();
        VietQrActivationPlanResponse mockResponse = createMockResponse(planId, "ADMIN:102", VietQrPlanStatus.ACTIVE);
        when(planService.activatePlan(planId, "ADMIN:303")).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans/{id}/activate", planId)
                        .with(withJwt("ADMIN:303", "vietqr:plan:activate")))
                .andExpect(status().isOk());

        verify(planService).activatePlan(planId, "ADMIN:303");
    }

    private VietQrActivationPlanResponse createMockResponse(UUID id, String creator, VietQrPlanStatus status) {
        return new VietQrActivationPlanResponse(
                id,
                "PLAN-01",
                "M01",
                "Merchant",
                "970422",
                "MB",
                "123",
                "TEST",
                PaymentActivationMode.GENERATE_ONLY,
                status,
                false,
                null,
                null,
                null,
                null,
                false,
                creator,
                Instant.now(),
                null,
                null,
                null,
                0,
                Instant.now(),
                Instant.now()
        );
    }
}
