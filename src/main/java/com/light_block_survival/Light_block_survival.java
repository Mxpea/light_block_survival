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

				// Extract light level: prefer BlockEntity (if present), then BlockState
				int foundLevel = extractLightLevelFromBlockEntity(world, pos);
				if (foundLevel < 0) {
					foundLevel = extractLightLevelFromState(state, world, pos);
				}

				// Remove the block server-side without vanilla drops (we'll spawn a proper drop)
				boolean removed = world.removeBlock(pos, false);
				if (!removed) return InteractionResult.PASS;

				// Build the appropriate drop ItemStack, preserving light level if possible
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

	private static int extractLightLevelFromState(BlockState state, Level world, BlockPos pos) {
		if (state == null) return -1;
		// Prefer reading explicit IntegerProperty named like level/light if present using BlockState.getValue(Property)
		java.lang.reflect.Method getValueMethod = null;
		for (java.lang.reflect.Method m : state.getClass().getMethods()) {
			if (!m.getName().equals("getValue")) continue;
			if (m.getParameterCount() == 1) { getValueMethod = m; break; }
		}

		try {
			Object block = state.getBlock();
			java.lang.reflect.Method getStateDef = block.getClass().getMethod("getStateDefinition");
			Object stateDef = getStateDef.invoke(block);
			java.lang.reflect.Method getProperties = stateDef.getClass().getMethod("getProperties");
			Object propsObj = getProperties.invoke(stateDef);
			if (propsObj instanceof java.util.Collection) {
				java.util.List<java.util.Map.Entry<Object,Integer>> numericCandidates = new java.util.ArrayList<>();
				for (Object prop : (java.util.Collection<?>) propsObj) {
					if (prop == null) continue;
					// attempt to get property name
					String propName = null;
					String[] nameMethods = new String[] {"getName","getSerializedName","getPropertyName","name","getId"};
					for (String nm : nameMethods) {
						try { java.lang.reflect.Method gm = prop.getClass().getMethod(nm); Object res = gm.invoke(prop); if (res != null) { propName = res.toString(); break; } } catch (Throwable ignored) {}
					}
					if (propName == null) propName = prop.toString();
					String pn = propName.toLowerCase();

					// if property name looks like level/light, prefer direct getValue
					if (getValueMethod != null && (pn.contains("level") || pn.contains("light") || pn.contains("luminance") || pn.contains("power"))) {
						try {
							Object val = getValueMethod.invoke(state, prop);
							Integer uv = unwrapIntResult(val);
							if (uv != null) return uv;
							if (val instanceof Number) {
								int v = ((Number) val).intValue(); if (v >= 0 && v <= 15) return v;
							}
							try { int v = Integer.parseInt(String.valueOf(val)); if (v >= 0 && v <= 15) return v; } catch (Throwable ignored) {}
						} catch (Throwable ignored) {}
					}

					// fallback: try any method on state that accepts this prop
					Object val = null;
					for (java.lang.reflect.Method m : state.getClass().getMethods()) {
						if (m.getParameterCount() != 1) continue;
						Class<?> pType = m.getParameterTypes()[0];
						if (!pType.isAssignableFrom(prop.getClass())) continue;
						try { val = m.invoke(state, prop); } catch (Throwable ignored) { continue; }
						if (val != null) break;
					}

					if (val instanceof Number) {
						int v = ((Number) val).intValue();
						if (v >= 0 && v <= 15) numericCandidates.add(new java.util.AbstractMap.SimpleEntry<>(prop, v));
					} else if (val != null) {
						try { int v = Integer.parseInt(val.toString()); if (v >= 0 && v <= 15) numericCandidates.add(new java.util.AbstractMap.SimpleEntry<>(prop, v)); } catch (Throwable ignored) {}
					}
				}

				if (numericCandidates.size() == 1) return numericCandidates.get(0).getValue();
			}
		} catch (Throwable ignored) {}

		// fallback to string parsing for compatibility
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

	private static int extractLightLevelFromBlockEntity(Level world, BlockPos pos) {
		try {
			java.lang.reflect.Method getBe = world.getClass().getMethod("getBlockEntity", BlockPos.class);
			Object be = getBe.invoke(world, pos);
			if (be == null) return -1;

			// Try to obtain NBT via common save methods
			Object nbt = null;
			try {
				java.lang.reflect.Method saveMethod = be.getClass().getMethod("save", CompoundTag.class);
				Object tmp = Class.forName("net.minecraft.nbt.CompoundTag").getDeclaredConstructor().newInstance();
				Object res = saveMethod.invoke(be, tmp);
				if (res == null) nbt = tmp; else nbt = res;
			} catch (Throwable ignored) {
				try {
					java.lang.reflect.Method saveNoArg = be.getClass().getMethod("save");
					Object res = saveNoArg.invoke(be);
					if (res != null) nbt = res;
				} catch (Throwable ignored2) {}
			}

			if (nbt == null) return -1;

			// Try to read an int via reflection (handle mappings that return Optional<Integer> or int)
			try {
				java.lang.reflect.Method getInt = nbt.getClass().getMethod("getInt", String.class);
				Object res = getInt.invoke(nbt, "level");
				Integer v = unwrapIntResult(res);
				if (v != null) return v;
			} catch (Throwable ignored) {}

			// fallback: check BlockStateTag compound
			try {
				java.lang.reflect.Method contains = nbt.getClass().getMethod("contains", String.class);
				Object has = contains.invoke(nbt, "BlockStateTag");
				boolean hasTag = false;
				if (has instanceof Boolean) hasTag = (Boolean) has; else if (has != null) hasTag = Boolean.parseBoolean(has.toString());
				if (hasTag) {
					java.lang.reflect.Method get = nbt.getClass().getMethod("get", String.class);
					Object bst = get.invoke(nbt, "BlockStateTag");
					if (bst != null) {
						try {
							java.lang.reflect.Method getInt2 = bst.getClass().getMethod("getInt", String.class);
							Object res2 = getInt2.invoke(bst, "level");
							Integer v2 = unwrapIntResult(res2);
							if (v2 != null) return v2;
						} catch (Throwable ignored) {}
					}
				}
			} catch (Throwable ignored) {}
		} catch (Throwable ignored) {}
		return -1;
	}

	@org.jetbrains.annotations.Nullable
	private static Integer unwrapIntResult(Object res) {
		try {
			if (res == null) return null;
			if (res instanceof Number) return ((Number) res).intValue();
			// handle Optional<Integer> or OptionalInt
			if (res instanceof java.util.Optional) {
				java.util.Optional<?> opt = (java.util.Optional<?>) res;
				if (!opt.isPresent()) return null;
				Object v = opt.get();
				if (v instanceof Number) return ((Number) v).intValue();
				try { return Integer.parseInt(v.toString()); } catch (Throwable ignored) { return null; }
			}
			// fallback parse
			try { return Integer.parseInt(res.toString()); } catch (Throwable ignored) { return null; }
		} catch (Throwable ignored) {}
		return null;
	}
}
