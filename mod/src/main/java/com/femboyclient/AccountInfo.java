package com.femboyclient;

import java.util.UUID;

/**
 * Who is logged in to Minecraft on this PC.
 *
 * Only the public profile is ever read: the UUID and the name. The session
 * token that also lives on the Minecraft user object is never touched.
 */
public record AccountInfo(UUID uuid, String name) {
}
