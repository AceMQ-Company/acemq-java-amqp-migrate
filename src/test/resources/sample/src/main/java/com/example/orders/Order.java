package com.example.orders;

/** No messaging API here: basicPublish( in a file that imports nothing must not count. */
public record Order(String id) {
    void basicPublish() {
    }
}
