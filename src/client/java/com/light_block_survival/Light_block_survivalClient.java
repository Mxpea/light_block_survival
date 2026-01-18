package com.light_block_survival;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;

public class Light_block_survivalClient implements ClientModInitializer {
    private static int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                // increment and only scan every 10 ticks (~0.5s at 20 TPS)
                tickCounter++;
                if (tickCounter % 10 != 0) return;

                if (client.player == null || client.level == null) return;
                ItemStack held = client.player.getMainHandItem();
                if (held.isEmpty()) return;
                // only show when holding the light item
                if (held.getItem() != Blocks.LIGHT.asItem()) return;

                int radius = 8;
                BlockPos origin = client.player.blockPosition();
                int ox = origin.getX();
                int oy = origin.getY();
                int oz = origin.getZ();
                int r2 = radius * radius;
                for (int dx = -radius; dx <= radius; dx++) {
                    int x = ox + dx;
                    for (int dy = -radius; dy <= radius; dy++) {
                        int y = oy + dy;
                        for (int dz = -radius; dz <= radius; dz++) {
                            int z = oz + dz;
                            int dist2 = dx*dx + dy*dy + dz*dz;
                            if (dist2 > r2) continue; // sphere check
                            BlockPos pos = new BlockPos(x, y, z);
                            if (client.level.getBlockState(pos).is(Blocks.LIGHT)) {
                                // spawn an end_rod particle at the block center
                                client.level.addParticle(ParticleTypes.END_ROD, x + 0.5, y + 0.7, z + 0.5, 0.0, 0.0, 0.0);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
