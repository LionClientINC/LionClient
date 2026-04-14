package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.NumberSetting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSnowball;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

public final class TrajectoriesModule extends Module {
    private static final double SIMULATION_STEP = 0.1D;

    private final NumberSetting aimingRed = new NumberSetting("Aiming Red", 0, 255, 5, 85);
    private final NumberSetting aimingGreen = new NumberSetting("Aiming Green", 0, 255, 5, 255);
    private final NumberSetting aimingBlue = new NumberSetting("Aiming Blue", 0, 255, 5, 85);
    private final NumberSetting trajectoryRed = new NumberSetting("Trajectory Red", 0, 255, 5, 255);
    private final NumberSetting trajectoryGreen = new NumberSetting("Trajectory Green", 0, 255, 5, 255);
    private final NumberSetting trajectoryBlue = new NumberSetting("Trajectory Blue", 0, 255, 5, 255);
    private final NumberSetting targetRed = new NumberSetting("Target Red", 0, 255, 5, 255);
    private final NumberSetting targetGreen = new NumberSetting("Target Green", 0, 255, 5, 80);
    private final NumberSetting targetBlue = new NumberSetting("Target Blue", 0, 255, 5, 80);
    private final NumberSetting thickness = new NumberSetting("Thickness", 1, 6, 1, 2);

    public TrajectoriesModule() {
        super("Trajectories", "Predicts projectile flight paths and highlights entity hits.", Category.RENDER, Keyboard.KEY_NONE);
        addSetting(aimingRed);
        addSetting(aimingGreen);
        addSetting(aimingBlue);
        addSetting(trajectoryRed);
        addSetting(trajectoryGreen);
        addSetting(trajectoryBlue);
        addSetting(targetRed);
        addSetting(targetGreen);
        addSetting(targetBlue);
        addSetting(thickness);
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.thePlayer;
        if (player == null || minecraft.theWorld == null || player.getHeldItem() == null) {
            return;
        }

        ProjectileProperties properties = getProjectileProperties(player);
        if (properties == null) {
            return;
        }

        SimulationResult result = simulatePath(minecraft, player, properties, event.partialTicks);
        if (result.points.size() < 2) {
            return;
        }

        float[] lineColor = result.entityHit == null ? getTrajectoryColor() : getAimingColor();
        GL11.glPushMatrix();
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(thickness.getValue());

            renderPath(minecraft, result.points, lineColor);
        } finally {
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1.0F);
            GlStateManager.enableCull();
            GlStateManager.enableLighting();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glPopMatrix();
        }
    }

    private ProjectileProperties getProjectileProperties(EntityPlayerSP player) {
        Item item = player.getHeldItem().getItem();
        if (item instanceof ItemBow) {
            if (!player.isUsingItem()) {
                return null;
            }

            float power = (72000 - player.getItemInUseCount()) / 20.0F;
            power = (power * power + power * 2.0F) / 3.0F;
            if (power < 0.1F) {
                return null;
            }
            if (power > 1.0F) {
                power = 1.0F;
            }
            return new ProjectileProperties(3.0D * power, 0.05D, 0.99D, 0.16D, 0.0F);
        }

        if (item instanceof ItemFishingRod) {
            return new ProjectileProperties(1.5D, 0.04D, 0.92D, 0.16D, 0.0F);
        }

        if (item instanceof ItemPotion) {
            return new ProjectileProperties(0.5D, 0.05D, 0.95D, 0.16D, -20.0F);
        }

        if (item instanceof ItemSnowball || item instanceof ItemEgg || item instanceof ItemEnderPearl) {
            return new ProjectileProperties(1.5D, 0.03D, 0.99D, 0.16D, 0.0F);
        }

        return null;
    }

    private SimulationResult simulatePath(Minecraft minecraft, EntityPlayerSP player, ProjectileProperties properties, float partialTicks) {
        List<Vec3> points = new ArrayList<Vec3>();
        float pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
        float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch + properties.pitchOffset);
        Vec3 position = getStartPosition(player, yawRadians, partialTicks, properties);
        Vec3 motion = getInitialMotion(yawRadians, pitchRadians, properties.velocity);

        points.add(position);
        Entity hitEntity = null;
        Vec3 hitVec = null;

        for (int i = 0; i < 1000; i++) {
            Vec3 nextPosition = position.addVector(
                motion.xCoord * SIMULATION_STEP,
                motion.yCoord * SIMULATION_STEP,
                motion.zCoord * SIMULATION_STEP
            );
            MovingObjectPosition blockHit = minecraft.theWorld.rayTraceBlocks(position, nextPosition, false, true, false);
            EntityHitResult entityHit = findEntityHit(minecraft, player, position, nextPosition);

            if (entityHit != null && (blockHit == null || position.distanceTo(entityHit.hitVec) <= position.distanceTo(blockHit.hitVec))) {
                points.add(entityHit.hitVec);
                hitEntity = entityHit.entity;
                hitVec = entityHit.hitVec;
                break;
            }

            if (blockHit != null) {
                points.add(blockHit.hitVec);
                hitVec = blockHit.hitVec;
                break;
            }

            position = nextPosition;
            points.add(position);
            motion = new Vec3(
                motion.xCoord * getStepDrag(properties.drag),
                motion.yCoord * getStepDrag(properties.drag) - properties.gravity * SIMULATION_STEP,
                motion.zCoord * getStepDrag(properties.drag)
            );

            if (position.yCoord < 0.0D || position.yCoord > minecraft.theWorld.getActualHeight()) {
                break;
            }
        }

        return new SimulationResult(points, hitEntity, hitVec);
    }

    private Vec3 getStartPosition(EntityPlayerSP player, double yawRadians, float partialTicks, ProjectileProperties properties) {
        double interpX = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double interpY = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks + player.getEyeHeight();
        double interpZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        return new Vec3(
            interpX - Math.cos(yawRadians) * properties.startOffset,
            interpY - 0.1D,
            interpZ - Math.sin(yawRadians) * properties.startOffset
        );
    }

    private Vec3 getInitialMotion(double yawRadians, double pitchRadians, double velocity) {
        double motionX = -Math.sin(yawRadians) * Math.cos(pitchRadians);
        double motionY = -Math.sin(pitchRadians);
        double motionZ = Math.cos(yawRadians) * Math.cos(pitchRadians);
        double motionLength = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
        if (motionLength == 0.0D) {
            return new Vec3(0.0D, 0.0D, 0.0D);
        }

        return new Vec3(
            motionX / motionLength * velocity,
            motionY / motionLength * velocity,
            motionZ / motionLength * velocity
        );
    }

    private double getStepDrag(double drag) {
        return 1.0D - ((1.0D - drag) * SIMULATION_STEP);
    }

    private EntityHitResult findEntityHit(Minecraft minecraft, EntityPlayerSP player, Vec3 start, Vec3 end) {
        Entity bestEntity = null;
        Vec3 bestHitVec = null;
        double bestDistance = start.distanceTo(end);
        AxisAlignedBB searchBox = new AxisAlignedBB(
            Math.min(start.xCoord, end.xCoord),
            Math.min(start.yCoord, end.yCoord),
            Math.min(start.zCoord, end.zCoord),
            Math.max(start.xCoord, end.xCoord),
            Math.max(start.yCoord, end.yCoord),
            Math.max(start.zCoord, end.zCoord)
        ).expand(1.0D, 1.0D, 1.0D);

        for (Object object : minecraft.theWorld.getEntitiesWithinAABBExcludingEntity(player, searchBox)) {
            if (!(object instanceof Entity)) {
                continue;
            }

            Entity entity = (Entity) object;
            if (!(entity instanceof EntityLivingBase)
                || entity == player
                || entity instanceof EntityFishHook
                || entity instanceof EntityArmorStand
                || !entity.canBeCollidedWith()) {
                continue;
            }

            float border = Math.max(0.45F, entity.getCollisionBorderSize());
            AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
            if (box.isVecInside(start)) {
                return new EntityHitResult(entity, start);
            }
            if (box.isVecInside(end)) {
                return new EntityHitResult(entity, end);
            }
            MovingObjectPosition intercept = box.calculateIntercept(start, end);
            if (intercept == null) {
                continue;
            }

            double distance = start.distanceTo(intercept.hitVec);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestEntity = entity;
                bestHitVec = intercept.hitVec;
            }
        }

        return bestEntity == null ? null : new EntityHitResult(bestEntity, bestHitVec);
    }

    private void renderPath(Minecraft minecraft, List<Vec3> points, float[] color) {
        double viewerX = minecraft.getRenderManager().viewerPosX;
        double viewerY = minecraft.getRenderManager().viewerPosY;
        double viewerZ = minecraft.getRenderManager().viewerPosZ;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        renderer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (Vec3 point : points) {
            renderer.pos(point.xCoord - viewerX, point.yCoord - viewerY, point.zCoord - viewerZ)
                .color(color[0], color[1], color[2], 0.95F)
                .endVertex();
        }
        tessellator.draw();
    }

    private void vertex(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
    }

    private float[] getAimingColor() {
        return new float[] {
            aimingRed.getValue() / 255.0F,
            aimingGreen.getValue() / 255.0F,
            aimingBlue.getValue() / 255.0F
        };
    }

    private float[] getTrajectoryColor() {
        return new float[] {
            trajectoryRed.getValue() / 255.0F,
            trajectoryGreen.getValue() / 255.0F,
            trajectoryBlue.getValue() / 255.0F
        };
    }

    private static final class ProjectileProperties {
        private final double velocity;
        private final double gravity;
        private final double drag;
        private final double startOffset;
        private final float pitchOffset;

        private ProjectileProperties(double velocity, double gravity, double drag, double startOffset, float pitchOffset) {
            this.velocity = velocity;
            this.gravity = gravity;
            this.drag = drag;
            this.startOffset = startOffset;
            this.pitchOffset = pitchOffset;
        }
    }

    private static final class SimulationResult {
        private final List<Vec3> points;
        private final Entity entityHit;
        private final Vec3 hitVec;

        private SimulationResult(List<Vec3> points, Entity entityHit, Vec3 hitVec) {
            this.points = points;
            this.entityHit = entityHit;
            this.hitVec = hitVec;
        }
    }

    private static final class EntityHitResult {
        private final Entity entity;
        private final Vec3 hitVec;

        private EntityHitResult(Entity entity, Vec3 hitVec) {
            this.entity = entity;
            this.hitVec = hitVec;
        }
    }
}
