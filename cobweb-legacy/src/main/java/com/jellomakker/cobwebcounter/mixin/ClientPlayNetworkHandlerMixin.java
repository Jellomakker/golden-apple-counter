package com.jellomakker.cobwebcounter.mixin;

import com.jellomakker.cobwebcounter.CobwebCounterClient;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts block-update packets to detect cobweb placements in the world.
 * This is more reliable than watching a player's held item because fast
 * hotbar switches can happen between server equipment-sync ticks, meaning
 * the client never "sees" the cobweb in the other player's hand.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    /** Single-block update (most common for player block placements). */
    @Inject(method = "onBlockUpdate", at = @At("TAIL"), require = 0)
    private void cobwebCounter$onBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        try {
            if (packet.getState().getBlock() == Blocks.COBWEB) {
                CobwebCounterClient.attributeCobwebPlacement(packet.getPos());
            }
        } catch (Throwable ignored) {
            // Graceful cross-version safety
        }
    }

    /** Multi-block / chunk-section delta update. */
    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"), require = 0)
    private void cobwebCounter$onChunkDeltaUpdate(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        try {
            packet.visitUpdates((pos, state) -> {
                if (state.getBlock() == Blocks.COBWEB) {
                    CobwebCounterClient.attributeCobwebPlacement(pos.toImmutable());
                }
            });
        } catch (Throwable ignored) {
            // Graceful cross-version safety
        }
    }
}
