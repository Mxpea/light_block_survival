package com.light_block_survival.mixin;

// Fallback helper for popResource-related paths. We no longer attempt an intrusive @Redirect here
// because the exact invoke site and descriptor can vary across mappings. Keep a small helper
// that can be expanded later if a specific method descriptor is identified.

final class BlockPopResourceHelper {
    private BlockPopResourceHelper() {}

    static void ensureBlockStateTagPresentOnLight(net.minecraft.world.item.ItemStack stack) {
        try {
            if (stack == null) return;
            if (stack.getItem() != net.minecraft.world.level.block.Blocks.LIGHT.asItem()) return;
            try {
                java.lang.reflect.Method getOrCreate = stack.getClass().getMethod("getOrCreateTag");
                Object tag = getOrCreate.invoke(stack);
                if (tag != null) {
                    Class<?> compoundClass = Class.forName("net.minecraft.nbt.CompoundTag");
                    Object bst = compoundClass.getDeclaredConstructor().newInstance();
                    tag.getClass().getMethod("put", String.class, Class.forName("net.minecraft.nbt.Tag")).invoke(tag, "BlockStateTag", bst);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }
}
