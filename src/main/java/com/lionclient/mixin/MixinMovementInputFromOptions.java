package com.lionclient.mixin;

import com.lionclient.combat.ClientRotationHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.util.MovementInputFromOptions.class)
public abstract class MixinMovementInputFromOptions {
    @Inject(method = "updatePlayerMoveState", at = @At("RETURN"))
    private void lionclient$afterUpdatePlayerMoveState(CallbackInfo callbackInfo) {
        ClientRotationHelper.get().fixMovementInputs();
    }
}
