package com.jellomakker.potcounter.mixin;

import com.jellomakker.potcounter.PotCounterClient;
import com.jellomakker.potcounter.config.PotCounterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Hooks into EntityRenderer.render() to draw the pot counter above each player.
 * Uses client.world.getEntityById(state.id) to resolve UUID — this is guaranteed
 * to succeed whenever the entity is being rendered (no timing dependency).
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T, S extends EntityRenderState> {

    @Inject(method = "render", at = @At("RETURN"))
    private void potCounter$afterRender(S state, MatrixStack matrices,
                                         VertexConsumerProvider vertexConsumers,
                                         int light,
                                         CallbackInfo ci) {
        if (!(state instanceof PlayerEntityRenderState playerState)) return;

        PotCounterConfig config = PotCounterConfig.get();
        if (!config.enabled) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        // Resolve entity by network ID — reliable since the entity is being rendered right now.
        Entity entity = client.world.getEntityById(playerState.id);
        if (!(entity instanceof PlayerEntity player)) return;
        UUID uuid = player.getUuid();

        if (!config.includeSelfDisplay && client.player != null
                && uuid.equals(client.player.getUuid())) {
            return;
        }

        int count = PotCounterClient.getCount(uuid);
        if (count <= 0) return;

        Text counterText = PotCounterClient.buildCounterText(count);

        // Keep position above head; use max() so it works even if height isn't set yet.
        double yOffset = Math.max(state.height, 1.8f) + 0.9;

        matrices.push();
        matrices.translate(0.0, yOffset, 0.0);
        matrices.multiply(client.gameRenderer.getCamera().getRotation());
        matrices.scale(-0.025f, -0.025f, 0.025f);

        TextRenderer textRenderer = client.textRenderer;
        OrderedText orderedText = counterText.asOrderedText();
        float textWidth = textRenderer.getWidth(orderedText);
        float x = -textWidth / 2.0f;
        Matrix4f positionMatrix = matrices.peek().getPositionMatrix();

        if (config.showBackground) {
            int bgColor = (int) (client.options.getTextBackgroundOpacity(0.25f) * 255.0f) << 24;
            textRenderer.draw(orderedText, x, 0, 0x20FFFFFF, false, positionMatrix,
                    vertexConsumers, TextRenderer.TextLayerType.SEE_THROUGH, bgColor, light);
        }

        textRenderer.draw(orderedText, x, 0, 0xFFFFFFFF, false, positionMatrix,
                vertexConsumers,
                config.showBackground ? TextRenderer.TextLayerType.SEE_THROUGH
                                      : TextRenderer.TextLayerType.NORMAL,
                0, light);

        matrices.pop();
    }
}
