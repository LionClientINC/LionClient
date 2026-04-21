package com.lionclient.mixin;

import com.lionclient.combat.ClientRotationHelper;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.entity.EntityPlayerSP.class)
public abstract class MixinEntityPlayerSP {
    @Inject(method = "onUpdateWalkingPlayer", at = @At("HEAD"))
    private void lionclient$beforeWalkingUpdate(CallbackInfo callbackInfo) {
        ClientRotationHelper helper = ClientRotationHelper.get();
        helper.updateServerRotations();
        helper.onWalkingUpdatePre((Entity) (Object) this);
    }

    @Inject(method = "onUpdateWalkingPlayer", at = @At("RETURN"))
    private void lionclient$afterWalkingUpdate(CallbackInfo callbackInfo) {
        ClientRotationHelper.get().onWalkingUpdatePost((Entity) (Object) this);
    }
}
