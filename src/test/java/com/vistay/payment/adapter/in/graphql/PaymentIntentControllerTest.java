package com.vistay.payment.adapter.in.graphql;

import com.vistay.payment.application.BookingChargeAuthorizationService;
import com.vistay.payment.application.PaymentIntentAccess;
import com.vistay.payment.application.PaymentIntentService;
import com.vistay.payment.application.PaymentIntentView;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentIntentControllerTest {
    @Mock
    private PaymentIntentService service;
    @Mock
    private BookingChargeAuthorizationService authorization;

    @Test
    void readAuthorizesTheRecordBeforeReturningIt() {
        var id = UUID.randomUUID();
        var jwt = mock(Jwt.class);
        var access = new PaymentIntentAccess(UUID.randomUUID(), UUID.randomUUID());
        var view = view(id);
        when(service.access(id)).thenReturn(access);
        when(service.get(id)).thenReturn(view);

        var result = new PaymentIntentController(service, authorization).paymentIntent(id, jwt);

        assertSame(view, result);
        InOrder order = inOrder(service, authorization);
        order.verify(authorization).requirePermission(jwt, "payment:read");
        order.verify(service).access(id);
        order.verify(authorization).assertPaymentIntentAccess(access, jwt, "payment:read");
        order.verify(service).get(id);
    }

    @Test
    void deniedReadNeverLoadsTheSensitivePresentation() {
        var id = UUID.randomUUID();
        var jwt = mock(Jwt.class);
        var access = new PaymentIntentAccess(UUID.randomUUID(), UUID.randomUUID());
        when(service.access(id)).thenReturn(access);
        org.mockito.Mockito.doThrow(mock(ResponseStatusException.class))
            .when(authorization).assertPaymentIntentAccess(access, jwt, "payment:read");

        assertThrows(ResponseStatusException.class,
            () -> new PaymentIntentController(service, authorization).paymentIntent(id, jwt));

        verify(service, never()).get(id);
    }

    @Test
    void missingActionPermissionIsRejectedBeforeRecordLookup() {
        var id = UUID.randomUUID();
        var jwt = mock(Jwt.class);
        org.mockito.Mockito.doThrow(mock(ResponseStatusException.class))
            .when(authorization).requirePermission(jwt, "payment:read");

        assertThrows(ResponseStatusException.class,
            () -> new PaymentIntentController(service, authorization).paymentIntent(id, jwt));

        verify(service, never()).access(id);
        verify(service, never()).get(id);
    }

    @Test
    void createAndCancelUseExactCatalogActions() {
        var jwt = mock(Jwt.class);
        var financeRequestId = UUID.randomUUID();
        var paymentIntentId = UUID.randomUUID();
        var createAccess = new PaymentIntentAccess(UUID.randomUUID(), UUID.randomUUID());
        var cancelAccess = new PaymentIntentAccess(UUID.randomUUID(), UUID.randomUUID());
        when(service.accessForCreate(financeRequestId, "idem-1")).thenReturn(createAccess);
        when(service.access(paymentIntentId)).thenReturn(cancelAccess);

        var controller = new PaymentIntentController(service, authorization);
        controller.createVietQrPaymentIntent(
            new PaymentIntentController.CreateInput(financeRequestId, "idem-1"), jwt);
        controller.cancelPaymentIntent(
            new PaymentIntentController.CancelInput(paymentIntentId, 2, "Customer request"), jwt);

        verify(authorization).assertPaymentIntentAccess(createAccess, jwt, "payment:create");
        verify(authorization).assertPaymentIntentAccess(cancelAccess, jwt, "payment:cancel");
        verify(authorization).requirePermission(jwt, "payment:create");
        verify(authorization).requirePermission(jwt, "payment:cancel");
    }

    private static PaymentIntentView view(UUID id) {
        return new PaymentIntentView(id, "VT123", UUID.randomUUID(), "10000", "VND",
            "QR_READY", "payload", "data:image/png;base64,x", Instant.now().plusSeconds(60), 1);
    }
}
