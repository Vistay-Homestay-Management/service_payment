package com.vistay.payment.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServiceInfoQueryTest {
    @Test
    void returnsPaymentIdentity() {
        var result = new ServiceInfoQuery().serviceInfo();
        assertThat(result.name()).isEqualTo("payment");
        assertThat(result.version()).isEqualTo("0.1.0");
    }
}
