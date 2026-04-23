package com.lionclient.mixin;

import com.lionclient.combat.ClientRotationHelper;
import com.lionclient.event.PrePlayerInputEvent;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MovementInput;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(net.minecraft.util.MovementInputFromOptions.class)
public abstract class MixinMovementInputFromOptions extends MovementInput {
    @Shadow
    @Final
    private GameSettings gameSettings;

    /**
     * @author Codex
     * @reason Adds a pre-input hook for modules like AntiFireball, then reapplies silent-rotation movement fixing.
     */
    @Overwrite
    public void updatePlayerMoveState() {
        this.moveStrafe = 0.0F;
        this.moveForward = 0.0F;

        if (this.gameSettings.keyBindForward.isKeyDown()) {
            ++this.moveForward;
        }
        if (this.gameSettings.keyBindBack.isKeyDown()) {
            --this.moveForward;
        }
        if (this.gameSettings.keyBindLeft.isKeyDown()) {
            ++this.moveStrafe;
        }
        if (this.gameSettings.keyBindRight.isKeyDown()) {
            --this.moveStrafe;
        }

        this.jump = this.gameSettings.keyBindJump.isKeyDown();
        this.sneak = this.gameSettings.keyBindSneak.isKeyDown();

        PrePlayerInputEvent event = new PrePlayerInputEvent(this.moveForward, this.moveStrafe, this.jump, this.sneak);
        MinecraftForge.EVENT_BUS.post(event);

        this.moveForward = event.getForward();
        this.moveStrafe = event.getStrafe();
        this.jump = event.isJump();
        this.sneak = event.isSneak();

        if (this.sneak) {
            this.moveStrafe *= 0.3F;
            this.moveForward *= 0.3F;
        }

        ClientRotationHelper.get().fixMovementInputs();
    }
}
