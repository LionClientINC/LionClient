package com.lionclient.mixin;

import com.lionclient.LionClient;
import com.lionclient.combat.ClientRotationHelper;
import com.lionclient.feature.module.impl.AntiFireballModule;
import com.lionclient.feature.module.impl.KillAuraModule;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.renderer.EntityRenderer.class)
public abstract class MixinEntityRenderer {
    @Inject(method = "getMouseOver", at = @At("HEAD"))
    private void lionclient$getMouseOverHead(float partialTicks, CallbackInfo callbackInfo) {
        ClientRotationHelper helper = ClientRotationHelper.get();
        if (helper.swappedForMouseOver) {
            return;
        }

        Entity viewEntity = Minecraft.getMinecraft().getRenderViewEntity();
        if (viewEntity == null || !helper.isActive()) {
            return;
        }

        Float yaw = helper.getServerYaw();
        Float pitch = helper.getServerPitch();
        if (yaw == null || pitch == null || yaw.isNaN() || pitch.isNaN()) {
            return;
        }

        helper.beginSwap(viewEntity, yaw.floatValue(), pitch.floatValue(), true);
        helper.swappedForMouseOver = true;
    }

    @Inject(method = "getMouseOver", at = @At(value = "INVOKE", target = "Lnet/minecraft/profiler/Profiler;endSection()V", shift = At.Shift.BEFORE))
    private void lionclient$overrideMouseOver(float partialTicks, CallbackInfo callbackInfo) {
        KillAuraModule killAura = getKillAura();
        if (killAura != null) {
            killAura.modifyMouseOverFromGetMouseOver(partialTicks);
        }

        AntiFireballModule antiFireball = getAntiFireball();
        if (antiFireball != null) {
            antiFireball.modifyMouseOverFromGetMouseOver(partialTicks);
        }
    }

    @Inject(method = "getMouseOver", at = @At("RETURN"))
    private void lionclient$getMouseOverReturn(float partialTicks, CallbackInfo callbackInfo) {
        ClientRotationHelper helper = ClientRotationHelper.get();
        if (!helper.swappedForMouseOver) {
            return;
        }

        Entity viewEntity = Minecraft.getMinecraft().getRenderViewEntity();
        if (viewEntity != null) {
            helper.endSwap(viewEntity);
        }
        helper.swappedForMouseOver = false;
    }

    private static KillAuraModule getKillAura() {
        LionClient client = LionClient.getInstance();
        return client == null ? null : client.getModuleManager().getModule(KillAuraModule.class);
    }

    private static AntiFireballModule getAntiFireball() {
        LionClient client = LionClient.getInstance();
        return client == null ? null : client.getModuleManager().getModule(AntiFireballModule.class);
    }
}
