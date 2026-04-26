package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

public final class BacktrackModule extends Module {
    private final EnumSetting<TargetMode> targetMode = new EnumSetting<TargetMode>("Target Mode", TargetMode.values(), TargetMode.ATTACK);
    private final DecimalSetting range = new DecimalSetting("Range", 1.0D, 8.0D, 0.1D, 3.0D);
    private final NumberSetting delay = new NumberSetting("Delay", 0, 500, 5, 140);
    private final NumberSetting nextBacktrackDelay = new NumberSetting("Next Delay", 0, 2000, 25, 0);
    private final NumberSetting trackingBuffer = new NumberSetting("Tracking Buffer", 0, 2000, 25, 500);
    private final NumberSetting attackWindow = new NumberSetting("Attack Window", 0, 5000, 50, 1000);
    private final NumberSetting chance = new NumberSetting("Chance", 0, 100, 1, 100);
    private final BooleanSetting pauseOnHurt = new BooleanSetting("Pause On Hurt", false);
    private final NumberSetting hurtTimePause = new NumberSetting("Hurt Time", 0, 10, 1, 3);
    private final BooleanSetting flushOnHurt = new BooleanSetting("Flush On Hurt", false);
    private final BooleanSetting targetEsp = new BooleanSetting("Target ESP", true);

    private final Random random = new Random();
    private final Set<Packet<?>> delayedPackets = Collections.newSetFromMap(new IdentityHashMap<Packet<?>, Boolean>());

    private EntityLivingBase target;
    private long lastAttackTime;
    private long lastInRangeTime;
    private long nextStartTime;
    private int currentChance;
    private boolean inboundFlushRequested;
    private boolean ghostValid;
    private double ghostX;
    private double ghostY;
    private double ghostZ;
    private float ghostYaw;
    private float ghostPitch;
    private float ghostHeadYaw;

    public BacktrackModule() {
        super("Backtrack", "Delays target movement packets and preserves a ghost position.", Category.COMBAT, Keyboard.KEY_NONE);
        attackWindow.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return targetMode.getValue() == TargetMode.ATTACK;
            }
        });
        hurtTimePause.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return pauseOnHurt.isEnabled();
            }
        });
        addSetting(targetMode);
        addSetting(range);
        addSetting(delay);
        addSetting(nextBacktrackDelay);
        addSetting(trackingBuffer);
        addSetting(attackWindow);
        addSetting(chance);
        addSetting(pauseOnHurt);
        addSetting(hurtTimePause);
        addSetting(flushOnHurt);
        addSetting(targetEsp);
    }

    @Override
    protected void onEnable() {
        resetState(false, true);
    }

    @Override
    protected void onDisable() {
        resetState(true, true);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null || minecraft.thePlayer.isDead) {
            resetState(true, true);
            return;
        }

        if (targetMode.getValue() == TargetMode.RANGE) {
            EntityLivingBase rangeTarget = findRangeTarget(minecraft);
            if (rangeTarget != null) {
                processTarget(rangeTarget);
            } else if (!hasDelayedPackets()) {
                resetState(false, false);
            }
        }

        if (target == null) {
            if (hasDelayedPackets()) {
                requestFlush();
            }
            return;
        }

        if (!shouldBacktrack(target, true)) {
            if (hasDelayedPackets()) {
                requestFlush();
            }

            if (!canKeepTracking(target)) {
                resetState(false, false);
            }
            return;
        }

        if (!ghostValid) {
            seedGhostFromEntity(target);
        }

        if (shouldFlushForCloserRealPosition(target)) {
            requestFlush();
        }
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if (!(packet instanceof C02PacketUseEntity)) {
            return;
        }

        C02PacketUseEntity useEntityPacket = (C02PacketUseEntity) packet;
        if (useEntityPacket.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null || minecraft.thePlayer == null) {
            return;
        }

        Entity attackedEntity = useEntityPacket.getEntityFromWorld(minecraft.theWorld);
        if (!(attackedEntity instanceof EntityLivingBase) || attackedEntity == minecraft.thePlayer) {
            return;
        }

        lastAttackTime = System.currentTimeMillis();
        currentChance = random.nextInt(101);
        if (targetMode.getValue() == TargetMode.ATTACK) {
            processTarget((EntityLivingBase) attackedEntity);
        }
    }

    @Override
    public void onInboundPacket(Packet<?> packet) {
        if (packet instanceof S08PacketPlayerPosLook) {
            resetState(true, true);
        }
    }

    @Override
    public int getInboundPacketDelay(Packet<?> packet) {
        if (delay.getValue() <= 0 || target == null || !isTrackablePacket(packet)) {
            return 0;
        }
        if (!shouldBacktrack(target, true) || !isTrackedTargetPacket(packet, target)) {
            return 0;
        }

        return delay.getValue();
    }

    @Override
    public void onInboundPacketQueued(Packet<?> packet) {
        if (!isEnabled() || target == null || !isTrackablePacket(packet) || !isTrackedTargetPacket(packet, target)) {
            return;
        }

        delayedPackets.add(packet);
        applyGhostPacket(packet);
    }

    @Override
    public void onInboundPacketReleased(Packet<?> packet) {
        delayedPackets.remove(packet);
    }

    @Override
    public boolean isInboundPacketDelayActive() {
        return target != null && shouldBacktrack(target, false);
    }

    @Override
    public boolean consumeInboundFlushRequest() {
        boolean requested = inboundFlushRequested;
        inboundFlushRequested = false;
        return requested;
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!targetEsp.isEnabled() || target == null || !shouldBacktrack(target, false)) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }

        GL11.glPushMatrix();
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GL11.glLineWidth(1.8F);

            drawActualTargetBox(minecraft, target, event.partialTicks);
            if (ghostValid && hasDelayedPackets()) {
                drawGhostBox(minecraft, target);
            }
        } finally {
            GL11.glLineWidth(1.0F);
            GlStateManager.enableCull();
            GlStateManager.disableLighting();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glPopMatrix();
        }
    }

    private EntityLivingBase findRangeTarget(Minecraft minecraft) {
        EntityLivingBase closest = null;
        double bestDistanceSq = Double.MAX_VALUE;
        double maxDistanceSq = range.getValue() * range.getValue();

        for (Object object : minecraft.theWorld.playerEntities) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }

            EntityPlayer player = (EntityPlayer) object;
            if (!isValidCandidate(player)) {
                continue;
            }

            double distanceSq = minecraft.thePlayer.getDistanceSqToEntity(player);
            if (distanceSq > maxDistanceSq || distanceSq >= bestDistanceSq) {
                continue;
            }

            closest = player;
            bestDistanceSq = distanceSq;
        }

        return closest;
    }

    private void processTarget(EntityLivingBase candidate) {
        if (!isValidCandidate(candidate) || !shouldBacktrack(candidate, true)) {
            return;
        }

        if (candidate != target) {
            resetState(true, false);
            target = candidate;
            seedGhostFromEntity(candidate);
            return;
        }

        target = candidate;
        if (!ghostValid) {
            seedGhostFromEntity(candidate);
        }
    }

    private boolean shouldBacktrack(EntityLivingBase candidate, boolean updateRangeTime) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!isValidCandidate(candidate) || minecraft.thePlayer == null || minecraft.theWorld == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now < nextStartTime) {
            return false;
        }

        boolean inRange = minecraft.thePlayer.getDistanceToEntity(candidate) <= range.getValue();
        if (inRange && updateRangeTime) {
            lastInRangeTime = now;
        }

        if (!inRange && now - lastInRangeTime > trackingBuffer.getValue()) {
            return false;
        }
        if (currentChance > chance.getValue()) {
            return false;
        }
        if (targetMode.getValue() == TargetMode.ATTACK && now - lastAttackTime > attackWindow.getValue()) {
            return false;
        }
        if (minecraft.thePlayer.ticksExisted <= 10) {
            return false;
        }
        if (pauseOnHurt.isEnabled() && candidate.hurtTime >= hurtTimePause.getValue()) {
            return false;
        }

        return !flushOnHurt.isEnabled() || candidate.hurtTime <= 0;
    }

    private boolean canKeepTracking(EntityLivingBase candidate) {
        return isValidCandidate(candidate)
            && System.currentTimeMillis() - lastInRangeTime <= trackingBuffer.getValue();
    }

    private boolean isValidCandidate(EntityLivingBase candidate) {
        if (candidate == null || candidate.isDead || candidate.getHealth() <= 0.0F) {
            return false;
        }
        if (candidate == Minecraft.getMinecraft().thePlayer || candidate.isInvisible()) {
            return false;
        }

        return !(candidate instanceof EntityPlayer) || !AntiBotModule.shouldIgnore((EntityPlayer) candidate);
    }

    private boolean isTrackablePacket(Packet<?> packet) {
        return packet instanceof S14PacketEntity
            || packet instanceof S18PacketEntityTeleport
            || packet instanceof S19PacketEntityHeadLook;
    }

    private boolean isTrackedTargetPacket(Packet<?> packet, EntityLivingBase trackedTarget) {
        return trackedTarget != null && getPacketEntityId(packet) == trackedTarget.getEntityId();
    }

    private int getPacketEntityId(Packet<?> packet) {
        if (packet instanceof S14PacketEntity) {
            Entity entity = ((S14PacketEntity) packet).getEntity(Minecraft.getMinecraft().theWorld);
            return entity == null ? Integer.MIN_VALUE : entity.getEntityId();
        }

        String[] candidateFields = packet instanceof S18PacketEntityTeleport
            ? new String[] { "entityId", "field_149458_a" }
            : new String[] { "entityId", "field_149384_a" };

        for (String fieldName : candidateFields) {
            try {
                Field field = packet.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.getInt(packet);
            } catch (IllegalAccessException ignored) {
            } catch (NoSuchFieldException ignored) {
            }
        }

        for (Field field : packet.getClass().getDeclaredFields()) {
            if (field.getType() != int.class) {
                continue;
            }

            try {
                field.setAccessible(true);
                return field.getInt(packet);
            } catch (IllegalAccessException ignored) {
            }
        }

        return Integer.MIN_VALUE;
    }

    private void seedGhostFromEntity(EntityLivingBase entity) {
        ghostX = entity.posX;
        ghostY = entity.posY;
        ghostZ = entity.posZ;
        ghostYaw = entity.rotationYaw;
        ghostPitch = entity.rotationPitch;
        ghostHeadYaw = entity.rotationYawHead;
        ghostValid = true;
        lastInRangeTime = System.currentTimeMillis();
    }

    private void applyGhostPacket(Packet<?> packet) {
        if (!ghostValid) {
            return;
        }

        if (packet instanceof S14PacketEntity) {
            ghostX += getRelativeMove(packet, "posX", "field_149072_b") / 32.0D;
            ghostY += getRelativeMove(packet, "posY", "field_149073_c") / 32.0D;
            ghostZ += getRelativeMove(packet, "posZ", "field_149070_d") / 32.0D;
            ghostYaw = unpackRotation(readByteField(packet, "yaw", "field_149071_e"), ghostYaw);
            ghostPitch = unpackRotation(readByteField(packet, "pitch", "field_149068_f"), ghostPitch);
            return;
        }

        if (packet instanceof S18PacketEntityTeleport) {
            ghostX = getTeleportCoord(packet, "posX", "field_149456_b");
            ghostY = getTeleportCoord(packet, "posY", "field_149457_c");
            ghostZ = getTeleportCoord(packet, "posZ", "field_149454_d");
            ghostYaw = unpackRotation(readByteField(packet, "yaw", "field_149455_e"), ghostYaw);
            ghostPitch = unpackRotation(readByteField(packet, "pitch", "field_149453_f"), ghostPitch);
            ghostValid = true;
            return;
        }

        if (packet instanceof S19PacketEntityHeadLook) {
            ghostHeadYaw = unpackRotation(readByteField(packet, "yaw", "field_149383_b"), ghostHeadYaw);
        }
    }

    private int getRelativeMove(Packet<?> packet, String... candidates) {
        Number number = readNumberField(packet, byte.class, candidates);
        if (number == null) {
            number = readNumberField(packet, int.class, candidates);
        }
        return number == null ? 0 : number.intValue();
    }

    private double getTeleportCoord(Packet<?> packet, String... candidates) {
        Number number = readNumberField(packet, int.class, candidates);
        return number == null ? 0.0D : number.intValue() / 32.0D;
    }

    private float unpackRotation(Byte packed, float fallback) {
        return packed == null ? fallback : (packed.byteValue() & 255) * 360.0F / 256.0F;
    }

    private Byte readByteField(Packet<?> packet, String... candidateFields) {
        Number number = readNumberField(packet, byte.class, candidateFields);
        return number == null ? null : Byte.valueOf(number.byteValue());
    }

    private Number readNumberField(Packet<?> packet, Class<?> type, String... candidateFields) {
        for (String fieldName : candidateFields) {
            try {
                Field field = packet.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(packet);
                if (value instanceof Number) {
                    return (Number) value;
                }
            } catch (IllegalAccessException ignored) {
            } catch (NoSuchFieldException ignored) {
            }
        }

        for (Field field : packet.getClass().getDeclaredFields()) {
            if (field.getType() != type) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(packet);
                if (value instanceof Number) {
                    return (Number) value;
                }
            } catch (IllegalAccessException ignored) {
            }
        }

        return null;
    }

    private boolean shouldFlushForCloserRealPosition(EntityLivingBase entity) {
        if (!ghostValid || !hasDelayedPackets()) {
            return false;
        }

        double realDistance = boxedDistanceSq(entity.posX, entity.posY, entity.posZ, entity.width, entity.height);
        double ghostDistance = boxedDistanceSq(ghostX, ghostY, ghostZ, entity.width, entity.height);
        return realDistance <= ghostDistance;
    }

    private double boxedDistanceSq(double x, double y, double z, float width, float height) {
        Minecraft minecraft = Minecraft.getMinecraft();
        double halfWidth = width / 2.0D;
        double minX = x - halfWidth;
        double maxX = x + halfWidth;
        double minY = y;
        double maxY = y + height;
        double minZ = z - halfWidth;
        double maxZ = z + halfWidth;

        double px = minecraft.thePlayer.posX;
        double py = minecraft.thePlayer.posY + minecraft.thePlayer.getEyeHeight();
        double pz = minecraft.thePlayer.posZ;

        double dx = clamp(px, minX, maxX) - px;
        double dy = clamp(py, minY, maxY) - py;
        double dz = clamp(pz, minZ, maxZ) - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    private double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    private void requestFlush() {
        if (delayedPackets.isEmpty()) {
            return;
        }

        delayedPackets.clear();
        inboundFlushRequested = true;
    }

    private void resetState(boolean flushPackets, boolean resetAttackState) {
        if (flushPackets) {
            requestFlush();
        } else {
            delayedPackets.clear();
        }

        if (target != null) {
            nextStartTime = System.currentTimeMillis() + nextBacktrackDelay.getValue();
        }

        if (resetAttackState) {
            lastAttackTime = 0L;
        }

        target = null;
        ghostValid = false;
        ghostX = 0.0D;
        ghostY = 0.0D;
        ghostZ = 0.0D;
        ghostYaw = 0.0F;
        ghostPitch = 0.0F;
        ghostHeadYaw = 0.0F;
    }

    private boolean hasDelayedPackets() {
        return !delayedPackets.isEmpty();
    }

    private void drawActualTargetBox(Minecraft minecraft, EntityLivingBase entity, float partialTicks) {
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - minecraft.getRenderManager().viewerPosX;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - minecraft.getRenderManager().viewerPosY;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - minecraft.getRenderManager().viewerPosZ;
        AxisAlignedBB bb = entity.getEntityBoundingBox();
        AxisAlignedBB renderBox = new AxisAlignedBB(
            bb.minX - entity.posX + x,
            bb.minY - entity.posY + y,
            bb.minZ - entity.posZ + z,
            bb.maxX - entity.posX + x,
            bb.maxY - entity.posY + y,
            bb.maxZ - entity.posZ + z
        ).expand(0.05D, 0.1D, 0.05D);
        drawBox(renderBox, 0.35F, 1.0F, 0.45F, hasDelayedPackets() ? 0.12F : 0.08F, 0.95F);
    }

    private void drawGhostBox(Minecraft minecraft, EntityLivingBase entity) {
        double renderX = ghostX - minecraft.getRenderManager().viewerPosX;
        double renderY = ghostY - minecraft.getRenderManager().viewerPosY;
        double renderZ = ghostZ - minecraft.getRenderManager().viewerPosZ;
        double halfWidth = entity.width / 2.0D + 0.05D;
        AxisAlignedBB ghostBox = new AxisAlignedBB(
            renderX - halfWidth,
            renderY,
            renderZ - halfWidth,
            renderX + halfWidth,
            renderY + entity.height + 0.1D,
            renderZ + halfWidth
        );
        drawBox(ghostBox, 1.0F, 0.35F, 0.35F, 0.14F, 0.95F);
    }

    private void drawBox(AxisAlignedBB bb, float red, float green, float blue, float fillAlpha, float outlineAlpha) {
        GlStateManager.color(red, green, blue, fillAlpha);
        GL11.glBegin(GL11.GL_QUADS);
        quad(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.minZ, bb.minX, bb.maxY, bb.minZ);
        quad(bb.minX, bb.minY, bb.maxZ, bb.maxX, bb.minY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);
        quad(bb.minX, bb.minY, bb.minZ, bb.minX, bb.minY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.minZ);
        quad(bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ, bb.maxX, bb.maxY, bb.minZ);
        quad(bb.minX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);
        quad(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ, bb.minX, bb.minY, bb.maxZ);
        GL11.glEnd();

        GlStateManager.color(red, green, blue, outlineAlpha);
        GL11.glBegin(GL11.GL_LINES);
        vertex(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.minZ);
        vertex(bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ);
        vertex(bb.maxX, bb.minY, bb.maxZ, bb.minX, bb.minY, bb.maxZ);
        vertex(bb.minX, bb.minY, bb.maxZ, bb.minX, bb.minY, bb.minZ);
        vertex(bb.minX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.minZ);
        vertex(bb.maxX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
        vertex(bb.maxX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);
        vertex(bb.minX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.minZ);
        vertex(bb.minX, bb.minY, bb.minZ, bb.minX, bb.maxY, bb.minZ);
        vertex(bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.minZ);
        vertex(bb.maxX, bb.minY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ);
        vertex(bb.minX, bb.minY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);
        GL11.glEnd();
    }

    private void vertex(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
    }

    private void quad(double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3, double z3, double x4, double y4, double z4) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
        GL11.glVertex3d(x3, y3, z3);
        GL11.glVertex3d(x4, y4, z4);
    }

    private enum TargetMode {
        ATTACK("Attack"),
        RANGE("Range");

        private final String label;

        TargetMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
