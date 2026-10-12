package com.femboyclient;

import java.time.Instant;

/**
 * One chat line we captured, with the time it arrived.
 *
 * @param time when the message reached the client
 * @param text the message with formatting codes stripped
 */
public record ChatMessage(Instant time, String text) {
}
