package com.jellomakker.cobwebcounter.mixin;

import com.jellomakker.cobwebcounter.CobwebCounterClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into {@link ClientWorld#handleBlockUpdate} to detect cobweb blocks
 * being placed in the world.  This fires for EVERY block update the server
 * sends, regardless of whether it was a single-block packet or a chunk-delta
 * packet, so it is the most reliable interception point.
 */
@Mixin(ClientWorld.class)
public class ClientWorldMixin {

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"), require = 0)
    private void cobwebCounter$onHandleBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        try {
            if (state.getBlock() == Blocks.COBWEB) {
                CobwebCounterClient.attributeCobwebPlacement(pos);
            }
        } catch (Throwable ignored) {
            // Cross-version safety
        }
    }
}
