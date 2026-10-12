package com.femboyclient;

import java.util.UUID;

/** A player in your lobby: the username and the UUID the game lists them with. */
public record LobbyPlayer(String name, UUID id) {
}
