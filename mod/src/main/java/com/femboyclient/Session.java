package com.femboyclient;

import java.util.UUID;

/**
 * One play session on SkyBlock.
 *
 * A session starts when the SkyBlock sidebar appears and ends when it goes away
 * (warping off SkyBlock, disconnecting, or quitting the game).
 *
 * @param id         random unique id for this session, used as the database key
 * @param playerUuid the account's UUID
 * @param playerName the account's name
 */
public record Session(String id, UUID playerUuid, String playerName) {

	/** Creates a session with a fresh random id. */
	public static Session start(UUID playerUuid, String playerName) {
		return new Session(UUID.randomUUID().toString(), playerUuid, playerName);
	}
}
