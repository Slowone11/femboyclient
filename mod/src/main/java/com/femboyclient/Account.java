package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/** Reads the account that launched the game. */
public final class Account {

	private Account() {
	}

	/**
	 * The logged-in account. This is the launcher account, so it is known from the
	 * moment the game starts, before any world or server is joined.
	 */
	public static AccountInfo current() {
		User user = Minecraft.getInstance().getUser();
		return new AccountInfo(user.getProfileId(), user.getName());
	}
}
