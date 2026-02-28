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
        });

        LOGGER.info("[CobwebCounter] Initialized");
    }

    private void onTick(MinecraftClient client) {
        if (client.world == null) return;

        if (resetKeybind != null) {
            while (resetKeybind.wasPressed()) {
                clearAll();
            }
        }

        Set<UUID> active = new HashSet<>();
        for (PlayerEntity player : client.world.getPlayers()) {
            active.add(player.getUuid());
        }
        COUNTS.keySet().retainAll(active);
        COUNTED_THIS_TICK.clear();
    }

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

    public static Text buildCounterText(int count) {
        Text cobwebIcon = Text.literal(COBWEB_ICON)
                .setStyle(Style.EMPTY.withFont(COBWEB_FONT));
        return Text.literal(String.valueOf(count)).append(Text.literal(" ")).append(cobwebIcon);
    }

    public static void clearAll() {
        COUNTS.clear();
        COUNTED_THIS_TICK.clear();
    }
}
