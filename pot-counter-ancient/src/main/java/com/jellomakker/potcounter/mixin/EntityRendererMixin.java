package com.jellomakker.potcounter.mixin;

import com.jellomakker.potcounter.PotCounterClient;
import com.jellomakker.potcounter.config.PotCounterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
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
 * Hooks into EntityRenderer.render() to draw the pot counter above each player's nametag.
 * Reuses vanilla renderLabelIfPresent() by temporarily overriding state.nameLabelPos.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T, S extends EntityRenderState> {

    @Unique
    private static final ThreadLocal<Boolean> potCounter$rendering =
            ThreadLocal.withInitial(() -> false);

    @Shadow
    protected abstract void renderLabelIfPresent(S state, Text label,
            MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light);

    @Inject(method = "render", at = @At("RETURN"))
    private void potCounter$afterRender(S state, MatrixStack matrices,
                                         VertexConsumerProvider vertexConsumers,
                                         int light,
                                         CallbackInfo ci) {
        if (potCounter$rendering.get()) return;
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

        // Save state fields, reposition label above the player's nametag, then render
        Vec3d originalPos = state.nameLabelPos;
        boolean wasSneaking = state.sneaking;

        state.nameLabelPos = new Vec3d(0, state.height + 0.9, 0);
        if (!config.showBackground) {
            // Setting sneaking=true makes vanilla skip the background quad
            state.sneaking = true;
        }

        potCounter$rendering.set(true);
        try {
            this.renderLabelIfPresent(state, counterText, matrices, vertexConsumers, light);
        } finally {
            potCounter$rendering.set(false);
            state.nameLabelPos = originalPos;
            state.sneaking = wasSneaking;
        }
    }
}
