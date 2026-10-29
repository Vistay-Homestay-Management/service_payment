package com.vistay.payment.adapter.in.grpc;

import com.google.protobuf.Timestamp;
import com.vistay.contracts.payment.v1.CancelPaymentIntentRequest;
import com.vistay.contracts.payment.v1.CancelPaymentIntentResponse;
import com.vistay.contracts.payment.v1.CreateVietQrIntentRequest;
import com.vistay.contracts.payment.v1.CreateVietQrIntentResponse;
import com.vistay.contracts.payment.v1.GetPaymentIntentRequest;
import com.vistay.contracts.payment.v1.GetPaymentIntentResponse;
import com.vistay.contracts.payment.v1.PaymentIntent;
import com.vistay.payment.application.PaymentIntentService;
import com.vistay.payment.application.PaymentIntentView;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class PaymentIntentGrpcService extends com.vistay.contracts.payment.v1.PaymentIntentServiceGrpc.PaymentIntentServiceImplBase {
    private final PaymentIntentService service;

    public PaymentIntentGrpcService(PaymentIntentService service) {
        this.service = service;
    }

    @Override
    public void createVietQrIntent(
            CreateVietQrIntentRequest request,
            StreamObserver<CreateVietQrIntentResponse> observer) {
        execute(observer,
                intent -> CreateVietQrIntentResponse.newBuilder().setPaymentIntent(intent).build(),
                () -> service.create(
                        UUID.fromString(request.getFinancePaymentRequestId()), request.getIdempotencyKey()));
    }

    @Override
    public void getPaymentIntent(
            GetPaymentIntentRequest request,
            StreamObserver<GetPaymentIntentResponse> observer) {
        execute(observer,
                intent -> GetPaymentIntentResponse.newBuilder().setPaymentIntent(intent).build(),
                () -> service.get(UUID.fromString(request.getPaymentIntentId())));
    }

    @Override
    public void cancelPaymentIntent(
            CancelPaymentIntentRequest request,
            StreamObserver<CancelPaymentIntentResponse> observer) {
        execute(observer,
                intent -> CancelPaymentIntentResponse.newBuilder().setPaymentIntent(intent).build(),
                () -> service.cancel(UUID.fromString(request.getPaymentIntentId()),
                        request.getExpectedVersion(), request.getReason()));
    }

    private static <T> void execute(
            StreamObserver<T> observer,
            Function<PaymentIntent, T> responseFactory,
            Operation operation) {
        try {
            observer.onNext(responseFactory.apply(toResponse(operation.run())));
            observer.onCompleted();
        } catch (IllegalArgumentException error) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(error.getMessage()).asRuntimeException());
        } catch (IllegalStateException error) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription(error.getMessage()).asRuntimeException());
        }
    }

    private static PaymentIntent toResponse(PaymentIntentView view) {
        var instant = view.expiresAt();
        var builder = PaymentIntent.newBuilder()
            .setPaymentIntentId(view.id().toString())
            .setIntentCode(view.intentCode())
            .setFinancePaymentRequestId(view.financePaymentRequestId().toString())
            .setAmountMinorUnits(new java.math.BigDecimal(view.amount()).longValueExact())
            .setCurrency(view.currency())
            .setStatus(view.status())
            .setExpiresAt(Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()))
            .setVersion(view.version());
        if (view.qrPayload() != null) builder.setQrPayload(view.qrPayload());
        if (view.qrImageDataUri() != null) builder.setQrImageDataUri(view.qrImageDataUri());
        return builder.build();
    }

    @FunctionalInterface
    private interface Operation { PaymentIntentView run(); }
}
