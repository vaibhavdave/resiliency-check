package com.example.resiliency.inventory.failure;

public enum FailureMode {
    /** Always respond successfully, immediately. */
    OK,
    /** Always respond with HTTP 500. */
    ERROR,
    /** Sleep for {@code delayMs} then respond successfully. */
    SLOW,
    /** Sleep long enough to blow past any sane client timeout, then respond successfully. */
    TIMEOUT,
    /** Respond with HTTP 500 with probability {@code errorRate}, otherwise succeed immediately. */
    RANDOM
}
