package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

import net.minecraft.world.item.TridentItem;

/** First-person melee poses. Real swings trigger a local visual stroke without changing player actions. */
public final class WeaponAnimations extends Module {
	private final ModeSetting style = add(new ModeSetting("style", "weapon_rotate",
			"weapon_rotate", "weapon_classic", "weapon_slash", "weapon_spin", "weapon_thrust") {
		@Override public void load(JsonElement element) {
			// Keep existing profiles usable while removing the former video-only choice from the menu.
			if (element != null && element.isJsonPrimitive() && "weapon_video".equals(element.getAsString()))
				super.load(new JsonPrimitive("weapon_rotate"));
			else super.load(element);
		}
	});
	private final BoolSetting continuous = add(new BoolSetting("continuous", false));
	private final BoolSetting noPause = add(new BoolSetting("noPause", false)).visibleWhen(this::rotationStyle);
	private final NumberSetting amplitude = add(new NumberSetting("amplitude", 100, 0, 150, 5, "%"));
	private final NumberSetting speed = add(new NumberSetting("speed", 1, 0.5, 2, 0.1));
	private final NumberSetting size = add(new NumberSetting("size", 1, 0.6, 1.2, 0.05));
	private final NumberSetting offsetX = add(new NumberSetting("offsetX", 0, -0.4, 0.4, 0.05));
	private final NumberSetting offsetY = add(new NumberSetting("offsetY", 0, -0.35, 0.35, 0.05));
	private final NumberSetting offsetZ = add(new NumberSetting("offsetZ", 0, -0.4, 0.25, 0.05));
	private final Stroke mainStroke = new Stroke();
	private final Stroke offStroke = new Stroke();
	private ClientLevel world;
	private AbstractClientPlayer owner;

	public WeaponAnimations() {
		super("weaponanimations", Category.VISUAL);
	}

	@Override
	public String suffix() {
		return style.display();
	}

	private boolean rotationStyle() {
		return "weapon_rotate".equals(style.get()) || "weapon_video".equals(style.get());
	}

	@Override public void onEnable() { reset(); }
	@Override public void onDisable() { reset(); }

	@Override
	public void onTick() {
		bindWorld();
		if (mc.player == null) return;
		for (InteractionHand hand : InteractionHand.values()) {
			Stroke stroke = stroke(hand);
			ItemStack held = mc.player.getItemInHand(hand);
			int slot = hand == InteractionHand.MAIN_HAND ? mc.player.getInventory().selected : -1;
			if (!weapon(held) || mc.player.isUsingItem() && mc.player.getUsedItemHand() == hand
					|| mc.player.isAutoSpinAttack() || Freecam.isActive() || !mc.options.getCameraType().isFirstPerson()
					|| stroke.item != null && (stroke.item != held.getItem() || stroke.slot != slot)) stroke.reset();
		}
	}

	private void bindWorld() {
		if (world != mc.level || owner != mc.player) {
			reset();
			world = mc.level;
			owner = mc.player;
		}
	}

	private void reset() {
		mainStroke.reset();
		offStroke.reset();
		world = null;
		owner = null;
	}

	private Stroke stroke(InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND ? mainStroke : offStroke;
	}

	/** The render argument is authoritative; a cached former weapon must not transform a newly held item. */
	public static WeaponAnimations forHand(AbstractClientPlayer player, InteractionHand hand, ItemStack rendered) {
		WeaponAnimations module = ModuleManager.get(WeaponAnimations.class);
		if (module == null || !module.isEnabled()) return null;
		module.bindWorld();
		if (player != mc.player || !mc.options.getCameraType().isFirstPerson() || Freecam.isActive()
				|| player.isUsingItem() && player.getUsedItemHand() == hand || player.isAutoSpinAttack() || !weapon(rendered)) {
			module.stroke(hand).reset();
			return null;
		}
		ItemStack held = player.getItemInHand(hand);
		if (weapon(held) && rendered.is(held.getItem())) return module;
		module.stroke(hand).reset();
		return null;
	}

	private static boolean weapon(ItemStack stack) {
		return !stack.isEmpty() && (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)
				|| false || stack.getItem() instanceof TridentItem);
	}

	/** Replaces swingArm as one operation, inside vanilla's balanced per-hand push/pop. */
	public void renderSwing(AbstractClientPlayer player, InteractionHand hand, ItemStack rendered, PoseStack pose,
			HumanoidArm arm, float swingProgress, float equippedProgress) {
		int side = arm == HumanoidArm.RIGHT ? 1 : -1;
		float raw = Mth.clamp(swingProgress, 0, 1);
		int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : -1;
		boolean uninterrupted = rotationStyle() && noPause.on();
		float phase = stroke(hand).frame(rendered.getItem(), slot, style.get(), raw,
				continuous.on() || uninterrupted, speed.floatVal());
		// The small supporting movement returns smoothly; a full turn uses its own monotonic angle.
		float sine = Mth.sin(phase * Mth.PI);
		float stroke = sine * sine;
		float returnArc = Mth.sin(phase * Mth.TWO_PI) * stroke;
		// A linear full turn keeps angular speed at the cycle seam; other styles retain the resting ease.
		float turn = uninterrupted ? phase : phase * phase * phase * (phase * (phase * 6 - 15) + 10);
		float amount = amplitude.floatVal() / 100F;
		float x = 0, y = 0, z = 0, yaw = 0, pitch = 0, roll = 0;
		float fullYaw = 0, fullRoll = 0;
		switch (style.get()) {
			case "weapon_slash" -> {
				x = -0.24F * stroke; y = 0.06F * stroke; z = -0.10F * stroke;
				yaw = 86 * stroke; pitch = -25 * stroke; roll = -100 * stroke;
			}
			case "weapon_rotate", "weapon_video" -> {
				// Reference: upright front -> narrow edge -> reverse face -> edge -> front, about 0.70 s.
				// Rotate around the grip instead of reversing an angle at the middle of the animation.
				x = -0.10F * stroke; y = 0.025F + 0.045F * stroke;
				z = -0.035F * stroke;
				yaw = 8 * returnArc; pitch = -7 - 9 * stroke; roll = -20 + 7 * returnArc;
				fullYaw = -360 * turn;
			}
			case "weapon_spin" -> {
				// A distinct complete plane roll; compact placement keeps the blade ahead of the body.
				x = -0.055F * stroke; y = 0.025F + 0.055F * stroke; z = -0.055F * stroke;
				yaw = 8 * stroke; pitch = -12 - 7 * stroke; roll = -20;
				fullRoll = -360 * turn;
			}
			case "weapon_thrust" -> {
				x = -0.10F * stroke; y = 0.06F * stroke; z = -0.34F * stroke;
				yaw = 45 * stroke; pitch = -72 * stroke; roll = -15 * stroke;
			}
			default -> {
				x = -0.045F - 0.21F * stroke; y = 0.02F + 0.09F * stroke - 0.07F * returnArc;
				z = 0.015F - 0.09F * stroke;
				yaw = 82 * stroke; pitch = -7 - 48 * stroke - 15 * returnArc; roll = -17 + 82 * stroke;
			}
		}
		// Vanilla equip height is retained. The weapon root stays on its own side and out of the near plane;
		// this is a local viewmodel position, without disabling depth or altering world/body rendering.
		float rootX = Mth.clamp(0.66F + offsetX.floatVal() + x * amount, 0.43F, 0.90F);
		float rootY = Mth.clamp(-0.47F + offsetY.floatVal() + y * amount, -0.74F, -0.12F);
		float rootZ = Mth.clamp(-0.62F + offsetZ.floatVal() + z * amount, -1.05F, -0.50F);
		pose.translate(side * rootX, rootY - Mth.clamp(equippedProgress, 0, 1) * 0.6F, rootZ);
		float itemScale = rendered.getItem() instanceof TridentItem ? 0.74F : 0.90F;
		float scale = size.floatVal() * itemScale;
		pose.scale(scale, scale, scale);
		rotate(pose, side, yaw * amount, pitch * amount, roll * amount);
		if (amount > 0 && (fullYaw != 0 || fullRoll != 0)) {
			// Amplitude adjusts the accompanying arc, not the completed turn: scaling 360 would break its seam.
			pose.translate(0, -0.18F, 0);
			pose.mulPose(Axis.YP.rotationDegrees(side * fullYaw));
			pose.mulPose(Axis.ZP.rotationDegrees(side * fullRoll));
			pose.translate(0, 0.18F, 0);
		}
	}

	private static void rotate(PoseStack pose, int side, float yaw, float pitch, float roll) {
		pose.mulPose(Axis.YP.rotationDegrees(side * yaw));
		pose.mulPose(Axis.ZP.rotationDegrees(side * roll));
		pose.mulPose(Axis.XP.rotationDegrees(pitch));
	}

	private static final class Stroke {
		private Item item;
		private int slot = -1;
		private String style = "";
		private long previousTime;
		private float previousSwing;
		private double phase;
		private boolean playing, queued;

		private void reset() {
			item = null; slot = -1; style = ""; previousTime = 0; previousSwing = 0;
			phase = 0; playing = queued = false;
		}

		private float frame(Item held, int selectedSlot, String selectedStyle, float swing, boolean continuous, float speed) {
			long now = System.nanoTime();
			if (item != held || slot != selectedSlot || !style.equals(selectedStyle)) {
				reset(); item = held; slot = selectedSlot; style = selectedStyle; previousTime = now;
			}
			double seconds = previousTime == 0 || mc.isPaused() ? 0 : Math.min(0.10, Math.max(0, (now - previousTime) / 1.0e9));
			previousTime = now;
			if (continuous) queued = false;
			boolean attackStarted = swing > 0.001F && (previousSwing <= 0.001F || swing + 0.15F < previousSwing);
			previousSwing = swing;
			if (attackStarted) {
				if (playing) { if (!continuous) queued = true; }
				else { playing = true; phase = 0; seconds = 0; }
			}
			if (!playing && continuous) { playing = true; phase = 0; seconds = 0; }
			if (playing) {
				phase += seconds * speed / 0.70;
				if (phase >= 1) {
					if (continuous || queued) { phase -= Math.floor(phase); queued = false; }
					else { phase = 0; playing = false; }
				}
			}
			// Turning continuous off finishes the current cycle at its zero-velocity resting pose.
			return playing ? (float) phase : 0;
		}
	}
}
