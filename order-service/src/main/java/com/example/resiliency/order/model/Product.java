package com.example.resiliency.order.model;

/** order-service's own view of a product, decoupled from inventory-service's schema on purpose. */
public record Product(String id, String name, int stock, double price) {
}
