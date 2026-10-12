package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A 3D model of one lobby player, drawn with the game's own player renderer and their own skin. It turns slowly on its own
 * and leans its body and head towards the mouse.
 *
 * The player is rebuilt as a stand-in {@link RemotePlayer} from the profile in your tab list (which already carries their
 * skin), so the model works for anyone in the lobby, near or far. Nothing is sent anywhere and nothing is added to the world.
 * The render state is built when a player is selected and refreshed twice a second (to pick up a skin that was still loading),
 * and the angles are only changed in place each frame, so drawing reuses its objects.
 */
public final class PlayerModelView {

	/** An entity id no real entity has, for the stand-in player. It is never added to the world. */
	private static final int FAKE_ENTITY_ID = Integer.MAX_VALUE - 7;

	private static final Logger LOGGER = LoggerFactory.getLogger(FemboyClientMod.MOD_ID);

	private final Quaternionf rotation = new Quaternionf();
	private final Quaternionf camera = new Quaternionf();
	private final Vector3f offset = new Vector3f();

	private RemotePlayer entity;
	private EntityRenderState state;
	private long builtAt;
	private final long startedAt = System.nanoTime();

	/** Chooses who to show, or nobody for null. Never throws: a player who cannot be shown just gives no model. */
	public void select(LobbyPlayer player) {
		entity = null;
		state = null;
		if (player == null) {
			return;
		}
		try {
			Minecraft minecraft = Minecraft.getInstance();
			ClientPacketListener connection = minecraft.getConnection();
			ClientLevel level = minecraft.level;
			net.minecraft.client.multiplayer.PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.id());
			if (info != null && level != null) {
				entity = new RemotePlayer(level, info.getProfile());
				entity.setId(FAKE_ENTITY_ID);          // the renderer needs an id; this one is never used by a real entity
			}
		} catch (RuntimeException e) {
			LOGGER.warn("player screen: could not build the 3D model ({})", e.toString());
			entity = null;
		}
	}

	/** True when there is a model to draw. */
	public boolean hasModel() {
		return entity != null;
	}

	/** Draws the model centered in the box, turned towards the mouse. Never throws. */
	public void draw(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, float mouseX, float mouseY) {
		if (entity == null) {
			return;
		}
		try {
			long now = System.nanoTime();
			if (state == null || now - builtAt > Theme.MODEL_REFRESH_MS * 1_000_000L) {
				rebuild(now);
			}

			// How far the mouse is from the middle of the box, from -1 to 1 (it flattens out with distance).
			float towardsX = (float) (Math.atan(((x0 + x1) / 2f - mouseX) / 40f) / (Math.PI / 2));
			float towardsY = (float) (Math.atan(((y0 + y1) / 2f - mouseY) / 40f) / (Math.PI / 2));
			float spin = (now - startedAt) / 1_000_000_000f * Theme.MODEL_SPIN_DEGREES_PER_SECOND;

			if (state instanceof LivingEntityRenderState living) {
				living.bodyRot = 180f + spin + towardsX * Theme.MODEL_FOLLOW_DEGREES;
				living.yRot = towardsX * Theme.MODEL_FOLLOW_DEGREES * 0.5f;
				living.xRot = living.pose == Pose.FALL_FLYING ? 0f : -towardsY * Theme.MODEL_FOLLOW_DEGREES * 0.5f;
			}

			camera.identity().rotateX(towardsY * 20f * 0.017453292f);
			rotation.identity().rotateZ((float) Math.PI).mul(camera);
			offset.set(0f, state.boundingBoxHeight / 2f + 0.0625f, 0f);
			float scale = Math.min(Theme.MODEL_SCALE, (y1 - y0 - 24) / 1.9f);        // a player is 1.8 blocks tall: fit the stage
			graphics.entity(state, scale, offset, rotation, camera, x0, y0, x1, y1);
		} catch (RuntimeException e) {
			LOGGER.warn("player screen: the 3D model could not be drawn ({})", e.toString());
			entity = null;                          // never let a rendering problem take the screen down
		}
	}

	/** Builds a fresh render state the way the game's inventory screen does, so a skin that has loaded is used. */
	private void rebuild(long now) {
		Minecraft minecraft = Minecraft.getInstance();
		EntityRenderState fresh = minecraft.getEntityRenderDispatcher().getRenderer(entity).createRenderState(entity, 1.0f);
		fresh.shadowPieces.clear();
		fresh.outlineColor = 0;
		fresh.nameTag = null;                    // no floating name over the model: the header already shows it
		if (fresh instanceof LivingEntityRenderState living) {
			living.boundingBoxWidth /= living.scale;
			living.boundingBoxHeight /= living.scale;
			living.scale = 1.0f;
		}
		state = fresh;
		builtAt = now;
	}
}
