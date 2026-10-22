package com.cheatprogram.module.combat;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.util.Targets;
import net.minecraft.core.Holder;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/** Selects an existing hotbar weapon; no item is created, moved or thrown away. */
public final class AutoWeapon extends Module {
	private final BoolSetting breakShield=add(new BoolSetting("breakShield",true));
	private final BoolSetting restore=add(new BoolSetting("restore",true));
	private final ModeSetting priority=add(new ModeSetting("priority","weapon_damage","weapon_damage","weapon_dps"));
	private Object player,world;
	private int original=-1,selected=-1,observedSlot=-1;
	private long manualUntil;
	public AutoWeapon(){super("autoweapon",Category.COMBAT);}
	@Override public void onEnable(){reset();}
	@Override public void onDisable(){release();reset();}
	private void reset(){player=mc.player;world=mc.level;original=selected=-1;observedSlot=mc.player==null?-1:mc.player.getInventory().selected;manualUntil=0;}
	private void release(){
		if(restore.on()&&player==mc.player&&world==mc.level&&mc.player!=null&&original>=0&&selected==mc.player.getInventory().selected)
			mc.player.getInventory().selected=original;
		if(mc.player!=null)observedSlot=mc.player.getInventory().selected;
		original=selected=-1;
	}
	private boolean ready(){return inGame()&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused()&&!mc.player.isUsingItem()
			&&!mc.player.isSpectator()&&mc.player.isAlive()&&!AiController.isRunning()&&!Freecam.isActive();}
	public void checkLifecycle(){if(player!=mc.player||world!=mc.level){reset();}}
	@Override public void onTick(){
		checkLifecycle();
		observeSelection();
		if(!ready()){release();return;}
		Entity target=ModuleManager.on(KillAura.class)?KillAura.target:mc.crosshairPickEntity;
		if(!Targets.isValid(target)){release();return;}
		prepare(target);
	}
	/** KillAura calls before testing cooldown so a weapon change starts its real recovery. */
	public static boolean prepareTarget(Entity target){
		AutoWeapon module=ModuleManager.get(AutoWeapon.class);
		return module==null||!module.isEnabled()||module.prepare(target);
	}
	/** A manual hotbar change also releases aura presentation, without clearing it on a normal auto-switch. */
	public static boolean manualOverrideActive(){
		AutoWeapon module=ModuleManager.get(AutoWeapon.class);
		return module!=null&&module.isEnabled()&&System.nanoTime()<module.manualUntil;
	}
	private void observeSelection(){
		if(mc.player==null){observedSlot=-1;return;}
		int held=mc.player.getInventory().selected;
		if(observedSlot>=0&&held!=observedSlot){
			// This baseline exists even when we started on the best weapon and never wrote a slot.
			original=selected=-1;manualUntil=System.nanoTime()+700_000_000L;
		}
		observedSlot=held;
	}
	private boolean prepare(Entity entity){
		checkLifecycle();
		observeSelection();
		if(!ready()||!Targets.isValid(entity))return false;
		int held=mc.player.getInventory().selected;
		if(System.nanoTime()<manualUntil)return false;
		LivingEntity target=(LivingEntity)entity;
		boolean shield=breakShield.on()&&target.isBlocking()&&target.getUseItem().is(Items.SHIELD);
		AttributeInstance damageBase=withoutMainHand(Attributes.ATTACK_DAMAGE),speedBase=withoutMainHand(Attributes.ATTACK_SPEED);
		int best=-1;double bestScore=-Double.MAX_VALUE;
		for(int index=0;index<9;index++){
			ItemStack stack=mc.player.getInventory().getItem(index);
			if(stack.isEmpty()||!(stack.is(ItemTags.SWORDS)||stack.is(ItemTags.AXES)))continue;
			double damage=candidateValue(damageBase,Attributes.ATTACK_DAMAGE,stack);
			double speed=candidateValue(speedBase,Attributes.ATTACK_SPEED,stack);
			for(var enchantment:stack.getEnchantments().entrySet())
				if(enchantment.getKey().is(Enchantments.SHARPNESS))damage+=.5*enchantment.getIntValue()+.5;
				else if(enchantment.getKey().is(Enchantments.SMITE)&&target.getType().is(EntityTypeTags.SENSITIVE_TO_SMITE))damage+=2.5*enchantment.getIntValue();
				else if(enchantment.getKey().is(Enchantments.BANE_OF_ARTHROPODS)&&target.getType().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS))damage+=2.5*enchantment.getIntValue();
			double score=priority.is("weapon_dps")?Math.max(0,damage)*Math.max(0,speed):Math.max(0,damage);
			if(shield&&stack.is(ItemTags.AXES))score+=10000;
			if(index==held)score+=.001;
			if(score>bestScore){bestScore=score;best=index;}
		}
		if(best<0)return true;
		if(best!=held){
			if(original<0)original=held;
			selected=observedSlot=best;mc.player.getInventory().selected=best;mc.player.resetAttackStrengthTicker();
			return false;
		}
		if(selected>=0)selected=held;
		return true;
	}
	/** Work on independent instances: live attributes, equipment and potion state remain untouched. */
	private AttributeInstance withoutMainHand(Holder<Attribute> attribute){
		AttributeInstance result=new AttributeInstance(attribute,ignored->{});
		AttributeInstance live=mc.player.getAttribute(attribute);
		if(live!=null)result.replaceFrom(live);
		mc.player.getMainHandItem().forEachModifier(EquipmentSlot.MAINHAND,(key,modifier)->{
			if(key.equals(attribute))result.removeModifier(modifier.id());
		});
		return result;
	}
	private static double candidateValue(AttributeInstance base,Holder<Attribute> attribute,ItemStack stack){
		AttributeInstance result=new AttributeInstance(attribute,ignored->{});result.replaceFrom(base);
		stack.forEachModifier(EquipmentSlot.MAINHAND,(key,modifier)->{
			if(key.equals(attribute)){
				result.removeModifier(modifier.id());result.addTransientModifier(modifier);
			}
		});
		// Vanilla AttributeInstance applies additive, base-multiplied and total-multiplied modifiers in order.
		return result.getValue();
	}
}
