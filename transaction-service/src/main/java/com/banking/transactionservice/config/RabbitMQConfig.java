package com.banking.transactionservice.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    /*
     * Shared topology constants.
     * Both services must agree on these names — they are the messaging contract.
     * The producer declares the exchange; the consumer declares the queue and binding.
     */
    public static final String EXCHANGE          = "banking.transactions";
    public static final String ROUTING_COMPLETED = "transaction.completed";
    public static final String ROUTING_FAILED    = "transaction.failed";

    /**
     * Topic exchange — chosen over direct exchange because routing-key patterns
     * let future consumers subscribe selectively.
     * Example: an audit-service could bind to "transaction.#" (all outcomes)
     * while an alerting-service binds only to "transaction.failed".
     *
     * durable=true  : exchange survives broker restart — no messages lost on restart.
     * autoDelete=false : exchange is not deleted when the last consumer unbinds.
     */
    @Bean
    public TopicExchange transactionExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /**
     * Serialize all AMQP messages as JSON.
     * Spring Boot detects this bean and auto-wires it into RabbitTemplate,
     * so every convertAndSend() call produces a JSON payload automatically.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
