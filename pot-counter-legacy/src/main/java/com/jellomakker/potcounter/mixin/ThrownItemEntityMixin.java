package com.jellomakker.potcounter.mixin;

import com.jellomakker.potcounter.PotCounterClient;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks ThrownItemEntity.setItem() which is called by the data tracker
 * when the server's tracker update packet is processed on the client.
 * This fires the EXACT moment the potion's item data becomes available,
 * making it far more reliable than polling in a tick loop.
 */
@Mixin(ThrownItemEntity.class)
public class ThrownItemEntityMixin {

    @Inject(method = "setItem", at = @At("RETURN"))
    private void onSetItem(ItemStack stack, CallbackInfo ci) {
        // Only care about PotionEntity (splash / lingering potions).
        // ThrownItemEntity is also the base of eggs, snowballs, etc.
        if (!((Object) this instanceof PotionEntity potionEntity)) return;
        PotCounterClient.onPotionItemSet(potionEntity);
    }
}
