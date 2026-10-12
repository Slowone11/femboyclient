package com.femboyclient;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remembering blocks, in made-up stretches of solid stone with tunnels carved through them. */
class HollowsTunnelsTest {

	/** Solid stone over chunks 18..29 in x and z, with whatever is carved out of it. Also the "live" blocks of a fake game. */
	static final class Stone implements HollowsPath.Blocks {
		static final int LOW = 18, HIGH = 29;
		final byte[][][] chunks = new byte[(HIGH - LOW + 1) * (HIGH - LOW + 1)][][];

		Stone() {
			for (int i = 0; i < chunks.length; i++) {
				chunks[i] = new byte[HollowsTunnels.SECTIONS][4096];
				for (byte[] section : chunks[i]) {
					java.util.Arrays.fill(section, HollowsTunnels.SOLID);
				}
			}
		}

		/** Air in this box, both ends included. */
		Stone carve(int x0, int y0, int z0, int x1, int y1, int z1) {
			for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
				for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
					for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
						set(x, y, z, HollowsTunnels.OPEN);
					}
				}
			}
			return this;
		}

		/** A tunnel 3 wide and 3 tall with its floor at y 99, from one point to another along x or z. */
		Stone tunnel(int x0, int z0, int x1, int z1) {
			return carve(x0 - 1, 100, z0 - 1, x1 + 1, 102, z1 + 1);
		}

		void set(int x, int y, int z, byte code) {
			chunks[chunk(x >> 4, z >> 4)][(y >> 4) - HollowsTunnels.LOW_SECTION][((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] = code;
		}

		private static int chunk(int chunkX, int chunkZ) {
			return (chunkZ - LOW) * (HIGH - LOW + 1) + (chunkX - LOW);
		}

		@Override
		public HollowsPath.Cell at(int x, int y, int z) {
			int cx = x >> 4, cz = z >> 4;
			if (cx < LOW || cx > HIGH || cz < LOW || cz > HIGH || y < HollowsTunnels.MIN_Y || y > HollowsTunnels.MAX_Y) {
				return HollowsPath.Cell.UNLOADED;
			}
			byte code = chunks[chunk(cx, cz)][(y >> 4) - HollowsTunnels.LOW_SECTION][((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
			return code == HollowsTunnels.OPEN ? HollowsPath.Cell.OPEN : HollowsPath.Cell.SOLID;
		}

		/** Hands every chunk to the tunnels, the way the game does as they load. */
		HollowsTunnels learnAll(HollowsTunnels tunnels) {
			for (int cx = LOW; cx <= HIGH; cx++) {
				for (int cz = LOW; cz <= HIGH; cz++) {
					tunnels.record(cx, cz, chunks[chunk(cx, cz)]);
				}
			}
			return tunnels;
		}
	}

	/** A U: east from A is solid stone; the tunnel goes south, east, and north again to B. */
	static Stone uTunnel() {
		return new Stone()
				.tunnel(325, 325, 325, 410)
				.tunnel(325, 410, 420, 410)
				.tunnel(420, 325, 420, 410);
	}

	@Test
	void remembersTheBlocks() {
		HollowsTunnels tunnels = uTunnel().learnAll(new HollowsTunnels());
		assertEquals(HollowsPath.Cell.OPEN, tunnels.at(325, 100, 330));
		assertEquals(HollowsPath.Cell.SOLID, tunnels.at(325, 99, 330));
		assertEquals(HollowsPath.Cell.SOLID, tunnels.at(370, 100, 325), "between the arms of the U");
		assertEquals(HollowsPath.Cell.UNLOADED, tunnels.at(600, 100, 600), "never loaded");
		assertTrue(tunnels.recorded(20, 20));
		assertFalse(tunnels.recorded(40, 40));
	}

	@Test
	void forgetsEverythingInANewLobby() {
		HollowsTunnels tunnels = uTunnel().learnAll(new HollowsTunnels());
		tunnels.reset();
		assertEquals(HollowsPath.Cell.UNLOADED, tunnels.at(325, 100, 330));
		assertFalse(tunnels.recorded(20, 20));
	}
}
