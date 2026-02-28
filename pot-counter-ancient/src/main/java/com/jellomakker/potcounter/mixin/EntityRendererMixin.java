package com.jellomakker.potcounter.mixin;

import com.jellomakker.potcounter.PotCounterClient;
import com.jellomakker.potcounter.config.PotCounterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"))
    private void onRender(LivingEntity entity, float yaw, float tickDelta,
                          MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                          int light, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity player)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        PotCounterConfig config = PotCounterConfig.get();
        if (!config.enabled) return;

        if (!config.includeSelfDisplay && client.player != null
                && player.getUuid().equals(client.player.getUuid())) return;

        int count = PotCounterClient.getCount(player.getUuid());
        if (count <= 0) return;

        Text text = PotCounterClient.buildCounterText(count);
        TextRenderer textRenderer = client.textRenderer;
        float textWidth = textRenderer.getWidth(text);

        matrices.push();
        matrices.translate(0.0, player.getHeight() + 0.9, 0.0);

        if (client.getCameraEntity() != null) {
            matrices.multiply(client.gameRenderer.getCamera().getRotation());
        }

        float scale = -0.025f;
        matrices.scale(scale, scale, scale);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float bgOpacity = MinecraftClient.getInstance().options.getTextBackgroundOpacity(0.25f);
        int bgColor = (int) (bgOpacity * 255.0f) << 24;

        textRenderer.draw(text, -textWidth / 2.0f, 0.0f, 0xFFFFFF,
                false, matrix, vertexConsumers, TextRenderer.TextLayerType.SEE_THROUGH, bgColor, light);
        textRenderer.draw(text, -textWidth / 2.0f, 0.0f, 0xFFFFFF,
                false, matrix, vertexConsumers, TextRenderer.TextLayerType.NORMAL, 0, light);

        matrices.pop();
    }
}
