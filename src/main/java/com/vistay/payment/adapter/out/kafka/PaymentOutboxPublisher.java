package com.vistay.payment.adapter.out.kafka;

import com.vistay.payment.domain.OutboxEventRepository;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentOutboxPublisher {
    private final OutboxEventRepository events;
    private final KafkaTemplate<String, Object> kafka;

    public PaymentOutboxPublisher(OutboxEventRepository events, KafkaTemplate<String, Object> kafka) {
        this.events = events;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${vistay.payment.outbox-poll-delay:1000}")
    @Transactional
    public void publishAvailable() {
        for (var event : events.findTop50ByPublishedAtIsNullAndAvailableAtBeforeOrderByOccurredAtAsc(Instant.now())) {
            var topic = event.getEventType() + ".v" + event.getEventVersion();
            try {
                kafka.send(topic, event.getAggregateId().toString(), event.getPayload()).get(5, TimeUnit.SECONDS);
                event.markPublished();
            } catch (Exception error) {
                event.markFailed(error);
            }
        }
    }
}
