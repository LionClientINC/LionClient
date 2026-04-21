package com.lionclient.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

public final class KillAuraRotationUtils {
    private static final Minecraft MINECRAFT = Minecraft.getMinecraft();
    private static final double BACKUP_FACE_INSET = 0.05D;
    private static final int BACKUP_TARGET_TOTAL = 30;
    private static final float FAR_THRESHOLD = 180.0F;

    public static final float[] serverRotations = new float[]{Float.NaN, Float.NaN};

    private KillAuraRotationUtils() {
    }

    public static float clampPitch(float pitch) {
        return MathHelper.clamp_float(pitch, -90.0F, 90.0F);
    }

    public static Vec3 getVectorForRotation(float pitch, float yaw) {
        float yawCos = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float yawSin = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float pitchCos = -MathHelper.cos(-pitch * 0.017453292F);
        float pitchSin = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(yawSin * pitchCos, pitchSin, yawCos * pitchCos);
    }

    public static float[] getRotationsToPoint(double x, double y, double z, float baseYaw, float basePitch) {
        if (MINECRAFT.thePlayer == null) {
            return null;
        }

        double deltaX = x - MINECRAFT.thePlayer.posX;
        double deltaZ = z - MINECRAFT.thePlayer.posZ;
        double deltaY = y - (MINECRAFT.thePlayer.posY + MINECRAFT.thePlayer.getEyeHeight());
        double horizontalDistanceSq = deltaX * deltaX + deltaZ * deltaZ;

        float yaw;
        float targetPitch;
        if (horizontalDistanceSq < 1.0E-12D) {
            yaw = baseYaw;
            targetPitch = (float) (-(Math.atan2(deltaY, 0.0D) * 57.295780181884766D));
        } else {
            float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * 57.295780181884766D) - 90.0F;
            yaw = baseYaw + MathHelper.wrapAngleTo180_float(targetYaw - baseYaw);
            double horizontalDistance = MathHelper.sqrt_double(horizontalDistanceSq);
            targetPitch = (float) (-(Math.atan2(deltaY, horizontalDistance) * 57.295780181884766D));
        }

        float pitch = basePitch + MathHelper.wrapAngleTo180_float(targetPitch - basePitch) + 3.0F;
        return new float[]{yaw, clampPitch(pitch)};
    }

    public static float[] getRotations(Entity entity, double horizontalMultipoint, double verticalMultipoint, float baseYaw, float basePitch) {
        Vec3 aimPoint = getAimPoint(entity, horizontalMultipoint, verticalMultipoint);
        if (aimPoint == null) {
            return null;
        }
        return getRotationsToPoint(aimPoint.xCoord, aimPoint.yCoord, aimPoint.zCoord, baseYaw, basePitch);
    }

    public static Vec3 getAimPoint(Entity entity, double horizontalMultipoint, double verticalMultipoint) {
        if (entity == null || MINECRAFT.thePlayer == null) {
            return null;
        }

        float borderSize = entity.getCollisionBorderSize();
        AxisAlignedBB bb = entity.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);
        double centerX = (bb.minX + bb.maxX) / 2.0D;
        double centerY = entity instanceof EntityLivingBase
            ? entity.posY + ((EntityLivingBase) entity).getEyeHeight()
            : (bb.minY + bb.maxY) / 2.0D;
        double centerZ = (bb.minZ + bb.maxZ) / 2.0D;
        Vec3 eye = MINECRAFT.thePlayer.getPositionEyes(1.0F);

        if (bb.isVecInside(eye)) {
            return new Vec3(centerX, eye.yCoord, centerZ);
        }

        Vec3 closest = closestPointOnAabb(bb, eye);
        double horizontalFactor = Math.max(0.0D, Math.min(1.0D, horizontalMultipoint / 100.0D));
        double verticalFactor = Math.max(0.0D, Math.min(1.0D, verticalMultipoint / 100.0D));
        double targetX = centerX + (closest.xCoord - centerX) * horizontalFactor;
        double targetY = centerY + (closest.yCoord - centerY) * verticalFactor;
        double targetZ = centerZ + (closest.zCoord - centerZ) * horizontalFactor;
        return new Vec3(targetX, targetY, targetZ);
    }

    public static Vec3 closestPointOnAabb(AxisAlignedBB box, Vec3 point) {
        double x = Math.max(box.minX, Math.min(box.maxX, point.xCoord));
        double y = Math.max(box.minY, Math.min(box.maxY, point.yCoord));
        double z = Math.max(box.minZ, Math.min(box.maxZ, point.zCoord));
        return new Vec3(x, y, z);
    }

    public static List<Vec3> buildBackupPoints(Entity entity, Vec3 eye) {
        List<Vec3> points = new ArrayList<Vec3>();
        if (entity == null || MINECRAFT.thePlayer == null) {
            return points;
        }

        float borderSize = entity.getCollisionBorderSize();
        AxisAlignedBB bb = entity.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);
        double sizeX = bb.maxX - bb.minX;
        double sizeY = bb.maxY - bb.minY;
        double sizeZ = bb.maxZ - bb.minZ;

        boolean xPos = eye.xCoord > bb.maxX;
        boolean xNeg = eye.xCoord < bb.minX;
        boolean yPos = eye.yCoord > bb.maxY;
        boolean yNeg = eye.yCoord < bb.minY;
        boolean zPos = eye.zCoord > bb.maxZ;
        boolean zNeg = eye.zCoord < bb.minZ;

        int visibleFaceCount = (xPos || xNeg ? 1 : 0) + (yPos || yNeg ? 1 : 0) + (zPos || zNeg ? 1 : 0);
        if (visibleFaceCount == 0) {
            return points;
        }

        int pointsPerFace = BACKUP_TARGET_TOTAL / visibleFaceCount;
        if (xPos || xNeg) {
            double fixedX = xPos ? bb.maxX - BACKUP_FACE_INSET : bb.minX + BACKUP_FACE_INSET;
            addFaceGrid(points, 0, fixedX, bb.minY + BACKUP_FACE_INSET, bb.maxY - BACKUP_FACE_INSET, bb.minZ + BACKUP_FACE_INSET, bb.maxZ - BACKUP_FACE_INSET, pointsPerFace, sizeY, sizeZ);
        }
        if (yPos || yNeg) {
            double fixedY = yPos ? bb.maxY - BACKUP_FACE_INSET : bb.minY + BACKUP_FACE_INSET;
            addFaceGrid(points, 1, fixedY, bb.minX + BACKUP_FACE_INSET, bb.maxX - BACKUP_FACE_INSET, bb.minZ + BACKUP_FACE_INSET, bb.maxZ - BACKUP_FACE_INSET, pointsPerFace, sizeX, sizeZ);
        }
        if (zPos || zNeg) {
            double fixedZ = zPos ? bb.maxZ - BACKUP_FACE_INSET : bb.minZ + BACKUP_FACE_INSET;
            addFaceGrid(points, 2, fixedZ, bb.minX + BACKUP_FACE_INSET, bb.maxX - BACKUP_FACE_INSET, bb.minY + BACKUP_FACE_INSET, bb.maxY - BACKUP_FACE_INSET, pointsPerFace, sizeX, sizeY);
        }

        return points;
    }

    private static void addFaceGrid(List<Vec3> output, int fixedAxis, double fixedValue, double uMin, double uMax, double vMin, double vMax, int targetPoints, double dimU, double dimV) {
        if (dimU < 1.0E-4D || dimV < 1.0E-4D) {
            double uMid = (uMin + uMax) / 2.0D;
            double vMid = (vMin + vMax) / 2.0D;
            if (fixedAxis == 0) {
                output.add(new Vec3(fixedValue, uMid, vMid));
            } else if (fixedAxis == 1) {
                output.add(new Vec3(uMid, fixedValue, vMid));
            } else {
                output.add(new Vec3(uMid, vMid, fixedValue));
            }
            return;
        }

        double ratio = dimU / dimV;
        int gridU = Math.max(2, (int) Math.round(Math.sqrt(targetPoints * ratio)));
        int gridV = Math.max(2, (int) Math.round(Math.sqrt(targetPoints / ratio)));
        for (int i = 0; i < gridU; i++) {
            double u = uMin + (uMax - uMin) * i / (gridU - 1);
            for (int j = 0; j < gridV; j++) {
                double v = vMin + (vMax - vMin) * j / (gridV - 1);
                if (fixedAxis == 0) {
                    output.add(new Vec3(fixedValue, u, v));
                } else if (fixedAxis == 1) {
                    output.add(new Vec3(u, fixedValue, v));
                } else {
                    output.add(new Vec3(u, v, fixedValue));
                }
            }
        }
    }

    public static double distanceSqFromEyeToClosestOnAABB(Entity entity) {
        if (entity == null || MINECRAFT.thePlayer == null) {
            return Double.MAX_VALUE;
        }

        Vec3 eye = MINECRAFT.thePlayer.getPositionEyes(1.0F);
        float borderSize = entity.getCollisionBorderSize();
        AxisAlignedBB bb = entity.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);
        Vec3 closest = closestPointOnAabb(bb, eye);
        double dx = eye.xCoord - closest.xCoord;
        double dy = eye.yCoord - closest.yCoord;
        double dz = eye.zCoord - closest.zCoord;
        return dx * dx + dy * dy + dz * dz;
    }

    public static double distanceFromEyeToClosestOnAABB(Entity entity) {
        double distanceSq = distanceSqFromEyeToClosestOnAABB(entity);
        return distanceSq == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(distanceSq);
    }

    public static boolean canAimAtPoint(Vec3 eye, Vec3 point, Entity target, double range, boolean allowThroughBlocks, boolean allowThroughEntities) {
        if (target == null) {
            return false;
        }

        double dx = point.xCoord - eye.xCoord;
        double dy = point.yCoord - eye.yCoord;
        double dz = point.zCoord - eye.zCoord;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-6D) {
            return false;
        }

        double scale = range / length;
        Vec3 end = new Vec3(eye.xCoord + dx * scale, eye.yCoord + dy * scale, eye.zCoord + dz * scale);
        float borderSize = target.getCollisionBorderSize();
        AxisAlignedBB aabb = target.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);
        MovingObjectPosition entityHit = aabb.calculateIntercept(eye, end);
        if (entityHit == null) {
            return false;
        }

        double entityDistanceSq = eye.squareDistanceTo(entityHit.hitVec);
        if (!allowThroughBlocks) {
            MovingObjectPosition blockHit = MINECRAFT.theWorld.rayTraceBlocks(eye, end, false, false, false);
            if (blockHit != null && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                double blockDistanceSq = eye.squareDistanceTo(blockHit.hitVec);
                if (blockDistanceSq < entityDistanceSq) {
                    return false;
                }
            }
        }

        return allowThroughEntities || !hasEntityBlockingPath(eye, end, target, entityDistanceSq);
    }

    private static boolean hasEntityBlockingPath(Vec3 eye, Vec3 end, Entity target, double targetDistanceSq) {
        if (MINECRAFT.thePlayer == null || MINECRAFT.theWorld == null) {
            return false;
        }

        Vec3 delta = end.subtract(eye);
        AxisAlignedBB searchBox = MINECRAFT.thePlayer.getEntityBoundingBox().addCoord(delta.xCoord, delta.yCoord, delta.zCoord).expand(1.0D, 1.0D, 1.0D);
        List<?> entities = MINECRAFT.theWorld.getEntitiesWithinAABBExcludingEntity(MINECRAFT.thePlayer, searchBox);
        for (Object object : entities) {
            if (!(object instanceof Entity)) {
                continue;
            }

            Entity entity = (Entity) object;
            if (entity == target || entity.isDead || !entity.canBeCollidedWith()) {
                continue;
            }

            float border = entity.getCollisionBorderSize();
            AxisAlignedBB bb = entity.getEntityBoundingBox().expand(border, border, border);
            MovingObjectPosition hit = bb.calculateIntercept(eye, end);
            if (bb.isVecInside(eye)) {
                return true;
            }
            if (hit != null) {
                double entityDistanceSq = eye.squareDistanceTo(hit.hitVec);
                if (entityDistanceSq < targetDistanceSq - 1.0E-7D) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isPathBlockedByEntity(Vec3 eye, Vec3 hitVec, Entity target) {
        if (eye == null || hitVec == null || target == null) {
            return false;
        }
        return hasEntityBlockingPath(eye, hitVec, target, eye.squareDistanceTo(hitVec));
    }

    private static boolean mainRayHitsTargetAABB(Vec3 eye, Vec3 point, Entity target, double range) {
        double dx = point.xCoord - eye.xCoord;
        double dy = point.yCoord - eye.yCoord;
        double dz = point.zCoord - eye.zCoord;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-6D) {
            return false;
        }

        double scale = range / length;
        Vec3 end = new Vec3(eye.xCoord + dx * scale, eye.yCoord + dy * scale, eye.zCoord + dz * scale);
        float borderSize = target.getCollisionBorderSize();
        AxisAlignedBB aabb = target.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);
        return aabb.calculateIntercept(eye, end) != null;
    }

    public static boolean hasValidAimPoint(Entity entity, double horizontalMultipoint, double verticalMultipoint, double range, boolean allowThroughBlocks, boolean allowThroughEntities) {
        if (entity == null || MINECRAFT.thePlayer == null) {
            return false;
        }

        Vec3 mainPoint = getAimPoint(entity, horizontalMultipoint, verticalMultipoint);
        if (mainPoint == null) {
            return false;
        }

        Vec3 eye = MINECRAFT.thePlayer.getPositionEyes(1.0F);
        if (eye.squareDistanceTo(mainPoint) < 1.0E-6D) {
            return true;
        }
        if (!mainRayHitsTargetAABB(eye, mainPoint, entity, range)) {
            return false;
        }
        if (canAimAtPoint(eye, mainPoint, entity, range, allowThroughBlocks, allowThroughEntities)) {
            return true;
        }

        List<Vec3> backups = buildBackupPoints(entity, eye);
        Collections.sort(backups, Comparator.comparingDouble(new java.util.function.ToDoubleFunction<Vec3>() {
            @Override
            public double applyAsDouble(Vec3 point) {
                double dx = point.xCoord - eye.xCoord;
                double dy = point.yCoord - eye.yCoord;
                double dz = point.zCoord - eye.zCoord;
                return dx * dx + dy * dy + dz * dz;
            }
        }));
        for (Vec3 point : backups) {
            if (canAimAtPoint(eye, point, entity, range, allowThroughBlocks, allowThroughEntities)) {
                return true;
            }
        }
        return false;
    }

    public static float[] getRotationsWithBackup(Entity entity, double horizontalMultipoint, double verticalMultipoint, float baseYaw, float basePitch, double range, boolean allowThroughBlocks, boolean allowThroughEntities) {
        if (entity == null || MINECRAFT.thePlayer == null) {
            return null;
        }

        Vec3 eye = MINECRAFT.thePlayer.getPositionEyes(1.0F);
        float borderSize = entity.getCollisionBorderSize();
        AxisAlignedBB bb = entity.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);
        if (bb.isVecInside(eye)) {
            double centerX = (bb.minX + bb.maxX) / 2.0D;
            double centerZ = (bb.minZ + bb.maxZ) / 2.0D;
            return getRotationsToPoint(centerX, eye.yCoord, centerZ, baseYaw, basePitch);
        }

        Vec3 mainPoint = getAimPoint(entity, horizontalMultipoint, verticalMultipoint);
        if (mainPoint == null || eye.squareDistanceTo(mainPoint) < 1.0E-6D) {
            return null;
        }
        if (!mainRayHitsTargetAABB(eye, mainPoint, entity, range)) {
            return getRotationsToPoint(mainPoint.xCoord, mainPoint.yCoord, mainPoint.zCoord, baseYaw, basePitch);
        }
        if (canAimAtPoint(eye, mainPoint, entity, range, allowThroughBlocks, allowThroughEntities)) {
            return getRotationsToPoint(mainPoint.xCoord, mainPoint.yCoord, mainPoint.zCoord, baseYaw, basePitch);
        }

        List<Vec3> backups = buildBackupPoints(entity, eye);
        Collections.sort(backups, Comparator.comparingDouble(new java.util.function.ToDoubleFunction<Vec3>() {
            @Override
            public double applyAsDouble(Vec3 point) {
                double dx = point.xCoord - eye.xCoord;
                double dy = point.yCoord - eye.yCoord;
                double dz = point.zCoord - eye.zCoord;
                return dx * dx + dy * dy + dz * dz;
            }
        }));
        for (Vec3 point : backups) {
            if (canAimAtPoint(eye, point, entity, range, allowThroughBlocks, allowThroughEntities)) {
                return getRotationsToPoint(point.xCoord, point.yCoord, point.zCoord, baseYaw, basePitch);
            }
        }
        return null;
    }

    public static float[] smoothRotation(float baseYaw, float basePitch, float targetYaw, float targetPitch, int speed, float randomizationPercent) {
        if (speed <= 0) {
            return new float[]{baseYaw, clampPitch(basePitch)};
        }
        if (speed >= 30) {
            return new float[]{targetYaw, clampPitch(targetPitch)};
        }

        float deltaYaw = MathHelper.wrapAngleTo180_float(targetYaw - baseYaw);
        float deltaPitch = targetPitch - basePitch;
        float magnitude = (float) MathHelper.sqrt_double(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (magnitude < 0.001F) {
            return new float[]{targetYaw, clampPitch(targetPitch)};
        }

        float t = speed / 30.0F;
        float stepSize = t * t * 180.0F;
        float range = 0.6F * (randomizationPercent / 100.0F);
        float multiplier = range <= 0.001F ? 1.0F : 1.0F - range / 2.0F + (float) (Math.random() * range);
        stepSize *= multiplier;

        float proximityFactor = Math.min(1.0F, magnitude / FAR_THRESHOLD);
        proximityFactor = (float) Math.pow(proximityFactor, 0.7D);
        float maxSlowdown = randomizationPercent / 100.0F;
        float proximityMultiplier = Math.max(0.8F, 1.0F - maxSlowdown * (1.0F - proximityFactor));
        stepSize *= proximityMultiplier;

        float stepLength = Math.min(stepSize, magnitude);
        float scale = stepLength / magnitude;
        float stepYaw = deltaYaw * scale;
        float stepPitch = deltaPitch * scale;
        return new float[]{baseYaw + stepYaw, clampPitch(basePitch + stepPitch)};
    }

    public static float[] fixRotation(float targetYaw, float targetPitch, float yaw, float pitch) {
        targetYaw = ClientRotationHelper.unwrapYaw(targetYaw, yaw);
        float yawDelta = targetYaw - yaw;
        float pitchDelta = targetPitch - pitch;
        float sensitivity = MINECRAFT.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        double gcd = sensitivity * sensitivity * sensitivity * 1.2D;
        float snappedYaw = (float) (Math.round(yawDelta / gcd) * gcd);
        float snappedPitch = (float) (Math.round(pitchDelta / gcd) * gcd);
        return new float[]{yaw + snappedYaw, clampPitch(pitch + snappedPitch)};
    }
}
