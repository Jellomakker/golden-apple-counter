package com.jellomakker.goldenapplecounter.mixin;

import com.jellomakker.goldenapplecounter.GoldenAppleCounterClient;
import com.jellomakker.goldenapplecounter.config.GoldenAppleCounterConfig;
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
    private static final ThreadLocal<Boolean> goldenAppleCounter$rendering = ThreadLocal.withInitial(() -> false);

    /** Cached reflective reference to the label-rendering method (version-dependent name). */
    @Unique
    private static volatile Method goldenAppleCounter$labelMethod;
    @Unique
    private static volatile boolean goldenAppleCounter$labelMethodResolved;

    /**
     * Render the golden apple counter above players.
     * Works on MC 1.21.2+ which all use EntityRenderState.
     */
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void goldenAppleCounter$afterRender(S state, MatrixStack matrices,
                                                 VertexConsumerProvider vertexConsumers, int light,
                                                 CallbackInfo ci) {
        if (goldenAppleCounter$rendering.get()) return;
        if (!(state instanceof PlayerEntityRenderState playerState)) return;

        GoldenAppleCounterConfig config = GoldenAppleCounterConfig.get();
        if (!config.enabled || !config.showOnPlayerName) return;

        try {
            goldenAppleCounter$renderLabel(state, playerState, config, matrices, vertexConsumers, light);
        } catch (Throwable ignored) {
            // Gracefully handle field/method changes across MC versions
        }
    }

    @Unique
    private void goldenAppleCounter$renderLabel(S state, PlayerEntityRenderState playerState,
                                                 GoldenAppleCounterConfig config,
                                                 MatrixStack matrices,
                                                 VertexConsumerProvider vertexConsumers, int light) {
        UUID uuid = GoldenAppleCounterClient.getUuidFromEntityId(playerState.id);
        if (uuid == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.includeSelfDisplay && client.player != null
                && uuid.equals(client.player.getUuid())) {
            return;
        }

        int count = GoldenAppleCounterClient.getCount(uuid);
        if (count <= 0) return;

        Vec3d originalPos = state.nameLabelPos;

        // Place the counter well above the player — high enough to clear
        // both the vanilla nametag and any server-rendered custom name.
        state.nameLabelPos = new Vec3d(0, state.height + 0.6, 0);

        Text label = GoldenAppleCounterClient.buildCounterText(count);

        boolean wasSneaking = state.sneaking;
        if (!config.showBackground) {
            state.sneaking = true;
        }

        goldenAppleCounter$rendering.set(true);
        try {
            goldenAppleCounter$invokeLabelMethod(state, label, matrices, vertexConsumers, light);
        } finally {
            goldenAppleCounter$rendering.set(false);
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
    private void goldenAppleCounter$invokeLabelMethod(S state, Text text, MatrixStack matrices,
                                                       VertexConsumerProvider vertexConsumers, int light) {
        try {
            Method m = goldenAppleCounter$resolveLabelMethod();
            if (m != null) {
                m.invoke(this, state, text, matrices, vertexConsumers, light);
            }
        } catch (Throwable ignored) { }
    }

    @Unique
    private static Method goldenAppleCounter$resolveLabelMethod() {
        if (goldenAppleCounter$labelMethodResolved) return goldenAppleCounter$labelMethod;
        synchronized (EntityRendererMixin.class) {
            if (goldenAppleCounter$labelMethodResolved) return goldenAppleCounter$labelMethod;
            try {
                // Try each known method name
                for (String name : new String[]{"renderLabelIfPresent", "submitNameTag"}) {
                    for (Method m : EntityRenderer.class.getDeclaredMethods()) {
                        if (m.getName().equals(name) && m.getParameterCount() == 5) {
                            m.setAccessible(true);
                            goldenAppleCounter$labelMethod = m;
                            goldenAppleCounter$labelMethodResolved = true;
                            return m;
                        }
                    }
                }
                // Fallback: find any method with 5 params where second is Text/Component
                for (Method m : EntityRenderer.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 5) {
                        Class<?>[] p = m.getParameterTypes();
                        if (Text.class.isAssignableFrom(p[1]) || p[1].getSimpleName().contains("Component")) {
                            m.setAccessible(true);
                            goldenAppleCounter$labelMethod = m;
                            goldenAppleCounter$labelMethodResolved = true;
                            return m;
                        }
                    }
                }
            } catch (Throwable ignored) { }
            goldenAppleCounter$labelMethodResolved = true;
            return null;
        }
    }
}
