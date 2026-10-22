package com.cheatprogram.compat;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
public final class WorldBridge {
    private WorldBridge(){}
    public static void clear(BlockEntity entity){if(entity instanceof Clearable clearable)clearable.clearContent();}
    public static int shieldDelay(ItemStack stack){var behavior=stack.get(DataComponents.BLOCKS_ATTACKS);return behavior==null?0:behavior.blockDelayTicks();}
}
