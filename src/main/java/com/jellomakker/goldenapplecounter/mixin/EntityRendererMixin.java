package com.jellomakker.goldenapplecounter.mixin;

import com.jellomakker.goldenapplecounter.GoldenAppleCounterClient;
import com.jellomakker.goldenapplecounter.config.GoldenAppleCounterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Hooks into EntityRenderer.render() to draw the golden apple counter
 * above each player. Reuses the engine's own renderLabelIfPresent() so
 * the text is rendered through the standard pipeline.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T, S extends EntityRenderState> {

    /**
     * Prevents infinite recursion: renderLabelIfPresent is called from
     * within our injected code, and the inject point is the same render method.
     */
    @Unique
    private static final ThreadLocal<Boolean> goldenAppleCounter$rendering =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Shadow
    protected abstract void renderLabelIfPresent(S state, MatrixStack matrices,
                                                  OrderedRenderCommandQueue queue, CameraRenderState cameraState);

    @Inject(method = "render", at = @At("RETURN"))
    private void goldenAppleCounter$afterRender(S state, MatrixStack matrices,
                                                 OrderedRenderCommandQueue queue,
                                                 CameraRenderState cameraState,
                                                 CallbackInfo ci) {
        // Prevent recursion
        if (goldenAppleCounter$rendering.get()) return;

        // Only process player entities
        if (!(state instanceof PlayerEntityRenderState playerState)) return;

        GoldenAppleCounterConfig config = GoldenAppleCounterConfig.get();
        if (!config.enabled || !config.showOnPlayerName) return;

        // Look up UUID from entity network id
        UUID uuid = GoldenAppleCounterClient.getUuidFromEntityId(playerState.id);
        if (uuid == null) return;

        // Skip self if configured
        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.includeSelfDisplay && client.player != null
                && uuid.equals(client.player.getUuid())) {
            return;
        }

        int count = GoldenAppleCounterClient.getCount(uuid);
        if (count <= 0) return;

        // Save original state
        Vec3d originalNameLabelPos = state.nameLabelPos;
        Text originalDisplayName = state.displayName;
        boolean originalSneaking = state.sneaking;

        try {
            // Position label above the player's head (height + 0.6)
            state.nameLabelPos = new Vec3d(0, state.height + 0.6, 0);
            state.displayName = GoldenAppleCounterClient.buildCounterText(count);

            // If no background desired, set sneaking=true (engine skips bg for sneaking)
            if (!config.showBackground) {
                state.sneaking = true;
            }

            goldenAppleCounter$rendering.set(Boolean.TRUE);
            renderLabelIfPresent(state, matrices, queue, cameraState);
        } finally {
            goldenAppleCounter$rendering.set(Boolean.FALSE);
            // Restore original state
            state.nameLabelPos = originalNameLabelPos;
            state.displayName = originalDisplayName;
            state.sneaking = originalSneaking;
        }
    }
}
