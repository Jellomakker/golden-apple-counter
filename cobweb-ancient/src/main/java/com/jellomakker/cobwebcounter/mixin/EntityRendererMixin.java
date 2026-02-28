package com.jellomakker.cobwebcounter.mixin;

import com.jellomakker.cobwebcounter.CobwebCounterClient;
import com.jellomakker.cobwebcounter.config.CobwebCounterConfig;
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

@Mixin(LivingEntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("RETURN"))
    private void cobwebCounter$afterRender(LivingEntity entity, float yaw, float tickDelta,
                                            MatrixStack matrices,
                                            VertexConsumerProvider vertexConsumers,
                                            int light, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity player)) return;

        CobwebCounterConfig config = CobwebCounterConfig.get();
        if (!config.enabled || !config.showOnPlayerName) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.includeSelfDisplay && client.player != null
                && player.getUuid().equals(client.player.getUuid())) {
            return;
        }

        int count = CobwebCounterClient.getCount(player.getUuid());
        if (count <= 0) return;

        Text counterText = CobwebCounterClient.buildCounterText(count);
        double yOffset = player.getHeight() + 0.9;

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
            textRenderer.draw(orderedText, x, 0, 0x20FFFFFF, false,
                    positionMatrix, vertexConsumers,
                    TextRenderer.TextLayerType.SEE_THROUGH, bgColor, light);
        }

        textRenderer.draw(orderedText, x, 0, 0xFFFFFFFF, false,
                positionMatrix, vertexConsumers,
                config.showBackground ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL,
                0, light);

        matrices.pop();
    }
}
