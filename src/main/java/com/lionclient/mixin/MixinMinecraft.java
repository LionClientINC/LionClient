package com.lionclient.mixin;

import com.lionclient.combat.ClientRotationHelper;
import com.lionclient.event.PrePlayerInteractEvent;
import net.minecraftforge.common.MinecraftForge;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.Minecraft.class)
public abstract class MixinMinecraft {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void lionclient$onRunTickStart(CallbackInfo callbackInfo) {
        ClientRotationHelper.get().onRunTickStart();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;getMouseOver(F)V", shift = At.Shift.BEFORE))
    private void lionclient$beforeGetMouseOver(CallbackInfo callbackInfo) {
        ClientRotationHelper.get().updateServerRotations();
    }

    @Inject(method = "runTick", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/client/settings/GameSettings;chatVisibility:Lnet/minecraft/entity/player/EntityPlayer$EnumChatVisibility;"))
    private void lionclient$beforePlayerInteract(CallbackInfo callbackInfo) {
        MinecraftForge.EVENT_BUS.post(new PrePlayerInteractEvent());
    }
}
