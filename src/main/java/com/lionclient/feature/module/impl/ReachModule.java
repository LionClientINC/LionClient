package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import org.lwjgl.input.Keyboard;

public final class ReachModule extends Module {
    private static final double VANILLA_REACH = 3.0D;

    private final DecimalSetting reach = new DecimalSetting("Reach", VANILLA_REACH, 6.0D, 0.1D, VANILLA_REACH);
    private final NumberSetting chance = new NumberSetting("Chance", 0, 100, 1, 100);

    public ReachModule() {
        super("Reach", "Extends left-click range with configurable chance.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(reach);
        addSetting(chance);
    }

    @Override
    public void onMouseEvent(MouseEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (event.button != 0 || !event.buttonstate) {
            return;
        }

        if (minecraft.thePlayer == null || minecraft.theWorld == null || minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            return;
        }

        double configuredReach = Math.max(VANILLA_REACH, reach.getValue());
        if (configuredReach <= VANILLA_REACH) {
            return;
        }

        if (Math.random() * 100.0D >= chance.getValue()) {
            return;
        }

        Entity target = rayTraceEntity(minecraft, configuredReach);
        if (!(target instanceof EntityLivingBase) || target == minecraft.thePlayer) {
            return;
        }

        double distance = minecraft.thePlayer.getDistanceToEntity(target);
        if (distance <= VANILLA_REACH || distance > configuredReach) {
            return;
        }

        minecraft.playerController.attackEntity(minecraft.thePlayer, target);
        minecraft.thePlayer.swingItem();
        event.setCanceled(true);
    }

    private Entity rayTraceEntity(Minecraft minecraft, double reachDistance) {
        EntityPlayer player = minecraft.thePlayer;
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 look = player.getLook(1.0F);
        Vec3 reachVector = eyes.addVector(look.xCoord * reachDistance, look.yCoord * reachDistance, look.zCoord * reachDistance);
        Entity pointedEntity = null;
        double bestDistance = reachDistance;
        List<?> entities = minecraft.theWorld.getEntitiesWithinAABBExcludingEntity(
            player,
            player.getEntityBoundingBox()
                .addCoord(look.xCoord * reachDistance, look.yCoord * reachDistance, look.zCoord * reachDistance)
                .expand(1.0D, 1.0D, 1.0D)
        );

        for (Object object : entities) {
            if (!(object instanceof Entity)) {
                continue;
            }

            Entity entity = (Entity) object;
            if (!entity.canBeCollidedWith() || entity == player) {
                continue;
            }

            float border = entity.getCollisionBorderSize();
            AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
            MovingObjectPosition intercept = box.calculateIntercept(eyes, reachVector);

            if (box.isVecInside(eyes)) {
                if (bestDistance >= 0.0D) {
                    pointedEntity = entity;
                    bestDistance = 0.0D;
                }
                continue;
            }

            if (intercept == null) {
                continue;
            }

            double distance = eyes.distanceTo(intercept.hitVec);
            if (distance < bestDistance || bestDistance == 0.0D) {
                pointedEntity = entity;
                bestDistance = distance;
            }
        }

        return pointedEntity;
    }
}
