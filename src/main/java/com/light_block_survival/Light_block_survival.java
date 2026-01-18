package com.light_block_survival;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class Light_block_survival implements ModInitializer {
	public static final String MOD_ID = "light_block_survival";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("神说，要有光");

		// When a player attacks a block, allow survival players to instantly break vanilla LIGHT blocks.
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			try {
				// Only run on server side
				if (world == null || world.isClientSide()) return InteractionResult.PASS;

				BlockState state = world.getBlockState(pos);
				if (!state.is(Blocks.LIGHT)) return InteractionResult.PASS;
				if (player.isCreative()) return InteractionResult.PASS;

				// Remove the block server-side without vanilla drops (we'll spawn a proper drop)
				boolean removed = world.removeBlock(pos, false);
				if (!removed) return InteractionResult.PASS;

				// Build the appropriate drop ItemStack, preserving light level if possible
				int foundLevel = extractLightLevelFromState(state);
				ItemStack drop = buildDropForLight(state, world, pos);
				if (foundLevel >= 0 && drop != null && !drop.isEmpty()) {
					attachBlockStateLevelTag(drop, foundLevel);
				}

				if (drop != null && !drop.isEmpty() && world instanceof ServerLevel) {
					ItemEntity ent = new ItemEntity((ServerLevel) world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
					((ServerLevel) world).addFreshEntity(ent);
				}

				// Send block-break event so clients show particles/sound like creative
				int blockId = getBlockId(state);
				world.levelEvent(null, 2001, pos, blockId);
				return InteractionResult.SUCCESS;
			} catch (Throwable t) {
				// swallow to avoid breaking server if reflection/mapping differs
				LOGGER.debug("Error in Light_block_survival attack handler", t);
				return InteractionResult.PASS;
			}
		});
	}

	// Attempt to build an ItemStack that represents the light block with correct level
	private ItemStack buildDropForLight(BlockState state, Level world, BlockPos pos) {
		try {
			// 1) Try to call block's clone/pick method reflectively
			Object blockObj = state.getBlock();
			Class<?> blockClass = blockObj.getClass();
			List<Method> candidates = new ArrayList<>();
			for (Method m : blockClass.getMethods()) {
				if (ItemStack.class.isAssignableFrom(m.getReturnType())) {
					String name = m.getName().toLowerCase();
					if (name.contains("clone") || name.contains("pick") || name.contains("item") || name.contains("stack")) {
						candidates.add(m);
					}
				}
			}

			Object[] common = new Object[]{world, pos, state, false};
			for (Method m : candidates) {
				Class<?>[] params = m.getParameterTypes();
				List<Object> args = new ArrayList<>();
				boolean ok = true;
				for (Class<?> p : params) {
					Object found = null;
					for (Object o : common) {
						if (o == null) continue;
						if (p.isInstance(o) || (p.isPrimitive() && isWrapperOf(o.getClass(), p))) { found = o; break; }
					}
					if (found == null) { ok = false; break; }
					args.add(found);
				}
				if (!ok) continue;
				try {
					m.setAccessible(true);
					Object res = m.invoke(blockObj, args.toArray());
					if (res instanceof ItemStack && !((ItemStack) res).isEmpty()) return (ItemStack) res;
				} catch (Throwable ignored) {}
			}

			// 2) fallback to static Items.LIGHT if present
			try {
				Class<?> itemsClass = Class.forName("net.minecraft.world.item.Items");
				java.lang.reflect.Field f = itemsClass.getField("LIGHT");
				Object itemObj = f.get(null);
				if (itemObj instanceof Item) return new ItemStack((Item) itemObj);
			} catch (Throwable ignored) {}

			// 3) fallback to block.asItem()
			Item it = state.getBlock().asItem();
			if (it != null) return new ItemStack(it);

		} catch (Throwable t) {
			// no-op
		}
		return ItemStack.EMPTY;
	}

	private int getBlockId(BlockState state) {
		try {
			Class<?> registryClass = Class.forName("net.minecraft.core.Registry");
			java.lang.reflect.Field field = registryClass.getField("BLOCK");
			Object blockRegistry = field.get(null);
			java.lang.reflect.Method getId = blockRegistry.getClass().getMethod("getId", Object.class);
			Object idObj = getId.invoke(blockRegistry, state.getBlock());
			if (idObj instanceof Integer) return (Integer) idObj;
		} catch (Throwable ignored) {}
		return 0;
	}

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

	private static void attachBlockStateLevelTag(ItemStack drop, int level) {
		try {
			CompoundTag bst = new CompoundTag();
			bst.putInt("level", level);
			// attach via reflection to support mappings variations
			try {
				java.lang.reflect.Method getOrCreate = ItemStack.class.getMethod("getOrCreateTag");
				Object tag = getOrCreate.invoke(drop);
				if (tag != null) {
					tag.getClass().getMethod("put", String.class, Class.forName("net.minecraft.nbt.Tag")).invoke(tag, "BlockStateTag", bst);
					return;
				}
			} catch (NoSuchMethodException ns) {
				// fall through to getTag/setTag
			} catch (Throwable ignored) {}

			try {
				java.lang.reflect.Method getTag = ItemStack.class.getMethod("getTag");
				Object tag = getTag.invoke(drop);
				if (tag == null) {
					java.lang.reflect.Method setTag = ItemStack.class.getMethod("setTag", CompoundTag.class);
					setTag.invoke(drop, bst);
				} else {
					tag.getClass().getMethod("put", String.class, Class.forName("net.minecraft.nbt.Tag")).invoke(tag, "BlockStateTag", bst);
				}
			} catch (Throwable ignored) {
				// give up
			}
		} catch (Throwable ignored) {}
	}

	private static boolean isWrapperOf(Class<?> wrapper, Class<?> primitive) {
		if (!primitive.isPrimitive()) return false;
		try {
			Class<?> w = (Class<?>) ((Object) primitive).getClass().getMethod("TYPE").invoke(primitive);
			return w == wrapper;
		} catch (Throwable e) {
			// fallback basic checks
			return (primitive == boolean.class && wrapper == Boolean.class)
				|| (primitive == byte.class && wrapper == Byte.class)
				|| (primitive == short.class && wrapper == Short.class)
				|| (primitive == int.class && wrapper == Integer.class)
				|| (primitive == long.class && wrapper == Long.class)
				|| (primitive == float.class && wrapper == Float.class)
				|| (primitive == double.class && wrapper == Double.class)
				|| (primitive == char.class && wrapper == Character.class);
		}
	}
}
