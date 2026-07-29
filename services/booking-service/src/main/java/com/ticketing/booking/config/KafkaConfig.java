package com.ticketing.booking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic paymentInitiatedTopic() {
        return TopicBuilder.name("payment-initiated").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentCompletedTopic() {
        return TopicBuilder.name("payment-completed").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return TopicBuilder.name("payment-failed").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic ticketIssuedTopic() {
        return TopicBuilder.name("ticket-issued").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic seatReleasedTopic() {
        return TopicBuilder.name("seat-released").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic seatHoldExpiredTopic() {
        return TopicBuilder.name("seat-hold-expired").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bookingCancelledTopic() {
        return TopicBuilder.name("booking-cancelled").partitions(3).replicas(1).build();
    }
}
