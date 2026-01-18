package com.light_block_survival.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Unique;

final class LightBlockDropHelper {
    private LightBlockDropHelper() {}

    /**
     * Handle breaking of a LIGHT block in survival: remove block, spawn ItemEntity with BlockStateTag(level).
     * Returns true if handled (caller should cancel further processing)
     */
    public static boolean handlePlayerBreak(Level world, BlockPos pos, BlockState state, Player player) {
        try {
            if (world == null || world.isClientSide()) return false;
            if (player == null) return false;
            if (player.isCreative()) return false;
            if (state == null || !state.is(Blocks.LIGHT)) return false;

            // Ensure the block is actually removed
            boolean removed = world.removeBlock(pos, false);
            if (!removed) return false;

            int foundLevel = extractLightLevelFromState(state);

            ItemStack drop = new ItemStack(Items.LIGHT);
            if (!drop.isEmpty() && foundLevel >= 0) {
                attachBlockStateLevelTag(drop, foundLevel);
            }

            if (world instanceof ServerLevel) {
                ServerLevel server = (ServerLevel) world;
                ItemEntity ent = new ItemEntity(server, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
                server.addFreshEntity(ent);
            }

            // send block-break event so clients show particles/sound like creative
            try {
                try {
                    Class<?> registryClass = Class.forName("net.minecraft.core.Registry");
                    java.lang.reflect.Field field = registryClass.getField("BLOCK");
                    Object blockRegistry = field.get(null);
                    java.lang.reflect.Method getId = blockRegistry.getClass().getMethod("getId", Object.class);
                    Object idObj = getId.invoke(blockRegistry, state.getBlock());
                    if (idObj instanceof Integer) {
                        world.levelEvent(null, 2001, pos, (Integer) idObj);
                    } else {
                        world.levelEvent(null, 2001, pos, 0);
                    }
                } catch (Throwable ignored) {
                    world.levelEvent(null, 2001, pos, 0);
                }
            } catch (Throwable ignored) {}

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Unique
    private static int extractLightLevelFromState(BlockState state) {
        try {
            String s = state.toString().toLowerCase();
            String[] keys = new String[]{"level=","light=","luminance=","power=","light_level="};
            for (String k : keys) {
                int i = s.indexOf(k);
                if (i >= 0) {
                    i += k.length();
                    int j = i;
                    while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
                    if (j > i) {
                        try { return Integer.parseInt(s.substring(i, j)); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    @Unique
    private static void attachBlockStateLevelTag(ItemStack drop, int level) {
        try {
            // try getOrCreateTag via reflection
            try {
                java.lang.reflect.Method getOrCreate = ItemStack.class.getMethod("getOrCreateTag");
                Object tag = getOrCreate.invoke(drop);
                if (tag != null) {
                    Class<?> compoundClass = Class.forName("net.minecraft.nbt.CompoundTag");
                    Object bst = compoundClass.getDeclaredConstructor().newInstance();
                    compoundClass.getMethod("putInt", String.class, int.class).invoke(bst, "level", level);
                    tag.getClass().getMethod("put", String.class, Class.forName("net.minecraft.nbt.Tag")).invoke(tag, "BlockStateTag", bst);
                    return;
                }
            } catch (NoSuchMethodException ns) {
                // fallback to getTag/setTag
            } catch (Throwable ignored) {}

            try {
                java.lang.reflect.Method getTag = ItemStack.class.getMethod("getTag");
                Object tag = getTag.invoke(drop);
                if (tag == null) {
                    Class<?> compoundClass = Class.forName("net.minecraft.nbt.CompoundTag");
                    Object bst = compoundClass.getDeclaredConstructor().newInstance();
                    compoundClass.getMethod("putInt", String.class, int.class).invoke(bst, "level", level);
                    java.lang.reflect.Method setTag = ItemStack.class.getMethod("setTag", compoundClass);
                    setTag.invoke(drop, bst);
                } else {
                    // tag exists, put BlockStateTag inside
                    Class<?> compoundClass = Class.forName("net.minecraft.nbt.CompoundTag");
                    Object bst = compoundClass.getDeclaredConstructor().newInstance();
                    compoundClass.getMethod("putInt", String.class, int.class).invoke(bst, "level", level);
                    tag.getClass().getMethod("put", String.class, Class.forName("net.minecraft.nbt.Tag")).invoke(tag, "BlockStateTag", bst);
                }
            } catch (Throwable ignored) {
                // give up
            }
        } catch (Throwable ignored) {}
    }
}
