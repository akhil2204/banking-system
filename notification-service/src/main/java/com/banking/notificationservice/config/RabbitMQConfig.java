package com.banking.notificationservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // Must match transaction-service's RabbitMQConfig constants exactly
    public static final String EXCHANGE    = "banking.transactions";
    public static final String QUEUE       = "notification.transaction.queue";
    public static final String BINDING_KEY = "transaction.#"; // matches transaction.completed AND transaction.failed

    // Dead letter infrastructure for messages that fail processing
    public static final String DLX = "banking.transactions.dlx";
    public static final String DLQ = "notification.transaction.dlq";

    /** Declare the same exchange as transaction-service — idempotent, safe. */
    @Bean
    public TopicExchange transactionExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /**
     * Main queue where notification-service receives events.
     *
     * durable=true: queue survives RabbitMQ restart — no messages lost.
     * x-dead-letter-exchange: messages that throw an exception in the listener
     *   are NACKed and routed here instead of being requeued forever.
     *   This prevents a poison-pill message from blocking the entire queue.
     */
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .build();
    }

    /**
     * Bind queue to exchange with a wildcard routing key.
     * "transaction.#" matches transaction.completed, transaction.failed,
     * and any new routing keys added later — zero config changes needed.
     */
    @Bean
    public Binding notificationBinding(Queue notificationQueue, TopicExchange transactionExchange) {
        return BindingBuilder.bind(notificationQueue).to(transactionExchange).with(BINDING_KEY);
    }

    /**
     * Dead letter exchange: receives messages that the listener rejected.
     * Keeps them for inspection rather than silently dropping them.
     */
    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(DLX, true, false);
    }

    /** Dead letter queue: holds failed events for manual inspection or replay. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#");
    }

    /** JSON converter — both producer and consumer must use the same format. */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Wire the JSON converter into the listener container factory.
     *
     * Why explicit factory config?
     * Spring Boot auto-configures RabbitTemplate with a MessageConverter bean,
     * but the listener container factory needs explicit wiring to use the same
     * converter for deserializing incoming messages.
     *
     * defaultRequeueRejected=false: when the listener throws an exception, NACK
     * without requeue → message goes to DLQ.  Default (true) would requeue it
     * indefinitely, causing an infinite retry loop on a bad message.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
