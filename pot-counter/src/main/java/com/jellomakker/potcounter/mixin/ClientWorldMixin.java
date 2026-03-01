package com.jellomakker.potcounter.mixin;

import com.jellomakker.potcounter.PotCounterClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into ClientWorld.addEntity() to detect splash potions the instant
 * the server sends the spawn packet to the client. This is far more reliable
 * than tick-based scanning because splash potions are extremely short-lived
 * (1-3 ticks) and can despawn between tick callbacks.
 */
@Mixin(ClientWorld.class)
public class ClientWorldMixin {

    @Inject(method = "addEntity", at = @At("TAIL"))
    private void potCounter$onAddEntity(Entity entity, CallbackInfo ci) {
        try {
            if (entity instanceof PotionEntity potionEntity) {
                PotCounterClient.onPotionSpawned(potionEntity);
            }
        } catch (Throwable ignored) {
            // Cross-version safety
        }
    }
}
