package com.jellomakker.goldenapplecounter.mixin;

import com.jellomakker.goldenapplecounter.GoldenAppleCounterClient;
import com.jellomakker.goldenapplecounter.config.GoldenAppleCounterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T, S extends EntityRenderState> {

    @Shadow
    public abstract TextRenderer getTextRenderer();

    @Inject(method = "render", at = @At("RETURN"))
    private void goldenAppleCounter$afterRender(S state, MatrixStack matrices,
                                                 VertexConsumerProvider vertexConsumers,
                                                 int light,
                                                 CallbackInfo ci) {
        if (!(state instanceof PlayerEntityRenderState playerState)) return;

        GoldenAppleCounterConfig config = GoldenAppleCounterConfig.get();
        if (!config.enabled || !config.showOnPlayerName) return;

        UUID uuid = GoldenAppleCounterClient.getUuidFromEntityId(playerState.id);
        if (uuid == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.includeSelfDisplay && client.player != null
                && uuid.equals(client.player.getUuid())) {
            return;
        }

        int count = GoldenAppleCounterClient.getCount(uuid);
        if (count <= 0) return;

        Text counterText = GoldenAppleCounterClient.buildCounterText(count);
        double yOffset = state.height + 0.6;

        matrices.push();

        if (state.nameLabelPos != null) {
            matrices.translate(state.nameLabelPos.x, yOffset, state.nameLabelPos.z);
        } else {
            matrices.translate(0.0, yOffset, 0.0);
        }

        matrices.multiply(client.gameRenderer.getCamera().getRotation());
        matrices.scale(-0.025f, -0.025f, 0.025f);

        TextRenderer textRenderer = this.getTextRenderer();
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
