package com.rentflow.event;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The topology: one topic exchange, one consumer queue, and a dead-letter queue behind it.
 *
 * A topic exchange rather than sending straight to a queue, because the publisher must not
 * know who listens. Day 19's realtime push binds another queue to the same events and
 * nothing about publishing changes.
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "rentflow.events";
    public static final String NOTIFICATIONS_QUEUE = "rentflow.notifications";

    /** Where messages go when the consumer keeps rejecting them — see the queue below. */
    public static final String DEAD_LETTER_EXCHANGE = "rentflow.events.dlx";
    public static final String DEAD_LETTER_QUEUE = "rentflow.notifications.dlq";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * Durable, and dead-lettered rather than infinitely redelivered. A message the consumer
     * can never handle would otherwise loop forever and starve every message behind it.
     */
    @Bean
    public Queue notificationsQueue() {
        return QueueBuilder.durable(NOTIFICATIONS_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(NOTIFICATIONS_QUEUE)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    /** Notifications care about everything we announce, hence the "#" wildcard. */
    @Bean
    public Binding notificationsBinding(Queue notificationsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(eventsExchange).with("#");
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(NOTIFICATIONS_QUEUE);
    }

    /**
     * JSON on the wire, not Java serialization: readable in the RabbitMQ console, and not a
     * remote-code-execution hole. Trusted packages are pinned for the same reason.
     */
    @Bean
    public MessageConverter eventMessageConverter() {
        DefaultJacksonJavaTypeMapper typeMapper = new DefaultJacksonJavaTypeMapper();
        typeMapper.setTrustedPackages("com.rentflow.event");

        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
