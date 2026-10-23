package com.example.orders;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.Session;

public class JmsBridge {

    public void send(ConnectionFactory cf) {
        try (JMSContext ctx = cf.createContext(Session.SESSION_TRANSACTED)) {
            ctx.createConsumer(ctx.createQueue("in"), "region = 'EU'");
            ctx.commit();
        }
    }
}
