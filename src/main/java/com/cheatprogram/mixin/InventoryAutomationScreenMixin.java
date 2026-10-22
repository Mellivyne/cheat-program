package com.cheatprogram.mixin;

import com.cheatprogram.util.InventoryAutomation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class InventoryAutomationScreenMixin {
	@Shadow @Final protected AbstractContainerMenu menu;
	@Shadow protected int leftPos;
	@Shadow protected int topPos;
	@Inject(method = "mouseClicked", at = @At("HEAD"))
	private void cheatprogram$userClick(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) { InventoryAutomation.manualInteraction(); }
	@Inject(method = "mouseDragged", at = @At("HEAD"))
	private void cheatprogram$userDrag(double x, double y, int button, double dx, double dy, CallbackInfoReturnable<Boolean> cir) { InventoryAutomation.manualInteraction(); }
	@Inject(method = "keyPressed", at = @At("HEAD"))
	private void cheatprogram$userKey(int key, int scan, int modifiers, CallbackInfoReturnable<Boolean> cir) { InventoryAutomation.manualInteraction(); }
	@Inject(method = "onClose", at = @At("HEAD"))
	private void cheatprogram$userClose(CallbackInfo ci) { InventoryAutomation.manualInteraction(); }
	@Inject(method = "render", at = @At("TAIL"))
	private void cheatprogram$selectedSlot(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		int index = InventoryAutomation.highlightedSlot(menu);
		if (index < 0 || index >= menu.slots.size()) return;
		var slot = menu.slots.get(index);
		int x = leftPos + slot.x - 1, y = topPos + slot.y - 1;
		graphics.fill(x, y, x + 18, y + 1, 0xDFFFFFFF);
		graphics.fill(x, y + 17, x + 18, y + 18, 0xDFFFFFFF);
		graphics.fill(x, y, x + 1, y + 18, 0xDFFFFFFF);
		graphics.fill(x + 17, y, x + 18, y + 18, 0xDFFFFFFF);
	}
}
