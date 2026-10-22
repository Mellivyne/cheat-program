package com.cheatprogram.util;

import com.cheatprogram.core.Friends;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/** Per-module target categories do not mutate the shared KillAura target settings. */
public final class AssistantTargets {
    private AssistantTargets() { }
    public static boolean accepts(Entity entity, boolean players, boolean friendly, boolean hostile) {
        var mc = Minecraft.getInstance();
        if (entity == null || entity == mc.player || !(entity instanceof LivingEntity living)
                || entity.isRemoved() || entity.level() != mc.level || !living.isAlive() || living.isDeadOrDying()
                || entity instanceof ArmorStand || !entity.isAttackable() || entity.isSpectator()) return false;
        if (entity instanceof Player player) return players && !Friends.isFriend(player);
        return entity instanceof Enemy ? hostile : friendly;
    }
}
