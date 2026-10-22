package com.cheatprogram.module.inventory;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.RangeSetting;
import com.cheatprogram.util.Clock;
import com.cheatprogram.util.InvUtil;
import com.cheatprogram.util.InventoryProtection;
import com.cheatprogram.util.InventoryAutomation;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.Map;

public class Refill extends Module {
	private final RangeSetting delay = add(new RangeSetting("delay", 75, 125, 0, 500, 5, "ms"));
	private final Clock clock = new Clock();
	private Object player,world;
	private final Map<Integer,Refused> refused=new HashMap<>();
	private record Refused(ItemStack hotbar,ItemStack source) { }

	public Refill() {
		super("refill", Category.INVENTORY);
	}
	@Override public void onEnable(){reset();}
	@Override public void onDisable(){reset();}
	private void reset(){player=mc.player;world=mc.level;refused.clear();clock.reset(0);InventoryAutomation.forgetOwnChanges(this);}
	private boolean refused(int hotbar,int source){
		int key=hotbar*36+source;Refused previous=refused.get(key);
		if(previous==null)return false;
		if(ItemStack.matches(previous.hotbar,InvUtil.stack(hotbar))&&ItemStack.matches(previous.source,InvUtil.stack(source)))return true;
		refused.remove(key);return false;
	}
	public boolean refusedWork(){
		if(!inGame())return false;
		for(int key:java.util.List.copyOf(refused.keySet())){
			int hotbar=key/36,source=key%36;
			if(!InventoryProtection.protectedSlot(hotbar)&&!InventoryProtection.protectedSlot(source)&&refused(hotbar,source))return true;
		}
		return false;
	}
	public boolean hasPendingWork(){
		if(!inGame()||!InventoryAutomation.canUseOwnInventory())return false;
		for(int i=0;i<9;i++){
			if(InventoryProtection.protectedSlot(i))continue;
			ItemStack stack=InvUtil.stack(i);if(stack.isEmpty()||!stack.isStackable()||stack.getCount()>stack.getMaxStackSize()/2)continue;
			for(int source=9;source<36;source++)if(!InventoryProtection.protectedSlot(source)&&!refused(i,source)
					&&!InvUtil.stack(source).isEmpty()&&ItemStack.isSameItemSameComponents(stack,InvUtil.stack(source)))return true;
		}
		return false;
	}

	@Override
	public void onTick() {
		if(player!=mc.player||world!=mc.level)reset();
		if (mc.gui.screen() != null && !(mc.gui.screen() instanceof InventoryScreen) && !(mc.gui.screen() instanceof CreativeModeInventoryScreen)) return;
		if (!InventoryAutomation.ownInventoryReady() || !clock.ready()) return;
		for (int i = 0; i < 9; i++) {
			if (InventoryProtection.protectedSlot(i)) continue;
			ItemStack hotbar = InvUtil.stack(i);
			if (hotbar.isEmpty() || !hotbar.isStackable() || hotbar.getCount() > hotbar.getMaxStackSize() / 2) continue;
			int source = -1;
			for (int candidate = 9; candidate < 36; candidate++) {
				if (!InventoryProtection.protectedSlot(candidate)&&!refused(i,candidate)&&!InvUtil.stack(candidate).isEmpty()
						&&ItemStack.isSameItemSameComponents(InvUtil.stack(candidate), hotbar)) { source = candidate; break; }
			}
			if (source < 0) continue;
			if(!InventoryAutomation.claim(mc.player.inventoryMenu,InvUtil.menuSlot(source)))return;
			ItemStack beforeHotbar=hotbar.copy(),beforeSource=InvUtil.stack(source).copy();
			int hotbarIndex=i,sourceIndex=source,key=i*36+source;
			// Preflight the full exchange before the first pickup; a rejected first click must not
			// turn a later destination click into picking up the player's existing hotbar stack.
			InventoryAutomation.clickOwnInventory(InvUtil.menuSlot(source), 0, ContainerInput.PICKUP);
			if (mc.player.inventoryMenu.getCarried().isEmpty()) {refused.put(key,new Refused(beforeHotbar,beforeSource));return;}
			InventoryAutomation.clickOwnInventory(InvUtil.menuSlot(i), 0, ContainerInput.PICKUP);
			if (!mc.player.inventoryMenu.getCarried().isEmpty()) InventoryAutomation.clickOwnInventory(InvUtil.menuSlot(source), 0, ContainerInput.PICKUP);
			if(ItemStack.matches(beforeHotbar,InvUtil.stack(i)))refused.put(key,new Refused(beforeHotbar,beforeSource));
			else InventoryAutomation.watchOwnChange(this,()->ItemStack.matches(beforeHotbar,InvUtil.stack(hotbarIndex))
					&&ItemStack.matches(beforeSource,InvUtil.stack(sourceIndex)),()->refused.put(key,new Refused(beforeHotbar,beforeSource)));
			clock.reset((long) delay.random());
			return;
		}
	}
}
