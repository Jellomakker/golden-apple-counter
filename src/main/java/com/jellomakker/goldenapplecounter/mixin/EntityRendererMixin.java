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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Hooks into EntityRenderer.render() to draw the golden apple counter
 * above each player. Calls queue.submitLabel() directly to bypass
 * PlayerEntityRenderer's renderLabelIfPresent override, which may be
 * affected by server-side name visibility / team settings.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T, S extends EntityRenderState> {

    @Inject(method = "render", at = @At("RETURN"))
    private void goldenAppleCounter$afterRender(S state, MatrixStack matrices,
                                                 OrderedRenderCommandQueue queue,
                                                 CameraRenderState cameraState,
                                                 CallbackInfo ci) {
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

        Text counterText = GoldenAppleCounterClient.buildCounterText(count);
        Vec3d labelPos = new Vec3d(0, state.height + 0.6, 0);

        // Call submitLabel directly — bypasses PlayerEntityRenderer's override
        // which may honour server team visibility settings that hide nametags.
        matrices.push();
        queue.submitLabel(
                matrices,
                labelPos,
                0,                              // y pixel offset
                counterText,
                config.showBackground,          // show background rectangle
                state.light,
                state.squaredDistanceToCamera,
                cameraState
        );
        matrices.pop();
    }
}
