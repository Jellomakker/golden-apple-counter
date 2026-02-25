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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Hooks into EntityRenderer.render() to draw the cobweb counter
 * above each player. Rendered at height + 0.9 to sit ABOVE the
 * golden apple counter (height + 0.6) when both mods are installed.
 * Calls queue.submitLabel() directly to bypass server name visibility.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T, S extends EntityRenderState> {

    @Inject(method = "render", at = @At("RETURN"))
    private void cobwebCounter$afterRender(S state, MatrixStack matrices,
                                            OrderedRenderCommandQueue queue,
                                            CameraRenderState cameraState,
                                            CallbackInfo ci) {
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

        Text counterText = CobwebCounterClient.buildCounterText(count);
        Vec3d labelPos = new Vec3d(0, state.height + 0.9, 0);

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
