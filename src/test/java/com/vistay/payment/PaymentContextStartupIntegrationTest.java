package com.vistay.payment;

import com.vistay.payment.adapter.in.web.BookingChargeController;
import com.vistay.payment.adapter.out.persistence.DefaultBookingBranchResolver;
import com.vistay.payment.application.BookingBranchResolver;
import com.vistay.payment.application.BookingChargeAuthorizationService;
import com.vistay.payment.application.BookingChargeService;
import com.vistay.payment.application.PaymentAuthorizationClient;
import com.vistay.payment.domain.BookingChargeAdjustmentRepository;
import com.vistay.payment.domain.BookingChargeAllocationRepository;
import com.vistay.payment.domain.BookingChargeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = {
        BookingChargeService.class,
        BookingChargeController.class,
        DefaultBookingBranchResolver.class,
        BookingChargeAuthorizationService.class
    },
    properties = {
        "spring.main.banner-mode=off"
    }
)
class PaymentContextStartupIntegrationTest {

    @MockitoBean
    private BookingChargeRepository chargeRepository;

    @MockitoBean
    private BookingChargeAllocationRepository allocationRepository;

    @MockitoBean
    private BookingChargeAdjustmentRepository adjustmentRepository;

    @MockitoBean
    private PaymentAuthorizationClient paymentAuthorizationClient;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private BookingChargeService bookingChargeService;

    @Autowired
    private BookingChargeController bookingChargeController;

    @Autowired
    private BookingBranchResolver bookingBranchResolver;

    @Autowired
    private BookingChargeAuthorizationService authorizationService;

    @Test
    @DisplayName("Context Startup: BookingChargeService and Controller beans initialize cleanly with unambiguous DI")
    void contextLoads_andBeansInjectedCleanly() {
        assertThat(applicationContext).isNotNull();
        assertThat(bookingChargeService).isNotNull();
        assertThat(bookingChargeController).isNotNull();
        assertThat(bookingBranchResolver).isNotNull();
        assertThat(authorizationService).isNotNull();
    }
}

