package com.lionclient.mixin;

import com.lionclient.event.StrafeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(net.minecraft.entity.Entity.class)
public abstract class MixinEntity {
    @Shadow
    public double motionX;

    @Shadow
    public double motionZ;

    @Shadow
    public float rotationYaw;

    /**
     * @author Codex
     * @reason Mirrors Raven's moveFlying hook so strafe math can use silent yaw.
     */
    @Overwrite
    public void moveFlying(float strafe, float forward, float friction) {
        StrafeEvent strafeEvent = new StrafeEvent(strafe, forward, friction, rotationYaw);
        if ((Object) this == Minecraft.getMinecraft().thePlayer) {
            MinecraftForge.EVENT_BUS.post(strafeEvent);
        }

        strafe = strafeEvent.getStrafe();
        forward = strafeEvent.getForward();
        friction = strafeEvent.getFriction();
        float yaw = strafeEvent.getYaw();
        float magnitude = strafe * strafe + forward * forward;

        if (magnitude >= 1.0E-4F) {
            magnitude = MathHelper.sqrt_float(magnitude);
            if (magnitude < 1.0F) {
                magnitude = 1.0F;
            }

            magnitude = friction / magnitude;
            strafe *= magnitude;
            forward *= magnitude;
            float sin = MathHelper.sin(yaw * (float) Math.PI / 180.0F);
            float cos = MathHelper.cos(yaw * (float) Math.PI / 180.0F);
            motionX += strafe * cos - forward * sin;
            motionZ += forward * cos + strafe * sin;
        }
    }
}
