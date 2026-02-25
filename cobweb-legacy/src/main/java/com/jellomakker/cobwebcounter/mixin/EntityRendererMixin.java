package com.jellomakker.cobwebcounter.mixin;

import com.jellomakker.cobwebcounter.CobwebCounterClient;
import com.jellomakker.cobwebcounter.config.CobwebCounterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Hooks into EntityRenderer.render() to draw the cobweb counter above each player.
 * Renders text manually using TextRenderer for 1.21.5-1.21.10 compatibility.
 * Rendered at height + 0.9 to sit ABOVE the golden apple counter when both mods are installed.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T, S extends EntityRenderState> {

    @Shadow
    public abstract TextRenderer getTextRenderer();

    @Inject(method = "render", at = @At("RETURN"))
    private void cobwebCounter$afterRender(S state, MatrixStack matrices,
                                            VertexConsumerProvider vertexConsumers,
                                            int light,
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

        // Position at height + 0.9 (above golden apple counter's +0.6)
        double yOffset = state.height + 0.9;

        matrices.push();

        // Translate to position above the player
        if (state.nameLabelPos != null) {
            matrices.translate(state.nameLabelPos.x, yOffset, state.nameLabelPos.z);
        } else {
            matrices.translate(0.0, yOffset, 0.0);
        }

        // Billboard: face the camera
        matrices.multiply(client.gameRenderer.getCamera().getRotation());
        matrices.scale(-0.025f, -0.025f, 0.025f);

        TextRenderer textRenderer = this.getTextRenderer();
        OrderedText orderedText = counterText.asOrderedText();
        float textWidth = textRenderer.getWidth(orderedText);
        float x = -textWidth / 2.0f;

        Matrix4f positionMatrix = matrices.peek().getPositionMatrix();

        // Background
        if (config.showBackground) {
            int bgColor = (int) (client.options.getTextBackgroundOpacity(0.25f) * 255.0f) << 24;
            textRenderer.draw(
                    orderedText,
                    x, 0,
                    0x20FFFFFF,
                    false,
                    positionMatrix,
                    vertexConsumers,
                    TextRenderer.TextLayerType.SEE_THROUGH,
                    bgColor,
                    light
            );
        }

        // Foreground text
        textRenderer.draw(
                orderedText,
                x, 0,
                0xFFFFFFFF,
                false,
                positionMatrix,
                vertexConsumers,
                config.showBackground ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL,
                0,
                light
        );

        matrices.pop();
    }
}
