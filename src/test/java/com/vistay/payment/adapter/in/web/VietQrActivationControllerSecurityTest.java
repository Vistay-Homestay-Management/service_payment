package com.vistay.payment.adapter.in.web;

import com.vistay.payment.application.BookingBranchResolver;
import com.vistay.payment.application.BookingChargeAuthorizationService;
import com.vistay.payment.application.VietQrActivationPlanResponse;
import com.vistay.payment.application.VietQrActivationPlanService;
import com.vistay.payment.config.SecurityConfig;
import com.vistay.payment.domain.PaymentActivationMode;
import com.vistay.payment.domain.VietQrPlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = {
        VietQrActivationControllerSecurityTest.TestWebConfig.class,
        VietQrActivationController.class,
        SecurityConfig.class,
        BookingChargeAuthorizationService.class
    },
    properties = {
        "spring.main.banner-mode=off",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080/realms/test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8080/certs",
        "vistay.security.audience=vistay-services"
    }
)
class VietQrActivationControllerSecurityTest {

    @Configuration
    @EnableWebMvc
    @Import({VietQrActivationController.class, SecurityConfig.class, BookingChargeAuthorizationService.class})
    static class TestWebConfig {}

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @MockitoBean
    private VietQrActivationPlanService activationPlanService;

    @MockitoBean
    private BookingChargeAuthorizationService authorizationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID planId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(authorizationService.requireAll(any(), any(String.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing permission"));
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private String allow(String permission) {
        String actor = adminUserId.toString();
        org.mockito.Mockito.doReturn(actor).when(authorizationService)
                .requireAll(any(), eq(permission));
        return actor;
    }

    private VietQrActivationPlanResponse samplePlanResponse(VietQrPlanStatus status) {
        return new VietQrActivationPlanResponse(
                planId, "PLAN-2026-001", "MERCHANT01", "ViStay Homestay",
                "970422", "MBBank", "0123456789", "VISTAY HOMESTAY",
                PaymentActivationMode.GENERATE_ONLY, status,
                false, null, null, null, null, false,
                "ADMIN:" + adminUserId, null, null, null, null,
                0L, Instant.now(), Instant.now()
        );
    }

    @Test
    @DisplayName("P0-04 VietQR Security: Anonymous access to activate plan -> 401 Unauthorized")
    void activatePlan_Anonymous_Returns401() throws Exception {
        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans/" + planId + "/activate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("P0-04 VietQR Security: Caller without vietqr:plan:activate -> 403 Forbidden")
    void activatePlan_UnauthorizedRole_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans/" + planId + "/activate")
                        .with(jwt().jwt(j -> j.subject("USER:" + UUID.randomUUID()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P0-04 Elimination of Admin Bypass: ROLE_ADMIN without vietqr:plan:activate -> 403 Forbidden")
    void activatePlan_AdminWithoutActivationGrant_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans/" + planId + "/activate")
                        .with(jwt().jwt(j -> j.subject("ADMIN:" + adminUserId))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P0-04 VietQR Security: Request operatorId cannot override authenticated actor")
    void activatePlan_RequestOperatorIdCannotOverrideAuthenticatedActor() throws Exception {
        UUID spoofedOperatorId = UUID.randomUUID();
        String authenticatedActor = allow("vietqr:plan:activate");
        when(activationPlanService.activatePlan(planId, authenticatedActor))
                .thenReturn(samplePlanResponse(VietQrPlanStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans/" + planId + "/activate")
                        .param("operatorId", spoofedOperatorId.toString())
                        .with(jwt().jwt(j -> j.subject(adminUserId.toString()).claim("account_type", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_vietqr:plan:activate"))))
                .andExpect(status().isOk());

        verify(activationPlanService).activatePlan(planId, authenticatedActor);
    }

    @Test
    @DisplayName("P0-04 VietQR Security: Valid caller with vietqr:plan:activate activates plan -> 200 OK")
    void activatePlan_ValidCaller_Success() throws Exception {
        String authenticatedActor = allow("vietqr:plan:activate");
        when(activationPlanService.activatePlan(planId, authenticatedActor))
                .thenReturn(samplePlanResponse(VietQrPlanStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/payment/vietqr/activation-plans/" + planId + "/activate")
                        .with(jwt().jwt(j -> j.subject(adminUserId.toString()).claim("account_type", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_vietqr:plan:activate"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(activationPlanService).activatePlan(planId, authenticatedActor);
    }

    @Test
    @DisplayName("P0-04 VietQR Security: Anonymous access to list plans -> 401 Unauthorized")
    void listPlans_Anonymous_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/payment/vietqr/activation-plans"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("P0-04 VietQR Security: User without read permission -> 403 Forbidden")
    void listPlans_UserWithoutReadPermission_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/payment/vietqr/activation-plans")
                        .with(jwt().jwt(j -> j.subject("USER:" + UUID.randomUUID()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P0-04 Elimination of Admin Bypass: ROLE_ADMIN without read grant -> 403 Forbidden")
    void getActivePlan_AdminWithoutReadGrant_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/payment/vietqr/activation-plans/active")
                        .with(jwt().jwt(j -> j.subject("ADMIN:" + adminUserId))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P0-04 VietQR Security: Caller with vietqr:plan:read reads active plan -> 200 OK")
    void getActivePlan_WithReadPermission_Returns200() throws Exception {
        allow("vietqr:plan:read");
        when(activationPlanService.getActivePlan())
                .thenReturn(Optional.of(samplePlanResponse(VietQrPlanStatus.ACTIVE)));

        mockMvc.perform(get("/api/v1/payment/vietqr/activation-plans/active")
                        .with(jwt().jwt(j -> j.subject("ADMIN:" + adminUserId))
                                .authorities(new SimpleGrantedAuthority("SCOPE_vietqr:plan:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("PLAN-2026-001"));
    }
}

