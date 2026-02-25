package com.jellomakker.goldenapplecounter.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Kept as a no-op so the mixin JSON entry doesn't fail.
 * All rendering is handled by EntityRendererMixin.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @ModifyReturnValue(method = "getDisplayName", at = @At("RETURN"))
    private Text goldenAppleCounter$appendCount(Text original) {
        return original;
    }
}
