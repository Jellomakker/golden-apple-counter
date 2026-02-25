package com.jellomakker.cobwebcounter;

import com.jellomakker.cobwebcounter.config.CobwebCounterConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CobwebCounterClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("cobwebcounter");
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

        resetKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cobwebcounter.reset",
                GLFW.GLFW_KEY_UNKNOWN,
                "category.cobwebcounter"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            COUNTS.clear();
            ID_TO_UUID.clear();
        });

        LOGGER.info("[CobwebCounter] Initialized (rendering via EntityRendererMixin)");
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
        if (!COUNTED_THIS_TICK.add(pos.toImmutable())) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        CobwebCounterConfig config = CobwebCounterConfig.get();
        if (!config.enabled) return;

        Vec3d cobwebPos = Vec3d.ofCenter(pos);
        double closestDist = 7.0;
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
}
