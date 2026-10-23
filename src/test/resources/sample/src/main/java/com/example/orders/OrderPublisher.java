package com.example.orders;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

// A comment that mentions basicPublish( and convertAndSend( must not count.
@Service
public class OrderPublisher {

    private final RabbitTemplate template;

    public OrderPublisher(RabbitTemplate template) {
        this.template = template;
        template.setChannelTransacted(true);
    }

    public void place(Order order) {
        template.convertAndSend("orders", "orders.new", order);
    }

    public Object poll() {
        return template.receiveAndConvert("orders.audit");
    }
}
