package com.jellomakker.goldenapplecounter;

import com.jellomakker.goldenapplecounter.config.GoldenAppleCounterConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GoldenAppleCounterClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("goldenapplecounter");
    private static boolean loggedFirstRender = false;
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

        // Render counters above player heads.
        // The Fabric rendering API package moved in newer versions, so we try
        // both the old and new locations to work on MC 1.21.5 through 1.21.11+.
        registerRenderEvent();
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

    // ── Cross-version Fabric render event registration ──────────────────────

    private static void registerRenderEvent() {
        // Try old Fabric API package (MC 1.21.5 – ...rendering.v1.WorldRenderEvents)
        try {
            Class.forName("net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents");
            OldApiRenderer.register();
            LOGGER.info("[GoldenAppleCounter] Registered render event via OLD Fabric API (rendering.v1.WorldRenderEvents)");
            return;
        } catch (Throwable e) {
            LOGGER.info("[GoldenAppleCounter] Old API not available: {}", e.getMessage());
        }

        // Try new Fabric API package (MC 1.21.11+ – ...rendering.v1.world.WorldRenderEvents)
        try {
            registerNewApiRenderEvent();
            LOGGER.info("[GoldenAppleCounter] Registered render event via NEW Fabric API (rendering.v1.world.WorldRenderEvents)");
        } catch (Throwable e) {
            LOGGER.error("[GoldenAppleCounter] FAILED to register render event via BOTH old and new API!", e);
        }
    }

    /**
     * Old API path – this inner class is only loaded when the old-package
     * WorldRenderEvents exists, preventing NoClassDefFoundError on 1.21.11+.
     */
    private static class OldApiRenderer {
        static void register() {
            net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
                    .AFTER_ENTITIES.register(context -> {
                        try {
                            float tickDelta = context.tickCounter().getTickProgress(false);
                            doRenderCounters(context.matrixStack(), context.consumers(), tickDelta);
                        } catch (Throwable e) {
                            LOGGER.error("[GoldenAppleCounter] OldApiRenderer render error", e);
                        }
                    });
        }
    }

    /**
     * New API path – fully reflective so we never reference the new package
     * at compile-time (we compile against 1.21.5 Fabric API).
     */
    private static void registerNewApiRenderEvent() throws Exception {
        Class<?> eventsClass = Class.forName(
                "net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents");
        Field afterEntitiesField = eventsClass.getField("AFTER_ENTITIES");
        Object event = afterEntitiesField.get(null);

        Class<?> listenerInterface = Class.forName(
                "net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents$AfterEntities");

        Object proxy = Proxy.newProxyInstance(
                listenerInterface.getClassLoader(),
                new Class[]{listenerInterface},
                (p, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(p);
                            case "equals"   -> p == args[0];
                            case "toString" -> "GoldenAppleCounterRenderProxy";
                            default         -> null;
                        };
                    }
                    if (args != null && args.length == 1) {
                        try {
                            MatrixStack matrices = extractMatrixStack(args[0]);
                            VertexConsumerProvider consumers = extractConsumers(args[0]);
                            if (matrices != null) {
                                doRenderCounters(matrices, consumers, getTickDelta());
                            } else {
                                LOGGER.warn("[GoldenAppleCounter] NewAPI: extractMatrixStack returned null");
                            }
                        } catch (Throwable e) {
                            LOGGER.error("[GoldenAppleCounter] NewAPI render error", e);
                        }
                    }
                    return null;
                });

        for (Method m : event.getClass().getMethods()) {
            if (m.getName().equals("register") && m.getParameterCount() == 1) {
                m.invoke(event, proxy);
                break;
            }
        }
    }

    /** Try to pull a MatrixStack from a WorldRenderContext of any API version. */
    private static MatrixStack extractMatrixStack(Object context) {
        for (String name : new String[]{"matrices", "matrixStack"}) {
            try {
                Method m = context.getClass().getMethod(name);
                Object result = m.invoke(context);
                if (result instanceof MatrixStack ms) return ms;
            } catch (Throwable ignored) {}
        }
        LOGGER.warn("[GoldenAppleCounter] Could not extract MatrixStack from context: {}", context.getClass().getName());
        return null;
    }

    /** Try to pull a VertexConsumerProvider from a WorldRenderContext of any API version. */
    private static VertexConsumerProvider extractConsumers(Object context) {
        try {
            Method m = context.getClass().getMethod("consumers");
            Object result = m.invoke(context);
            if (result instanceof VertexConsumerProvider vcp) return vcp;
        } catch (Throwable ignored) {}
        LOGGER.warn("[GoldenAppleCounter] Could not extract consumers from context, will use own Immediate");
        return null;
    }

    /**
     * Get camera position, working across MC versions.
     * Camera.getPos() was removed in 1.21.11 but the pos field still exists.
     */
    private static Vec3d getCameraPos(Camera camera) {
        // Try the method first (exists in 1.21.5)
        try {
            Method m = Camera.class.getMethod("getPos");
            return (Vec3d) m.invoke(camera);
        } catch (Throwable ignored) {}
        // Fall back to reading the pos field directly (1.21.11+)
        try {
            for (java.lang.reflect.Field f : Camera.class.getDeclaredFields()) {
                if (Vec3d.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Vec3d pos = (Vec3d) f.get(camera);
                    return pos;
                }
            }
        } catch (Throwable e) {
            LOGGER.error("[GoldenAppleCounter] Failed to read camera pos field", e);
        }
        LOGGER.warn("[GoldenAppleCounter] getCameraPos: no Vec3d field found on Camera class");
        return null;
    }

    /** Get tick delta from MinecraftClient, with a safe fallback. */
    private static float getTickDelta() {
        try {
            return MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false);
        } catch (Throwable e) {
            return 1.0f;
        }
    }

    // ── Shared rendering logic ───────────────────────────────────────────────

    private static void doRenderCounters(MatrixStack matrices, VertexConsumerProvider contextConsumers, float tickDelta) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) return;

            GoldenAppleCounterConfig config = GoldenAppleCounterConfig.get();
            if (!config.enabled || !config.showOnPlayerName) return;

            Camera camera = client.gameRenderer.getCamera();
            Vec3d cameraPos = getCameraPos(camera);
            if (cameraPos == null) {
                LOGGER.warn("[GoldenAppleCounter] cameraPos is null, skipping render");
                return;
            }

            // Use context consumers if available; otherwise fall back to own Immediate
            boolean usingOwnImmediate = false;
            VertexConsumerProvider consumers;
            VertexConsumerProvider.Immediate ownImmediate = null;
            if (contextConsumers != null) {
                consumers = contextConsumers;
            } else {
                ownImmediate = client.getBufferBuilders().getEntityVertexConsumers();
                consumers = ownImmediate;
                usingOwnImmediate = true;
            }
            TextRenderer textRenderer = client.textRenderer;

            boolean anyRendered = false;

            for (PlayerEntity player : client.world.getPlayers()) {
                UUID uuid = player.getUuid();

                if (!config.includeSelfDisplay && uuid.equals(client.player.getUuid())) {
                    continue;
                }

                int count = getCount(uuid);
                if (count <= 0) continue;

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

                textRenderer.draw(label, textX, 0, 0x20FFFFFF, false, matrix, consumers,
                        TextRenderer.TextLayerType.SEE_THROUGH, bgColor, light);
                textRenderer.draw(label, textX, 0, 0xFFFFFFFF, false, matrix, consumers,
                        TextRenderer.TextLayerType.NORMAL, 0, light);

                matrices.pop();
                anyRendered = true;
            }

            if (anyRendered && usingOwnImmediate && ownImmediate != null) {
                ownImmediate.draw();
            }

            if (!loggedFirstRender && anyRendered) {
                LOGGER.info("[GoldenAppleCounter] First render frame OK (usingOwnImmediate={})", usingOwnImmediate);
                loggedFirstRender = true;
            }
        } catch (Throwable e) {
            LOGGER.error("[GoldenAppleCounter] doRenderCounters exception", e);
        }
    }
}
