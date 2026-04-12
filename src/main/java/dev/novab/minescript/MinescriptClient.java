package dev.novab.minescript;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeCoords;

public final class MinescriptClient implements ClientModInitializer {
	public static final String MOD_ID = "minescript";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int MAX_CHAT_LINES = 100;
	private static final int MAX_CHAT_MESSAGE_LENGTH = 240;
	private static final int MAX_EVENT_QUEUE = 512;
	private static final String SCRIPT_CHAT_PREFIX = "[ms:";
	private static final Deque<String> RECENT_CHAT = new ArrayDeque<>();
	private static final Deque<JsonObject> EVENT_QUEUE = new ArrayDeque<>();
	private static volatile boolean syntheticForwardPressed;
	private static volatile boolean syntheticBackPressed;
	private static volatile boolean syntheticLeftPressed;
	private static volatile boolean syntheticRightPressed;
	private static volatile boolean syntheticSneakPressed;
	private static volatile boolean syntheticSprintPressed;

	private MinescriptConfig config;
	private ScriptRunner scriptRunner;

	@Override
	public void onInitializeClient() {
		this.config = MinescriptConfig.loadOrCreate();
		this.config.ensureSupportFiles();

		PythonBridgeServer bridgeServer = new PythonBridgeServer(this.config, new ClientApi());
		bridgeServer.start();

		this.scriptRunner = new ScriptRunner(this.config);
		registerCommands();
		registerChatCapture();
		registerEventCapture();

		LOGGER.info("Minescript ready. Scripts: {}", this.config.scriptsDir());
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(createRootCommand("minescript"));
			dispatcher.register(createRootCommand("ms"));
		});
	}

	private com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> createRootCommand(String name) {
		return literal(name)
			.then(literal("run")
				.then(argument("script", StringArgumentType.greedyString())
					.executes(context -> {
						String scriptName = StringArgumentType.getString(context, "script");
						context.getSource().sendFeedback(Text.literal("Running " + scriptName + "...").formatted(Formatting.GRAY));

						this.scriptRunner.runScript(scriptName).whenComplete((ignored, throwable) -> {
							if (throwable != null) {
								LOGGER.error("Failed to run script {}", scriptName, throwable);
							} else {
								LOGGER.info("Finished script {}", scriptName);
							}
						});

						return 1;
					})
				)
			)
			.then(literal("folder")
				.executes(context -> {
					context.getSource().sendFeedback(Text.literal(this.config.scriptsDir().toString()));
					return 1;
				})
			)
			.then(literal("reload")
				.executes(context -> {
					this.config.ensureSupportFiles();
					context.getSource().sendFeedback(Text.literal("Running all Python files in " + this.config.scriptsDir().getFileName() + "...").formatted(Formatting.GRAY));

					this.scriptRunner.runAllScripts().whenComplete((count, throwable) -> {
						if (throwable != null) {
							LOGGER.error("Failed to reload Minescript Python files", throwable);
						} else {
							LOGGER.info("Reloaded {} Python script(s)", count);
						}
					});

					return 1;
				})
			);
	}

	private void registerChatCapture() {
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			String content = message.getString();
			rememberChatLine(content);
			appendChatEvent(content, false);
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String content = message.getString();
			rememberChatLine(content);
			appendChatEvent(content, overlay);
		});
	}

	private void registerEventCapture() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			applySyntheticInputState(client);
			if (client.player == null) {
				return;
			}

			JsonObject event = new JsonObject();
			event.addProperty("type", "tick");
			event.addProperty("time", System.currentTimeMillis());
			event.addProperty("x", client.player.getX());
			event.addProperty("y", client.player.getY());
			event.addProperty("z", client.player.getZ());
			appendEvent(event);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			JsonObject event = new JsonObject();
			event.addProperty("type", "join_world");
			event.addProperty("time", System.currentTimeMillis());
			if (client.world != null) {
				event.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
			}
			appendEvent(event);
		});
	}

	private static synchronized void rememberChatLine(String message) {
		if (message == null || message.isBlank() || isInternalScriptMessage(message)) {
			return;
		}

		RECENT_CHAT.addLast(message);
		while (RECENT_CHAT.size() > MAX_CHAT_LINES) {
			RECENT_CHAT.removeFirst();
		}
	}

	private static synchronized void appendEvent(JsonObject event) {
		EVENT_QUEUE.addLast(event);
		while (EVENT_QUEUE.size() > MAX_EVENT_QUEUE) {
			EVENT_QUEUE.removeFirst();
		}
	}

	private static void appendChatEvent(String content, boolean overlay) {
		if (content == null || content.isBlank() || isInternalScriptMessage(content)) {
			return;
		}

		JsonObject event = new JsonObject();
		event.addProperty("type", "chat");
		event.addProperty("time", System.currentTimeMillis());
		event.addProperty("message", content);
		event.addProperty("overlay", overlay);
		appendEvent(event);
	}

	private static boolean isInternalScriptMessage(String message) {
		return message.startsWith(SCRIPT_CHAT_PREFIX);
	}

	public static void sendScriptMessage(String scriptName, String message, String level) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}

		client.execute(() -> {
			if (client.player == null || message == null || message.isBlank()) {
				return;
			}

			Formatting formatting = switch (level.toUpperCase(Locale.ROOT)) {
				case "ERROR" -> Formatting.RED;
				case "WARN" -> Formatting.YELLOW;
				case "SYSTEM" -> Formatting.GREEN;
				default -> Formatting.GRAY;
			};
			String prefix = "[ms:" + scriptName + "] ";
			for (String chunk : splitForChat(message)) {
				client.player.sendMessage(Text.literal(prefix + chunk).formatted(formatting), false);
			}
		});
	}

	public static void showScriptToast(String title, String description, boolean error) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}

		client.execute(() -> client.getToastManager().add(SystemToast.create(
			client,
			SystemToast.Type.PERIODIC_NOTIFICATION,
			Text.literal(title),
			Text.literal(description).formatted(error ? Formatting.RED : Formatting.GREEN)
		)));
	}

	public static void clearSyntheticInputs() {
		syntheticForwardPressed = false;
		syntheticBackPressed = false;
		syntheticLeftPressed = false;
		syntheticRightPressed = false;
		syntheticSneakPressed = false;
		syntheticSprintPressed = false;

		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			client.execute(() -> applySyntheticInputState(client));
		}
	}

	private static List<String> splitForChat(String message) {
		List<String> chunks = new ArrayList<>();
		String remaining = message.replace('\r', ' ');

		while (remaining.length() > MAX_CHAT_MESSAGE_LENGTH) {
			chunks.add(remaining.substring(0, MAX_CHAT_MESSAGE_LENGTH));
			remaining = remaining.substring(MAX_CHAT_MESSAGE_LENGTH);
		}

		if (!remaining.isEmpty()) {
			chunks.add(remaining);
		}

		return chunks;
	}

	private final class ClientApi implements PythonBridgeServer.Api {
		@Override
		public JsonElement sendChat(String message) {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				if (player.networkHandler == null) {
					throw new IllegalStateException("Network handler is not available");
				}

				if (message.startsWith("/")) {
					player.networkHandler.sendChatCommand(message.substring(1));
				} else {
					player.networkHandler.sendChatMessage(message);
				}

				JsonObject result = new JsonObject();
				result.addProperty("sent", true);
				result.addProperty("message", message);
				return result;
			});
		}

		@Override
		public JsonElement getChat(int limit) {
			JsonArray lines = new JsonArray();
			synchronized (MinescriptClient.class) {
				RECENT_CHAT.stream()
					.skip(Math.max(0, RECENT_CHAT.size() - Math.max(1, limit)))
					.forEach(lines::add);
			}

			return lines;
		}

		@Override
		public JsonElement getPlayerPos() {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				JsonObject result = new JsonObject();
				result.addProperty("x", player.getX());
				result.addProperty("y", player.getY());
				result.addProperty("z", player.getZ());
				result.addProperty("yaw", player.getYaw());
				result.addProperty("pitch", player.getPitch());
				result.addProperty("block_x", player.getBlockX());
				result.addProperty("block_y", player.getBlockY());
				result.addProperty("block_z", player.getBlockZ());
				return result;
			});
		}

		@Override
		public JsonElement lookAt(double x, double y, double z) {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				Vec3d eyes = player.getEyePos();
				double deltaX = x - eyes.x;
				double deltaY = y - eyes.y;
				double deltaZ = z - eyes.z;
				double horizontal = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
				float yaw = (float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0);
				float pitch = (float) (-Math.toDegrees(Math.atan2(deltaY, horizontal)));

				player.setYaw(MathHelper.wrapDegrees(yaw));
				player.setPitch(MathHelper.wrapDegrees(pitch));
				player.setHeadYaw(player.getYaw());

				JsonObject result = new JsonObject();
				result.addProperty("yaw", player.getYaw());
				result.addProperty("pitch", player.getPitch());
				return result;
			});
		}

		@Override
		public JsonElement rightClick() {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				if (client.interactionManager == null) {
					throw new IllegalStateException("Client interaction is not available");
				}

				if (client.crosshairTarget instanceof EntityHitResult entityHitResult) {
					client.interactionManager.interactEntity(player, entityHitResult.getEntity(), Hand.MAIN_HAND);
				} else {
					client.interactionManager.interactItem(player, Hand.MAIN_HAND);
				}

				player.swingHand(Hand.MAIN_HAND);
				JsonObject result = new JsonObject();
				result.addProperty("used", true);
				return result;
			});
		}

		@Override
		public JsonElement leftClick() {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				if (client.interactionManager == null) {
					throw new IllegalStateException("Client interaction is not available");
				}

				if (client.crosshairTarget instanceof EntityHitResult entityHitResult) {
					client.interactionManager.attackEntity(player, entityHitResult.getEntity());
				} else if (client.crosshairTarget instanceof BlockHitResult blockHitResult) {
					client.interactionManager.attackBlock(blockHitResult.getBlockPos(), blockHitResult.getSide());
				}

				player.swingHand(Hand.MAIN_HAND);
				JsonObject result = new JsonObject();
				result.addProperty("attacked", true);
				return result;
			});
		}

		@Override
		public JsonElement jump() {
			return runOnClientThread(() -> {
				ClientPlayerEntity player = requirePlayer(MinecraftClient.getInstance());
				player.jump();
				JsonObject result = new JsonObject();
				result.addProperty("jumped", true);
				return result;
			});
		}

		@Override
		public JsonElement setForwardPressed(boolean pressed) {
			return runOnClientThread(() -> setMovementKey("forward", pressed));
		}

		@Override
		public JsonElement setBackPressed(boolean pressed) {
			return runOnClientThread(() -> setMovementKey("back", pressed));
		}

		@Override
		public JsonElement setLeftPressed(boolean pressed) {
			return runOnClientThread(() -> setMovementKey("left", pressed));
		}

		@Override
		public JsonElement setRightPressed(boolean pressed) {
			return runOnClientThread(() -> setMovementKey("right", pressed));
		}

		@Override
		public JsonElement stopMoving() {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				requirePlayer(client);
				syntheticForwardPressed = false;
				syntheticBackPressed = false;
				syntheticLeftPressed = false;
				syntheticRightPressed = false;
				applySyntheticInputState(client);

				JsonObject result = new JsonObject();
				result.addProperty("moving", false);
				return result;
			});
		}

		@Override
		public JsonElement setSneaking(boolean sneaking) {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				syntheticSneakPressed = sneaking;
				player.setSneaking(sneaking);
				client.options.sneakKey.setPressed(sneaking);
				JsonObject result = new JsonObject();
				result.addProperty("sneaking", sneaking);
				return result;
			});
		}

		@Override
		public JsonElement setSprinting(boolean sprinting) {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				syntheticSprintPressed = sprinting;
				player.setSprinting(sprinting);
				client.options.sprintKey.setPressed(sprinting);
				JsonObject result = new JsonObject();
				result.addProperty("sprinting", sprinting);
				return result;
			});
		}

		@Override
		public JsonElement getObjectAtInventorySlot(int slotIndex) {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				if (client.player == null) {
					throw new IllegalStateException("Player is not available");
				}

				ScreenHandler screenHandler = client.player.currentScreenHandler;
				if (slotIndex < 0 || slotIndex >= screenHandler.slots.size()) {
					throw new IllegalArgumentException("Slot out of range: " + slotIndex);
				}

				ItemStack stack = screenHandler.slots.get(slotIndex).getStack();
				JsonObject result = new JsonObject();
				result.addProperty("slot", slotIndex);
				result.addProperty("empty", stack.isEmpty());
				result.addProperty("count", stack.getCount());
				result.addProperty("name", stack.getName().getString());
				result.addProperty("item_id", stack.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(stack.getItem()).toString());
				result.addProperty("sync_id", screenHandler.syncId);
				result.addProperty("slot_count", screenHandler.slots.size());
				return result;
			});
		}

		@Override
		public JsonElement getInventory() {
			return runOnClientThread(() -> {
				ClientPlayerEntity player = requirePlayer(MinecraftClient.getInstance());
				ScreenHandler screenHandler = player.currentScreenHandler;
				JsonArray slots = new JsonArray();
				for (int slotIndex = 0; slotIndex < screenHandler.slots.size(); slotIndex++) {
					slots.add(serializeSlot(screenHandler, slotIndex));
				}
				return slots;
			});
		}

		@Override
		public JsonElement quickMoveSlot(int slotIndex) {
			return clickSlot(slotIndex, 0, SlotActionType.QUICK_MOVE.name());
		}

		@Override
		public JsonElement dropSlot(int slotIndex) {
			return clickSlot(slotIndex, 0, SlotActionType.THROW.name());
		}

		@Override
		public JsonElement swapSlots(int firstSlot, int secondSlot) {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				ScreenHandler screenHandler = player.currentScreenHandler;
				validateSlot(screenHandler, firstSlot);
				validateSlot(screenHandler, secondSlot);

				client.interactionManager.clickSlot(screenHandler.syncId, firstSlot, 0, SlotActionType.PICKUP, player);
				client.interactionManager.clickSlot(screenHandler.syncId, secondSlot, 0, SlotActionType.PICKUP, player);
				client.interactionManager.clickSlot(screenHandler.syncId, firstSlot, 0, SlotActionType.PICKUP, player);

				JsonObject result = new JsonObject();
				result.addProperty("swapped", true);
				result.addProperty("slot_a", firstSlot);
				result.addProperty("slot_b", secondSlot);
				return result;
			});
		}

		@Override
		public JsonElement getSelectedHotbarSlot() {
			return runOnClientThread(() -> {
				ClientPlayerEntity player = requirePlayer(MinecraftClient.getInstance());
				JsonObject result = new JsonObject();
				result.addProperty("slot", player.getInventory().selectedSlot);
				return result;
			});
		}

		@Override
		public JsonElement selectHotbarSlot(int slotIndex) {
			return runOnClientThread(() -> {
				ClientPlayerEntity player = requirePlayer(MinecraftClient.getInstance());
				if (slotIndex < 0 || slotIndex > 8) {
					throw new IllegalArgumentException("Hotbar slot must be between 0 and 8");
				}

				player.getInventory().selectedSlot = slotIndex;
				JsonObject result = new JsonObject();
				result.addProperty("slot", slotIndex);
				return result;
			});
		}

		@Override
		public JsonElement getLeaderboard() {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				if (client.world == null) {
					throw new IllegalStateException("World is not available");
				}

				Scoreboard scoreboard = client.world.getScoreboard();
				ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
				JsonObject result = new JsonObject();

				if (objective == null) {
					result.addProperty("title", "");
					result.add("entries", new JsonArray());
					return result;
				}

				result.addProperty("title", objective.getDisplayName().getString());

				List<ScoreEntry> entries = new ArrayList<>();
				for (ScoreHolder scoreHolder : scoreboard.getKnownScoreHolders()) {
					ReadableScoreboardScore score = scoreboard.getScore(scoreHolder, objective);
					if (score == null) {
						continue;
					}

					entries.add(new ScoreEntry(scoreHolder.getNameForScoreboard(), score.getScore()));
				}

				entries.sort(Comparator.comparingInt(ScoreEntry::score).reversed().thenComparing(ScoreEntry::name));

				JsonArray jsonEntries = new JsonArray();
				for (ScoreEntry entry : entries) {
					JsonObject jsonEntry = new JsonObject();
					jsonEntry.addProperty("name", entry.name());
					jsonEntry.addProperty("score", entry.score());
					jsonEntries.add(jsonEntry);
				}

				result.add("entries", jsonEntries);
				return result;
			});
		}

		@Override
		public JsonElement clickSlot(int slotIndex, int button, String actionType) {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				if (client.player == null || client.interactionManager == null) {
					throw new IllegalStateException("Client interaction is not available");
				}

				ScreenHandler screenHandler = client.player.currentScreenHandler;
				validateSlot(screenHandler, slotIndex);

				SlotActionType slotActionType = SlotActionType.valueOf(actionType.toUpperCase(Locale.ROOT));
				client.interactionManager.clickSlot(screenHandler.syncId, slotIndex, button, slotActionType, client.player);

				JsonObject result = new JsonObject();
				result.addProperty("clicked", true);
				result.addProperty("slot", slotIndex);
				result.addProperty("button", button);
				result.addProperty("action_type", slotActionType.name());
				result.addProperty("sync_id", screenHandler.syncId);
				return result;
			});
		}

		@Override
		public JsonElement getTargetBlock() {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				requirePlayer(client);
				if (!(client.crosshairTarget instanceof BlockHitResult blockHitResult)) {
					return JsonNull.INSTANCE;
				}

				BlockPos blockPos = blockHitResult.getBlockPos();
				var blockState = client.world.getBlockState(blockPos);
				JsonObject result = new JsonObject();
				result.addProperty("x", blockPos.getX());
				result.addProperty("y", blockPos.getY());
				result.addProperty("z", blockPos.getZ());
				result.addProperty("side", blockHitResult.getSide().getName());
				result.addProperty("block_id", Registries.BLOCK.getId(blockState.getBlock()).toString());
				result.addProperty("replaceable", blockState.isReplaceable());
				return result;
			});
		}

		@Override
		public JsonElement getTargetEntity() {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				if (!(client.crosshairTarget instanceof EntityHitResult entityHitResult)) {
					return JsonNull.INSTANCE;
				}

				return serializeEntity(entityHitResult.getEntity(), player);
			});
		}

		@Override
		public JsonElement getHealth() {
			return runOnClientThread(() -> {
				ClientPlayerEntity player = requirePlayer(MinecraftClient.getInstance());
				JsonObject result = new JsonObject();
				result.addProperty("health", player.getHealth());
				result.addProperty("max_health", player.getMaxHealth());
				return result;
			});
		}

		@Override
		public JsonElement getHunger() {
			return runOnClientThread(() -> {
				ClientPlayerEntity player = requirePlayer(MinecraftClient.getInstance());
				HungerManager hungerManager = player.getHungerManager();
				JsonObject result = new JsonObject();
				result.addProperty("food", hungerManager.getFoodLevel());
				result.addProperty("saturation", hungerManager.getSaturationLevel());
				return result;
			});
		}

		@Override
		public JsonElement getArmor() {
			return runOnClientThread(() -> {
				ClientPlayerEntity player = requirePlayer(MinecraftClient.getInstance());
				JsonObject result = new JsonObject();
				result.addProperty("armor", player.getArmor());
				return result;
			});
		}

		@Override
		public JsonElement getDimension() {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				requirePlayer(client);
				JsonObject result = new JsonObject();
				result.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
				return result;
			});
		}

		@Override
		public JsonElement getBiome() {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				RegistryEntry<Biome> biome = client.world.getBiome(player.getBlockPos());
				JsonObject result = new JsonObject();
				result.addProperty("biome", biome.getKey().map(key -> key.getValue().toString()).orElse("unknown"));
				return result;
			});
		}

		@Override
		public JsonElement getNearbyEntities(double radius) {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				Box searchBox = player.getBoundingBox().expand(radius);
				List<Entity> entities = client.world.getOtherEntities(player, searchBox, entity -> true);
				JsonArray result = new JsonArray();
				for (Entity entity : entities) {
					result.add(serializeEntity(entity, player));
				}
				return result;
			});
		}

		@Override
		public JsonElement getNearbyPlayers(double radius) {
			return runOnClientThread(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				ClientPlayerEntity player = requirePlayer(client);
				Box searchBox = player.getBoundingBox().expand(radius);
				JsonArray result = new JsonArray();
				for (AbstractClientPlayerEntity nearbyPlayer : client.world.getPlayers()) {
					if (nearbyPlayer == player || !nearbyPlayer.getBoundingBox().intersects(searchBox)) {
						continue;
					}

					result.add(serializeNearbyPlayer(nearbyPlayer, player));
				}
				return result;
			});
		}

		@Override
		public JsonElement pollEvents(List<String> eventTypes, int limit) {
			JsonArray result = new JsonArray();
			Set<String> filters = Set.copyOf(eventTypes);
			synchronized (MinescriptClient.class) {
				Iterator<JsonObject> iterator = EVENT_QUEUE.iterator();
				while (iterator.hasNext() && result.size() < Math.max(1, limit)) {
					JsonObject event = iterator.next();
					String type = event.get("type").getAsString();
					if (!filters.isEmpty() && !filters.contains(type)) {
						continue;
					}

					result.add(event.deepCopy());
					iterator.remove();
				}
			}

			return result;
		}
	}

	private <T> T runOnClientThread(Supplier<T> supplier) {
		MinecraftClient client = MinecraftClient.getInstance();
		CompletableFuture<T> future = new CompletableFuture<>();
		client.execute(() -> {
			try {
				future.complete(supplier.get());
			} catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});

		try {
			return future.get();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for client thread", exception);
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}

			throw new IllegalStateException("Client action failed", cause);
		}
	}

	private ClientPlayerEntity requirePlayer(MinecraftClient client) {
		if (client.player == null || client.world == null) {
			throw new IllegalStateException("Player is not available");
		}

		return client.player;
	}

	private void validateSlot(ScreenHandler screenHandler, int slotIndex) {
		if (slotIndex < 0 || slotIndex >= screenHandler.slots.size()) {
			throw new IllegalArgumentException("Slot out of range: " + slotIndex);
		}
	}

	private JsonObject serializeSlot(ScreenHandler screenHandler, int slotIndex) {
		Slot slot = screenHandler.slots.get(slotIndex);
		ItemStack stack = slot.getStack();
		JsonObject result = new JsonObject();
		result.addProperty("slot", slotIndex);
		result.addProperty("id", slot.id);
		result.addProperty("empty", stack.isEmpty());
		result.addProperty("count", stack.getCount());
		result.addProperty("name", stack.getName().getString());
		result.addProperty("item_id", stack.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(stack.getItem()).toString());
		result.addProperty("can_take", slot.canTakeItems(MinecraftClient.getInstance().player));
		return result;
	}

	private JsonObject serializeEntity(Entity entity, ClientPlayerEntity player) {
		JsonObject result = new JsonObject();
		result.addProperty("id", entity.getId());
		result.addProperty("uuid", entity.getUuidAsString());
		result.addProperty("name", entity.getName().getString());
		result.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
		result.addProperty("entity_type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
		result.addProperty("x", entity.getX());
		result.addProperty("y", entity.getY());
		result.addProperty("z", entity.getZ());
		result.addProperty("yaw", entity.getYaw());
		result.addProperty("pitch", entity.getPitch());
		result.addProperty("distance", player.distanceTo(entity));
		return result;
	}

	private JsonObject serializeNearbyPlayer(AbstractClientPlayerEntity nearbyPlayer, ClientPlayerEntity sourcePlayer) {
		JsonObject result = serializeEntity(nearbyPlayer, sourcePlayer);
		result.addProperty("player_name", nearbyPlayer.getGameProfile().getName());
		result.addProperty("display_name", nearbyPlayer.getDisplayName().getString());
		result.addProperty("main_hand", describeItemStack(nearbyPlayer.getMainHandStack()));
		result.addProperty("off_hand", describeItemStack(nearbyPlayer.getOffHandStack()));
		result.addProperty("facing", describeFacing(nearbyPlayer.getYaw()));
		result.addProperty("body_yaw", nearbyPlayer.bodyYaw);
		result.addProperty("head_yaw", nearbyPlayer.headYaw);
		result.addProperty("on_ground", nearbyPlayer.isOnGround());
		result.addProperty("sneaking", nearbyPlayer.isSneaking());
		result.addProperty("sprinting", nearbyPlayer.isSprinting());
		result.addProperty("flying", nearbyPlayer.getAbilities().flying);

		if (nearbyPlayer instanceof LivingEntity livingEntity) {
			result.addProperty("health", livingEntity.getHealth());
			result.addProperty("max_health", livingEntity.getMaxHealth());
		}

		Vec3d velocity = nearbyPlayer.getVelocity();
		JsonObject velocityJson = new JsonObject();
		velocityJson.addProperty("x", velocity.x);
		velocityJson.addProperty("y", velocity.y);
		velocityJson.addProperty("z", velocity.z);
		result.add("velocity", velocityJson);
		return result;
	}

	private String describeItemStack(ItemStack stack) {
		return stack.isEmpty()
			? "minecraft:air"
			: Registries.ITEM.getId(stack.getItem()).toString();
	}

	private String describeFacing(float yaw) {
		int index = MathHelper.floor((yaw % 360.0F) / 45.0F + 0.5F) & 7;
		return switch (index) {
			case 0 -> "south";
			case 1 -> "south_west";
			case 2 -> "west";
			case 3 -> "north_west";
			case 4 -> "north";
			case 5 -> "north_east";
			case 6 -> "east";
			case 7 -> "south_east";
			default -> "unknown";
		};
	}

	private JsonObject setMovementKey(String direction, boolean pressed) {
		MinecraftClient client = MinecraftClient.getInstance();
		requirePlayer(client);

		switch (direction) {
			case "forward" -> syntheticForwardPressed = pressed;
			case "back" -> syntheticBackPressed = pressed;
			case "left" -> syntheticLeftPressed = pressed;
			case "right" -> syntheticRightPressed = pressed;
			default -> throw new IllegalArgumentException("Unknown movement direction: " + direction);
		}

		applySyntheticInputState(client);

		JsonObject result = new JsonObject();
		result.addProperty("direction", direction);
		result.addProperty("pressed", pressed);
		return result;
	}

	private static void applySyntheticInputState(MinecraftClient client) {
		client.options.forwardKey.setPressed(syntheticForwardPressed);
		client.options.backKey.setPressed(syntheticBackPressed);
		client.options.leftKey.setPressed(syntheticLeftPressed);
		client.options.rightKey.setPressed(syntheticRightPressed);
		client.options.sneakKey.setPressed(syntheticSneakPressed);
		client.options.sprintKey.setPressed(syntheticSprintPressed);

		if (client.player != null) {
			client.player.setSneaking(syntheticSneakPressed);
			client.player.setSprinting(syntheticSprintPressed);
		}
	}

	private record ScoreEntry(String name, int score) {
	}
}