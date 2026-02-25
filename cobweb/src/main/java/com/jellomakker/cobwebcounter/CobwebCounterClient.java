package com.jellomakker.cobwebcounter;

import com.jellomakker.cobwebcounter.config.CobwebCounterConfig;
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
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CobwebCounterClient implements ClientModInitializer {
    public static final String MOD_ID = "cobwebcounter";

    public static final Identifier COBWEB_FONT = Identifier.of(MOD_ID, "cobweb");
    public static final String COBWEB_ICON = "\uE200";

    private static final Map<UUID, Integer> COUNTS = new ConcurrentHashMap<>();

    /** Entity network ID → UUID, populated each tick for the renderer mixin. */
    private static final Map<Integer, UUID> ID_TO_UUID = new ConcurrentHashMap<>();

    /**
     * Set of block positions we have already counted, so multiple mixins
     * firing for the same placement don't double-count.
     * Cleared each tick.
     */
    private static final Set<BlockPos> COUNTED_THIS_TICK = ConcurrentHashMap.newKeySet();

    private static KeyBinding resetKeybind;

    @Override
    public void onInitializeClient() {
        CobwebCounterConfig.get();

        // KeyBinding constructor changed across versions (category went from
        // String → enum in 1.21.11+). Try the compile-time signature first,
        // then fall back to reflection so we work on both.
        try {
            resetKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.cobwebcounter.reset",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN,
                    "category.cobwebcounter"
            ));
        } catch (Throwable e) {
            resetKeybind = createKeybindReflective();
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            COUNTS.clear();
            ID_TO_UUID.clear();
        });

        // Render counters above player heads using Fabric's stable rendering event.
        // This avoids injecting into EntityRenderer.render() whose signature changes
        // between MC versions (1.21.5 vs 1.21.11+).
        WorldRenderEvents.AFTER_ENTITIES.register(CobwebCounterClient::renderCounters);
    }

    /**
     * Reflective fallback for MC versions where the KeyBinding constructor
     * signature changed (e.g. 1.21.11+ uses a Category enum instead of String).
     */
    private static KeyBinding createKeybindReflective() {
        try {
            // Look for a constructor that takes (String, <InputType>, int, <CategoryType>)
            for (Constructor<?> ctor : KeyBinding.class.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length >= 3 && params[0] == String.class) {
                    if (params.length == 3 && params[1] == int.class) {
                        // (String, int, Category) – resolve category
                        Object category = resolveCategoryEnum(params[2], "category.cobwebcounter");
                        if (category != null) {
                            ctor.setAccessible(true);
                            return (KeyBinding) ctor.newInstance(
                                    "key.cobwebcounter.reset",
                                    GLFW.GLFW_KEY_UNKNOWN,
                                    category);
                        }
                    }
                    if (params.length == 4 && params[2] == int.class) {
                        // (String, InputType, int, Category)
                        Object category = resolveCategoryEnum(params[3], "category.cobwebcounter");
                        if (category != null) {
                            ctor.setAccessible(true);
                            return (KeyBinding) ctor.newInstance(
                                    "key.cobwebcounter.reset",
                                    InputUtil.Type.KEYSYM,
                                    GLFW.GLFW_KEY_UNKNOWN,
                                    category);
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        // Absolute last resort – null keybind; reset will only work from config screen.
        return null;
    }

    /** Try to resolve a category String into whatever enum/class the constructor expects. */
    private static Object resolveCategoryEnum(Class<?> type, String name) {
        if (type == String.class) return name;
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                // Match by enum name containing MISC / GAMEPLAY, or just pick first
                if (constant.toString().toLowerCase().contains("misc")
                        || constant.toString().toLowerCase().contains("gameplay")) {
                    return constant;
                }
            }
            // If no match, return the first constant as fallback
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

        // Maintain the entity-id → UUID map for the renderer mixin
        Set<UUID> active = new HashSet<>();
        for (PlayerEntity player : client.world.getPlayers()) {
            UUID uuid = player.getUuid();
            active.add(uuid);
            ID_TO_UUID.put(player.getId(), uuid);
        }

        COUNTS.keySet().retainAll(active);
        ID_TO_UUID.values().retainAll(active);
        COUNTED_THIS_TICK.clear();
    }

    /**
     * Called from {@link com.jellomakker.cobwebcounter.mixin.ClientPlayNetworkHandlerMixin}
     * when a cobweb block appears in the world. Attributes the placement to the
     * nearest player within interaction range (6 blocks).
     */
    public static void attributeCobwebPlacement(BlockPos pos) {
        // Deduplicate: multiple mixins (packet handler + ClientWorld) may
        // both fire for the same block placement.
        if (!COUNTED_THIS_TICK.add(pos.toImmutable())) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        CobwebCounterConfig config = CobwebCounterConfig.get();
        if (!config.enabled) return;

        Vec3d cobwebPos = Vec3d.ofCenter(pos);
        double closestDist = 7.0; // generous range (interaction is ~4.5-6)
        PlayerEntity closest = null;

        for (PlayerEntity player : client.world.getPlayers()) {
            double dist = player.getPos().distanceTo(cobwebPos);
            if (dist < closestDist) {
                closestDist = dist;
                closest = player;
            }
        }

        if (closest != null) {
            UUID uuid = closest.getUuid();

            // Skip self if config says so
            if (!config.includeSelfDisplay && client.player != null
                    && uuid.equals(client.player.getUuid())) {
                return;
            }

            COUNTS.merge(uuid, 1, Integer::sum);
        }
    }

    public static int getCount(UUID playerUuid) {
        return COUNTS.getOrDefault(playerUuid, 0);
    }

    /** Look up UUID from an entity network id. */
    public static UUID getUuidFromEntityId(int entityId) {
        return ID_TO_UUID.get(entityId);
    }

    /** Build the counter text with a cobweb icon from our custom font. */
    public static Text buildCounterText(int count) {
        Text cobwebIcon = Text.literal(COBWEB_ICON)
                .setStyle(Style.EMPTY.withFont(COBWEB_FONT));
        return Text.literal(String.valueOf(count)).append(Text.literal(" ")).append(cobwebIcon);
    }

    public static void clearAll() {
        COUNTS.clear();
        ID_TO_UUID.clear();
        COUNTED_THIS_TICK.clear();
    }

    // ── Rendering via Fabric WorldRenderEvents (cross-version safe) ──────────

    /**
     * Render cobweb counters above tracked players.
     * Uses Fabric's WorldRenderEvents.AFTER_ENTITIES which provides a stable
     * API across MC versions, avoiding the EntityRenderer.render() signature
     * changes in 1.21.11+.
     * Rendered at height + 0.9 to sit ABOVE the golden apple counter (height + 0.6)
     * when both mods are installed together.
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

        CobwebCounterConfig config = CobwebCounterConfig.get();
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
            double y = playerPos.y - cameraPos.y + player.getHeight() + 0.9;
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
