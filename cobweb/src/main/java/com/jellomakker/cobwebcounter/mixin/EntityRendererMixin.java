package com.jellomakker.cobwebcounter.mixin;

import com.jellomakker.cobwebcounter.CobwebCounterClient;
import com.jellomakker.cobwebcounter.config.CobwebCounterConfig;
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
 * Hooks into EntityRenderer.render() to draw the cobweb counter
 * above each player. Rendered at height + 0.9 to sit ABOVE the
 * golden apple counter (height + 0.6) when both mods are installed.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T, S extends EntityRenderState> {

    @Unique
    private static final ThreadLocal<Boolean> cobwebCounter$rendering =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Shadow
    protected abstract void renderLabelIfPresent(S state, MatrixStack matrices,
                                                  OrderedRenderCommandQueue queue, CameraRenderState cameraState);

    @Inject(method = "render", at = @At("RETURN"))
    private void cobwebCounter$afterRender(S state, MatrixStack matrices,
                                            OrderedRenderCommandQueue queue,
                                            CameraRenderState cameraState,
                                            CallbackInfo ci) {
        if (cobwebCounter$rendering.get()) return;

        if (!(state instanceof PlayerEntityRenderState playerState)) return;

        CobwebCounterConfig config = CobwebCounterConfig.get();
        if (!config.enabled || !config.showOnPlayerName) return;

        UUID uuid = CobwebCounterClient.getUuidFromEntityId(playerState.id);
        if (uuid == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.includeSelfDisplay && client.player != null
                && uuid.equals(client.player.getUuid())) {
            return;
        }

        int count = CobwebCounterClient.getCount(uuid);
        if (count <= 0) return;

        Vec3d originalNameLabelPos = state.nameLabelPos;
        Text originalDisplayName = state.displayName;
        boolean originalSneaking = state.sneaking;

        try {
            // Position at height + 0.9 (above golden apple counter's +0.6)
            state.nameLabelPos = new Vec3d(0, state.height + 0.9, 0);
            state.displayName = CobwebCounterClient.buildCounterText(count);

            if (!config.showBackground) {
                state.sneaking = true;
            }

            cobwebCounter$rendering.set(Boolean.TRUE);
            renderLabelIfPresent(state, matrices, queue, cameraState);
        } finally {
            cobwebCounter$rendering.set(Boolean.FALSE);
            state.nameLabelPos = originalNameLabelPos;
            state.displayName = originalDisplayName;
            state.sneaking = originalSneaking;
        }
    }
}
