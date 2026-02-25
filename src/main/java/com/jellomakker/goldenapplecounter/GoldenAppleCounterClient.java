package com.jellomakker.goldenapplecounter;

import com.jellomakker.goldenapplecounter.config.GoldenAppleCounterConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GoldenAppleCounterClient implements ClientModInitializer {
    public static final String MOD_ID = "goldenapplecounter";

    public static final Identifier GOLDEN_APPLE_FONT = Identifier.of(MOD_ID, "golden_apple");
    public static final String GOLDEN_APPLE_ICON = "\uE200";

    private static final Map<UUID, Integer> COUNTS = new ConcurrentHashMap<>();

    /**
     * Per-player eating state tracking.
     * [0] = wasEating (0 or 1)
     * [1] = golden apple stack count in eating hand (baseline)
     * [2] = active hand ordinal (0=MAIN, 1=OFF)
     * [3] = grace ticks (ticks since eating stopped, for equipment sync delay)
     */
    private static final Map<UUID, int[]> EAT_STATE = new ConcurrentHashMap<>();

    /**
     * Track the golden apple stack count per player when they are NOT eating.
     * This lets us detect the first apple being consumed even if the stack
     * decrements before we see isUsingItem() go true.
     */
    private static final Map<UUID, int[]> IDLE_STACK = new ConcurrentHashMap<>();

    /** Entity network ID → UUID, populated each tick for the renderer mixin. */
    private static final Map<Integer, UUID> ID_TO_UUID = new ConcurrentHashMap<>();

    /** Ticks to wait after eating stops for the equipment update to arrive. */
    private static final int GRACE_TICKS = 6;

    private static KeyBinding resetKeybind;

    @Override
    public void onInitializeClient() {
        GoldenAppleCounterConfig.get();

        // KeyBinding constructor changed across versions (category went from
        // String → enum in 1.21.11+). Try the compile-time signature first,
        // then fall back to reflection so we work on both.
        try {
            resetKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.goldenapplecounter.reset",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN,
                    "category.goldenapplecounter"
            ));
        } catch (Throwable e) {
            resetKeybind = createKeybindReflective();
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            COUNTS.clear();
            EAT_STATE.clear();
            IDLE_STACK.clear();
            ID_TO_UUID.clear();
        });

        // Render counters above player heads using Fabric's stable rendering event.
        // This avoids injecting into EntityRenderer.render() whose signature changes
        // between MC versions (1.21.5 vs 1.21.11+).
        WorldRenderEvents.AFTER_ENTITIES.register(GoldenAppleCounterClient::renderCounters);
    }

    /**
     * Reflective fallback for MC versions where the KeyBinding constructor
     * signature changed (e.g. 1.21.11+ uses a Category enum instead of String).
     */
    private static KeyBinding createKeybindReflective() {
        try {
            for (Constructor<?> ctor : KeyBinding.class.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length >= 3 && params[0] == String.class) {
                    if (params.length == 3 && params[1] == int.class) {
                        Object category = resolveCategoryEnum(params[2], "category.goldenapplecounter");
                        if (category != null) {
                            ctor.setAccessible(true);
                            return (KeyBinding) ctor.newInstance(
                                    "key.goldenapplecounter.reset",
                                    GLFW.GLFW_KEY_UNKNOWN,
                                    category);
                        }
                    }
                    if (params.length == 4 && params[2] == int.class) {
                        Object category = resolveCategoryEnum(params[3], "category.goldenapplecounter");
                        if (category != null) {
                            ctor.setAccessible(true);
                            return (KeyBinding) ctor.newInstance(
                                    "key.goldenapplecounter.reset",
                                    InputUtil.Type.KEYSYM,
                                    GLFW.GLFW_KEY_UNKNOWN,
                                    category);
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object resolveCategoryEnum(Class<?> type, String name) {
        if (type == String.class) return name;
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (constant.toString().toLowerCase().contains("misc")
                        || constant.toString().toLowerCase().contains("gameplay")) {
                    return constant;
                }
            }
            Object[] constants = type.getEnumConstants();
            if (constants.length > 0) return constants[0];
        }
        return null;
    }

    private void onTick(MinecraftClient client) {
        if (client.world == null) return;

        // Check reset keybind
        if (resetKeybind != null) {
            while (resetKeybind.wasPressed()) {
                clearAll();
            }
        }

        GoldenAppleCounterConfig config = GoldenAppleCounterConfig.get();
        Set<UUID> active = new HashSet<>();

        for (PlayerEntity player : client.world.getPlayers()) {
            UUID uuid = player.getUuid();
            active.add(uuid);
            ID_TO_UUID.put(player.getId(), uuid);

            if (!config.includeSelfDisplay && client.player != null
                    && uuid.equals(client.player.getUuid())) {
                continue;
            }

            // --- Golden apple tracking ---
            boolean eating = player.isUsingItem()
                    && isTrackedApple(player.getActiveItem().getItem(), config);

            int[] s = EAT_STATE.computeIfAbsent(uuid, k -> new int[]{0, 0, 0, 0});

            if (eating) {
                Hand hand = player.getActiveHand();
                int handCount = player.getStackInHand(hand).getCount();

                if (s[0] == 0) {
                    // Just started eating — use the idle stack count as the true baseline,
                    // since the stack may have already decremented by the time we see
                    // isUsingItem() go true.
                    int[] idle = IDLE_STACK.get(uuid);
                    int baseline = handCount;
                    if (idle != null && idle[0] == hand.ordinal() && idle[1] > handCount) {
                        // The idle count was higher → stack already decreased, count the diff
                        int missed = idle[1] - handCount;
                        for (int i = 0; i < missed; i++) increment(uuid);
                    }
                    s[0] = 1;
                    s[1] = handCount;
                    s[2] = hand.ordinal();
                    s[3] = 0;
                } else {
                    // Continuing to eat — check for mid-eat stack decrease
                    // One eating animation = one apple, so count 1 per decrease
                    if (handCount < s[1]) {
                        increment(uuid);
                        s[1] = handCount;
                    }
                }
            } else {
                // Not eating this tick
                if (s[0] == 1) {
                    // Was eating — check if the same hand still has golden apples
                    Hand hand = Hand.values()[s[2]];
                    Item handItem = player.getStackInHand(hand).getItem();
                    boolean stillHasApple = handItem == Items.GOLDEN_APPLE
                            || handItem == Items.ENCHANTED_GOLDEN_APPLE;
                    int handCount = stillHasApple ? player.getStackInHand(hand).getCount() : -1;

                    if (handCount >= 0 && handCount < s[1]) {
                        // Same item, stack decreased → ate exactly 1
                        increment(uuid);
                        resetState(s);
                    } else if (!stillHasApple) {
                        // Hand changed items (slot switch or ate last one)
                        // Only count if stack was 1 (ate the last apple) or item is now empty
                        if (s[1] == 1 || player.getStackInHand(hand).isEmpty()) {
                            increment(uuid);
                        }
                        // If they just switched slots, s[1] > 1 and hand is non-empty
                        // non-apple → don't count (they cancelled/switched)
                        resetState(s);
                    } else {
                        // Stack didn't decrease yet — equipment sync might be delayed
                        s[3]++;
                        if (s[3] > GRACE_TICKS) {
                            // No change → they cancelled eating, discard
                            resetState(s);
                        }
                    }
                }

                // Track idle golden apple stack for next eat detection
                updateIdleStack(uuid, player);
            }
        }

        COUNTS.keySet().retainAll(active);
        EAT_STATE.keySet().retainAll(active);
        IDLE_STACK.keySet().retainAll(active);
        ID_TO_UUID.values().retainAll(active);
    }

    /** Record the current golden apple stack count in both hands when not eating. */
    private void updateIdleStack(UUID uuid, PlayerEntity player) {
        for (Hand hand : Hand.values()) {
            Item item = player.getStackInHand(hand).getItem();
            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                IDLE_STACK.put(uuid, new int[]{hand.ordinal(), player.getStackInHand(hand).getCount()});
                return;
            }
        }
        IDLE_STACK.remove(uuid);
    }

    private static void resetState(int[] s) {
        s[0] = 0;
        s[1] = 0;
        s[2] = 0;
        s[3] = 0;
    }

    private static boolean isTrackedApple(Item item, GoldenAppleCounterConfig config) {
        return (item == Items.GOLDEN_APPLE && config.countNormalGoldenApple)
                || (item == Items.ENCHANTED_GOLDEN_APPLE && config.countEnchantedGoldenApple);
    }

    public static void increment(UUID playerUuid) {
        COUNTS.merge(playerUuid, 1, Integer::sum);
    }

    public static int getCount(UUID playerUuid) {
        return COUNTS.getOrDefault(playerUuid, 0);
    }

    /** Look up UUID from an entity network id. */
    public static UUID getUuidFromEntityId(int entityId) {
        return ID_TO_UUID.get(entityId);
    }

    /** Build the counter text with a real golden apple icon from our custom font. */
    public static Text buildCounterText(int count) {
        Text appleIcon = Text.literal(GOLDEN_APPLE_ICON)
                .setStyle(Style.EMPTY.withFont(GOLDEN_APPLE_FONT));
        return Text.literal(String.valueOf(count)).append(Text.literal(" ")).append(appleIcon);
    }

    public static void clearAll() {
        COUNTS.clear();
        EAT_STATE.clear();
        IDLE_STACK.clear();
        ID_TO_UUID.clear();
    }

    // ── Rendering via Fabric WorldRenderEvents (cross-version safe) ──────────

    /**
     * Render golden apple counters above tracked players.
     * Uses Fabric's WorldRenderEvents.AFTER_ENTITIES which provides a stable
     * API across MC versions, avoiding the EntityRenderer.render() signature
     * changes in 1.21.11+.
     */
    private static void renderCounters(WorldRenderContext context) {
        try {
            renderCountersInternal(context);
        } catch (Throwable ignored) {
            // Gracefully handle any API incompatibility across MC versions
        }
    }

    private static void renderCountersInternal(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        GoldenAppleCounterConfig config = GoldenAppleCounterConfig.get();
        if (!config.enabled || !config.showOnPlayerName) return;

        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();
        float tickDelta = context.tickCounter().getTickProgress(false);

        VertexConsumerProvider.Immediate immediate =
                client.getBufferBuilders().getEntityVertexConsumers();
        MatrixStack matrices = context.matrixStack();
        TextRenderer textRenderer = client.textRenderer;

        boolean anyRendered = false;

        for (PlayerEntity player : client.world.getPlayers()) {
            UUID uuid = player.getUuid();

            if (!config.includeSelfDisplay && uuid.equals(client.player.getUuid())) {
                continue;
            }

            int count = getCount(uuid);
            if (count <= 0) continue;

            // Interpolated position relative to camera
            Vec3d playerPos = player.getLerpedPos(tickDelta);
            double x = playerPos.x - cameraPos.x;
            double y = playerPos.y - cameraPos.y + player.getHeight() + 0.6;
            double z = playerPos.z - cameraPos.z;

            Text label = buildCounterText(count);

            matrices.push();
            matrices.translate(x, y, z);
            matrices.multiply(camera.getRotation());
            matrices.scale(-0.025f, -0.025f, 0.025f);

            float textWidth = textRenderer.getWidth(label);
            float textX = -textWidth / 2;
            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            int bgColor = config.showBackground ? (int) (0.25f * 255.0f) << 24 : 0;
            Matrix4f matrix = matrices.peek().getPositionMatrix();

            // See-through layer (visible behind blocks, semi-transparent)
            textRenderer.draw(label, textX, 0, 0x20FFFFFF, false, matrix, immediate,
                    TextRenderer.TextLayerType.SEE_THROUGH, bgColor, light);
            // Normal opaque layer
            textRenderer.draw(label, textX, 0, 0xFFFFFFFF, false, matrix, immediate,
                    TextRenderer.TextLayerType.NORMAL, 0, light);

            matrices.pop();
            anyRendered = true;
        }

        if (anyRendered) {
            immediate.draw();
        }
    }
}
