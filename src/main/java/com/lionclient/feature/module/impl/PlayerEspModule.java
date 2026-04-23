package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

public final class PlayerEspModule extends Module {
    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.MODERN);
    private final NumberSetting red = new NumberSetting("Red", 0, 255, 5, 255);
    private final NumberSetting green = new NumberSetting("Green", 0, 255, 5, 60);
    private final NumberSetting blue = new NumberSetting("Blue", 0, 255, 5, 60);

    public PlayerEspModule() {
        super("PlayerESP", "Draws a box around other players trough walls.", Category.RENDER, Keyboard.KEY_NONE);
        red.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return mode.getValue() == Mode.CLASSIC;
            }
        });
        green.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return mode.getValue() == Mode.CLASSIC;
            }
        });
        blue.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return mode.getValue() == Mode.CLASSIC;
            }
        });
        addSetting(mode);
        addSetting(red);
        addSetting(green);
        addSetting(blue);
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null || minecraft.thePlayer == null) {
            return;
        }

        float partialTicks = event.partialTicks;
        boolean modern = mode.getValue() == Mode.MODERN;

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

            double viewerX = minecraft.getRenderManager().viewerPosX;
            double viewerY = minecraft.getRenderManager().viewerPosY;
            double viewerZ = minecraft.getRenderManager().viewerPosZ;

            for (Object object : minecraft.theWorld.playerEntities) {
                if (!(object instanceof EntityPlayer)) {
                    continue;
                }

                EntityPlayer player = (EntityPlayer) object;
                if (player == minecraft.thePlayer || player.isInvisible() || AntiBotModule.shouldIgnore(player)) {
                    continue;
                }

                double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks - viewerX;
                double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks - viewerY;
                double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks - viewerZ;

                AxisAlignedBB bb = player.getEntityBoundingBox();
                AxisAlignedBB renderBox = new AxisAlignedBB(
                    bb.minX - player.posX + x,
                    bb.minY - player.posY + y,
                    bb.minZ - player.posZ + z,
                    bb.maxX - player.posX + x,
                    bb.maxY - player.posY + y,
                    bb.maxZ - player.posZ + z
                ).expand(0.05D, 0.1D, 0.05D);

                float[] colors = modern ? getModernColor(player) : getClassicColor();
                if (modern) {
                    drawFilledBox(renderBox, colors[0], colors[1], colors[2], 0.12F);
                }
                drawOutlinedBox(renderBox, colors[0], colors[1], colors[2], modern ? 0.95F : 1.0F);
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

    private void drawOutlinedBox(AxisAlignedBB bb, float r, float g, float b, float a) {
        GlStateManager.color(r, g, b, a);
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

    private void drawFilledBox(AxisAlignedBB bb, float r, float g, float b, float a) {
        GlStateManager.color(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);

        quad(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.minZ, bb.minX, bb.maxY, bb.minZ);
        quad(bb.minX, bb.minY, bb.maxZ, bb.maxX, bb.minY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);
        quad(bb.minX, bb.minY, bb.minZ, bb.minX, bb.minY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.minZ);
        quad(bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ, bb.maxX, bb.maxY, bb.minZ);
        quad(bb.minX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);
        quad(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ, bb.minX, bb.minY, bb.maxZ);

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

    private float[] getClassicColor() {
        return new float[] {
            red.getValue() / 255.0F,
            green.getValue() / 255.0F,
            blue.getValue() / 255.0F
        };
    }

    private float[] getModernColor(EntityPlayer player) {
        double time = System.currentTimeMillis() / 340.0D;
        float wave = (float) ((Math.sin(time + (player.getEntityId() * 0.35D)) + 1.0D) * 0.5D);
        int color = ClickGuiModule.blendColor(ClickGuiModule.getLightAccentColor(), ClickGuiModule.getDarkAccentColor(), wave);
        return new float[] {
            ((color >>> 16) & 255) / 255.0F,
            ((color >>> 8) & 255) / 255.0F,
            (color & 255) / 255.0F
        };
    }

    private enum Mode {
        MODERN("Modern"),
        CLASSIC("Classic");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
