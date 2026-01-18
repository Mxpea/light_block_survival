package com.light_block_survival;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Light_block_survival implements ModInitializer {
	public static final String MOD_ID = "light_block_survival";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {

		LOGGER.info("Hello Fabric world!");

		// When a player attacks a block, allow survival players to instantly break vanilla LIGHT blocks.
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			try {
				// Only run on server side
				if (world == null || world.isClientSide()) return InteractionResult.PASS;

				BlockState state = world.getBlockState(pos);
				if (state.is(Blocks.LIGHT)) {
					if (!player.isCreative()) {
						// Break block server-side with drops
						boolean did = world.destroyBlock(pos, true);
						// Ensure explicit drop in case block doesn't drop by itself
						try {
							ItemStack drop = ItemStack.EMPTY;
							// prefer Items.LIGHT if present (some mappings register a dedicated item)
							try {
								Class<?> itemsClass = Class.forName("net.minecraft.world.item.Items");
								java.lang.reflect.Field f = itemsClass.getField("LIGHT");
								Object itemObj = f.get(null);
								if (itemObj instanceof Item) drop = new ItemStack((Item) itemObj);
							} catch (Throwable t) {
								// fallback
							}
							if (drop.isEmpty()) {
								Item it = state.getBlock().asItem();
								if (it != null) drop = new ItemStack(it);
							}
							if (!drop.isEmpty() && world instanceof ServerLevel) {
								ItemEntity ent = new ItemEntity((ServerLevel) world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
								((ServerLevel) world).addFreshEntity(ent);
							}
						} catch (Throwable t) {
							// swallow
						}
						// Send block-break event (2001) so clients show break particles/sound like creative
						int blockId = 0;
						try {
							Class<?> registryClass = Class.forName("net.minecraft.core.Registry");
							java.lang.reflect.Field field = registryClass.getField("BLOCK");
							Object blockRegistry = field.get(null);
							java.lang.reflect.Method getId = blockRegistry.getClass().getMethod("getId", Object.class);
							Object idObj = getId.invoke(blockRegistry, state.getBlock());
							if (idObj instanceof Integer) blockId = (Integer) idObj;
						} catch (Throwable refl) {
							// fallback to 0
						}
						world.levelEvent(null, 2001, pos, blockId);
						return InteractionResult.SUCCESS;
					}
					}
			} catch (Throwable t) {
				// swallow to avoid breaking server
			}
			return InteractionResult.PASS;
		});
	}
}