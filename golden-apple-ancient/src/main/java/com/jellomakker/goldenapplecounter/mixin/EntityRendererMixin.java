package com.jellomakker.goldenapplecounter.mixin;

import com.jellomakker.goldenapplecounter.GoldenAppleCounterClient;
import com.jellomakker.goldenapplecounter.config.GoldenAppleCounterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21-1.21.4: Entity-based render method (no EntityRenderState).
 * Targets LivingEntityRenderer.render() to catch player entities.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("RETURN"))
    private void goldenAppleCounter$afterRender(LivingEntity entity, float yaw, float tickDelta,
                                                 MatrixStack matrices,
                                                 VertexConsumerProvider vertexConsumers,
                                                 int light, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity player)) return;

        GoldenAppleCounterConfig config = GoldenAppleCounterConfig.get();
        if (!config.enabled || !config.showOnPlayerName) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.includeSelfDisplay && client.player != null
                && player.getUuid().equals(client.player.getUuid())) {
            return;
        }

        int count = GoldenAppleCounterClient.getCount(player.getUuid());
        if (count <= 0) return;

        Text counterText = GoldenAppleCounterClient.buildCounterText(count);
        double yOffset = player.getHeight() + 0.6;

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
            textRenderer.draw(
                    orderedText, x, 0, 0x20FFFFFF, false,
                    positionMatrix, vertexConsumers,
                    TextRenderer.TextLayerType.SEE_THROUGH,
                    bgColor, light
            );
        }

        textRenderer.draw(
                orderedText, x, 0, 0xFFFFFFFF, false,
                positionMatrix, vertexConsumers,
                config.showBackground ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL,
                0, light
        );

        matrices.pop();
    }
}
