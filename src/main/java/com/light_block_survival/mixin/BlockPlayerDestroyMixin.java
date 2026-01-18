package com.light_block_survival.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.block.Block")
public class BlockPlayerDestroyMixin {
    @Inject(method = "playerDestroy", at = @At("HEAD"), cancellable = true)
    private void onPlayerDestroy(Level world, Player player, BlockPos pos, BlockState state, net.minecraft.world.level.block.entity.BlockEntity blockEntity, net.minecraft.world.item.ItemStack stack, CallbackInfo ci) {
        try {
            boolean handled = LightBlockDropHelper.handlePlayerBreak(world, pos, state, player);
            if (handled) ci.cancel();
        } catch (Throwable ignored) {}
    }
}
