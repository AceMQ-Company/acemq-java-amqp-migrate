package com.example.orders;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Queue;

public class OrderListener {

    public Queue ordersQueue() {
        return new Queue("orders.new", true);
    }

    @RabbitListener(queues = "orders.new", concurrency = "4")
    public void receive(Order order) {
        // handled
    }
}
