package com.jellomakker.cobwebcounter.mixin;

import com.jellomakker.cobwebcounter.CobwebCounterClient;
import com.jellomakker.cobwebcounter.config.CobwebCounterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.UUID;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T, S extends EntityRenderState> {

    @Unique
    private static final ThreadLocal<Boolean> cobwebCounter$rendering = ThreadLocal.withInitial(() -> false);

    /** Cached reflective reference to the label-rendering method (version-dependent name). */
    @Unique
    private static volatile Method cobwebCounter$labelMethod;
    @Unique
    private static volatile boolean cobwebCounter$labelMethodResolved;

    /**
     * Render the cobweb counter above players.
     * Placed at height + 0.9 so it sits ABOVE the golden apple counter (height + 0.6)
     * when both mods are installed.
     */
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void cobwebCounter$afterRender(S state, MatrixStack matrices,
                                            VertexConsumerProvider vertexConsumers, int light,
                                            CallbackInfo ci) {
        if (cobwebCounter$rendering.get()) return;
        if (!(state instanceof PlayerEntityRenderState playerState)) return;

        CobwebCounterConfig config = CobwebCounterConfig.get();
        if (!config.enabled || !config.showOnPlayerName) return;

        try {
            cobwebCounter$renderLabel(state, playerState, config, matrices, vertexConsumers, light);
        } catch (Throwable ignored) {
            // Gracefully handle field/method changes across MC versions
        }
    }

    @Unique
    private void cobwebCounter$renderLabel(S state, PlayerEntityRenderState playerState,
                                            CobwebCounterConfig config,
                                            MatrixStack matrices,
                                            VertexConsumerProvider vertexConsumers, int light) {
        UUID uuid = CobwebCounterClient.getUuidFromEntityId(playerState.id);
        if (uuid == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.includeSelfDisplay && client.player != null
                && uuid.equals(client.player.getUuid())) {
            return;
        }

        int count = CobwebCounterClient.getCount(uuid);
        if (count <= 0) return;

        Vec3d originalPos = state.nameLabelPos;

        // Render at height + 0.9 — above the golden apple counter's height + 0.6
        // so both mods can be used together without overlapping.
        state.nameLabelPos = new Vec3d(0, state.height + 0.9, 0);

        Text label = CobwebCounterClient.buildCounterText(count);

        boolean wasSneaking = state.sneaking;
        if (!config.showBackground) {
            state.sneaking = true;
        }

        cobwebCounter$rendering.set(true);
        try {
            cobwebCounter$invokeLabelMethod(state, label, matrices, vertexConsumers, light);
        } finally {
            cobwebCounter$rendering.set(false);
            state.nameLabelPos = originalPos;
            state.sneaking = wasSneaking;
        }
    }

    /**
     * Reflectively invoke the label-rendering method. The name differs across
     * MC versions (renderLabelIfPresent in 1.21.5, possibly renamed later).
     * We search once and cache the result.
     */
    @Unique
    private void cobwebCounter$invokeLabelMethod(S state, Text text, MatrixStack matrices,
                                                  VertexConsumerProvider vertexConsumers, int light) {
        try {
            Method m = cobwebCounter$resolveLabelMethod();
            if (m != null) {
                m.invoke(this, state, text, matrices, vertexConsumers, light);
            }
        } catch (Throwable ignored) { }
    }

    @Unique
    private static Method cobwebCounter$resolveLabelMethod() {
        if (cobwebCounter$labelMethodResolved) return cobwebCounter$labelMethod;
        synchronized (EntityRendererMixin.class) {
            if (cobwebCounter$labelMethodResolved) return cobwebCounter$labelMethod;
            try {
                // Try each known method name
                for (String name : new String[]{"renderLabelIfPresent", "submitNameTag"}) {
                    for (Method m : EntityRenderer.class.getDeclaredMethods()) {
                        if (m.getName().equals(name) && m.getParameterCount() == 5) {
                            m.setAccessible(true);
                            cobwebCounter$labelMethod = m;
                            cobwebCounter$labelMethodResolved = true;
                            return m;
                        }
                    }
                }
                // Fallback: find any protected/public method with 5 params where
                // the second one is Text/Component
                for (Method m : EntityRenderer.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 5) {
                        Class<?>[] p = m.getParameterTypes();
                        if (Text.class.isAssignableFrom(p[1]) || p[1].getSimpleName().contains("Component")) {
                            m.setAccessible(true);
                            cobwebCounter$labelMethod = m;
                            cobwebCounter$labelMethodResolved = true;
                            return m;
                        }
                    }
                }
            } catch (Throwable ignored) { }
            cobwebCounter$labelMethodResolved = true;
            return null;
        }
    }
}
