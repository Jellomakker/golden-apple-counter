package com.jellomakker.potcounter.mixin;

import com.jellomakker.potcounter.PotCounterClient;
import com.jellomakker.potcounter.config.PotCounterConfig;
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
 * Hooks into EntityRenderer.render() to draw the pot counter above each player.
 * Rendered at height + 0.9 to sit at the same height as the cobweb counter.
 * Calls queue.submitLabel() directly to bypass server name visibility.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T, S extends EntityRenderState> {

    @Inject(method = "render", at = @At("RETURN"))
    private void potCounter$afterRender(S state, MatrixStack matrices,
                                         OrderedRenderCommandQueue queue,
                                         CameraRenderState cameraState,
                                         CallbackInfo ci) {
        if (!(state instanceof PlayerEntityRenderState playerState)) return;

        PotCounterConfig config = PotCounterConfig.get();
        if (!config.enabled || !config.showOnPlayerName) return;

        UUID uuid = PotCounterClient.getUuidFromEntityId(playerState.id);
        if (uuid == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.includeSelfDisplay && client.player != null
                && uuid.equals(client.player.getUuid())) {
            return;
        }

        int count = PotCounterClient.getCount(uuid);
        if (count <= 0) return;

        Text counterText = PotCounterClient.buildCounterText(count);
        Vec3d labelPos = new Vec3d(0, state.height + 0.9, 0);

        matrices.push();
        queue.submitLabel(
                matrices,
                labelPos,
                0,
                counterText,
                config.showBackground,
                state.light,
                state.squaredDistanceToCamera,
                cameraState
        );
        matrices.pop();
    }
}
