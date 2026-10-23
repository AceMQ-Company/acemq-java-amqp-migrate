package com.example.orders;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

public class LegacyBilling {

    public void run() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setAutomaticRecoveryEnabled(true);
        try (Connection c = factory.newConnection(); Channel ch = c.createChannel()) {
            ch.queueDeclare("billing", true, false, false, null);
            ch.basicQos(10);
            ch.txSelect();
            ch.basicPublish("", "billing", null, new byte[0]);
            ch.txCommit();
        }
    }
}
