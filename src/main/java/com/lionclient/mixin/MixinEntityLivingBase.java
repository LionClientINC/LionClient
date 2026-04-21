package com.lionclient.mixin;

import com.lionclient.event.JumpEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBase extends Entity {
    public MixinEntityLivingBase(World worldIn) {
        super(worldIn);
    }

    @Shadow
    protected abstract float getJumpUpwardsMotion();

    @Shadow
    public abstract boolean isPotionActive(Potion potionIn);

    @Shadow
    public abstract PotionEffect getActivePotionEffect(Potion potionIn);

    /**
     * @author Codex
     * @reason Mirrors Raven's jump hook so sprint-jump yaw can follow silent rotations.
     */
    @Overwrite
    protected void jump() {
        JumpEvent jumpEvent = new JumpEvent(getJumpUpwardsMotion(), rotationYaw, isSprinting());
        MinecraftForge.EVENT_BUS.post(jumpEvent);
        if (jumpEvent.isCanceled()) {
            return;
        }

        motionY = jumpEvent.getMotionY();
        if (isPotionActive(Potion.jump)) {
            motionY += (float) (getActivePotionEffect(Potion.jump).getAmplifier() + 1) * 0.1F;
        }

        if (jumpEvent.applySprint()) {
            float yaw = jumpEvent.getYaw() * 0.017453292F;
            motionX -= MathHelper.sin(yaw) * 0.2F;
            motionZ += MathHelper.cos(yaw) * 0.2F;
        }

        isAirBorne = true;
        ForgeHooks.onLivingJump((EntityLivingBase) (Object) this);
    }
}
