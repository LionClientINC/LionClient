package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

public final class BedPlatesModule extends Module {
    private final NumberSetting range = new NumberSetting("Range", 5, 64, 1, 24);
    private final NumberSetting layers = new NumberSetting("Layers", 1, 4, 1, 2);
    private final BooleanSetting showDistance = new BooleanSetting("Show Distance", true);

    public BedPlatesModule() {
        super("BedPlates", "Shows the unique defense blocks around nearby beds.", Category.RENDER, Keyboard.KEY_NONE);
        addSetting(range);
        addSetting(layers);
        addSetting(showDistance);
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }

        List<BedDefenseInfo> beds = findBeds(mc, player);
        if (beds.isEmpty()) {
            return;
        }

        Collections.sort(beds, new Comparator<BedDefenseInfo>() {
            @Override
            public int compare(BedDefenseInfo left, BedDefenseInfo right) {
                return Double.compare(left.distanceSq, right.distanceSq);
            }
        });

        for (BedDefenseInfo bed : beds) {
            renderLabel(mc, bed, event.partialTicks);
        }
    }

    private List<BedDefenseInfo> findBeds(Minecraft mc, EntityPlayerSP player) {
        int radius = range.getValue();
        int centerX = MathHelper.floor_double(player.posX);
        int centerY = MathHelper.floor_double(player.posY);
        int centerZ = MathHelper.floor_double(player.posZ);
        int minY = Math.max(0, centerY - radius);
        int maxY = Math.min(255, centerY + radius);
        double maxDistanceSq = radius * radius;

        Map<String, BedDefenseInfo> beds = new HashMap<String, BedDefenseInfo>();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    Block block = mc.theWorld.getBlockState(pos).getBlock();
                    if (!(block instanceof BlockBed)) {
                        continue;
                    }

                    double distanceSq = player.getDistanceSq(pos);
                    if (distanceSq > maxDistanceSq) {
                        continue;
                    }

                    BedPair pair = resolveBedPair(mc, pos, (BlockBed) block);
                    String key = makeBedKey(pair.first, pair.second);
                    if (beds.containsKey(key)) {
                        continue;
                    }

                    Set<String> defenses = collectDefenseBlocks(mc, pair.first, pair.second);
                    beds.put(key, new BedDefenseInfo(pair.first, pair.second, defenses, distanceSq));
                }
            }
        }

        return new ArrayList<BedDefenseInfo>(beds.values());
    }

    private BedPair resolveBedPair(Minecraft mc, BlockPos pos, BlockBed bed) {
        BlockPos otherPart = pos;
        BlockPos[] neighbors = new BlockPos[] {
            pos.north(),
            pos.south(),
            pos.east(),
            pos.west()
        };
        for (BlockPos neighbor : neighbors) {
            if (mc.theWorld.getBlockState(neighbor).getBlock() instanceof BlockBed) {
                otherPart = neighbor;
                break;
            }
        }

        BlockPos first = comparePos(pos, otherPart) <= 0 ? pos : otherPart;
        BlockPos second = comparePos(pos, otherPart) <= 0 ? otherPart : pos;
        return new BedPair(first, second);
    }

    private Set<String> collectDefenseBlocks(Minecraft mc, BlockPos first, BlockPos second) {
        Set<String> names = new LinkedHashSet<String>();
        int radius = layers.getValue();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = 0; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    addDefenseBlock(mc, names, first.add(dx, dy, dz));
                    addDefenseBlock(mc, names, second.add(dx, dy, dz));
                }
            }
        }

        return names;
    }

    private void addDefenseBlock(Minecraft mc, Set<String> names, BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block == null || block == Blocks.air || block instanceof BlockBed || block.getMaterial() == Material.air) {
            return;
        }
        names.add(cleanBlockName(block));
    }

    private String cleanBlockName(Block block) {
        String name = block.getLocalizedName();
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }

        Object fallbackObject = Block.blockRegistry.getNameForObject(block);
        String fallback = fallbackObject == null ? null : fallbackObject.toString();
        if (fallback == null) {
            return "Unknown";
        }
        int separator = fallback.indexOf(':');
        String simple = separator >= 0 ? fallback.substring(separator + 1) : fallback;
        return simple.replace('_', ' ');
    }

    private void renderLabel(Minecraft mc, BedDefenseInfo bed, float partialTicks) {
        FontRenderer font = mc.fontRendererObj;
        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;

        double x = (bed.first.getX() + bed.second.getX()) / 2.0D + 0.5D - viewerX;
        double y = Math.max(bed.first.getY(), bed.second.getY()) + 1.35D - viewerY;
        double z = (bed.first.getZ() + bed.second.getZ()) / 2.0D + 0.5D - viewerZ;

        String defenseText = bed.defenses.isEmpty() ? "Uncovered" : joinNames(bed.defenses);
        if (showDistance.isEnabled()) {
            defenseText = defenseText + String.format(Locale.US, " [%.1fm]", Math.sqrt(bed.distanceSq));
        }

        float scale = 0.026F;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(mc.getRenderManager().playerViewX, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

        int width = font.getStringWidth(defenseText) / 2;
        drawBackground(width);
        font.drawStringWithShadow(defenseText, -width, 0, 0xFFFFFFFF);

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    private void drawBackground(int halfWidth) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        GlStateManager.disableTexture2D();
        renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        renderer.pos(-halfWidth - 2, -2, 0.0D).color(0.0F, 0.0F, 0.0F, 0.45F).endVertex();
        renderer.pos(-halfWidth - 2, 10, 0.0D).color(0.0F, 0.0F, 0.0F, 0.45F).endVertex();
        renderer.pos(halfWidth + 2, 10, 0.0D).color(0.0F, 0.0F, 0.0F, 0.45F).endVertex();
        renderer.pos(halfWidth + 2, -2, 0.0D).color(0.0F, 0.0F, 0.0F, 0.45F).endVertex();
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    private String joinNames(Set<String> names) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (String name : names) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(name);
            index++;
            if (builder.length() > 48 && index < names.size()) {
                builder.append("...");
                break;
            }
        }
        return builder.toString();
    }

    private String makeBedKey(BlockPos first, BlockPos second) {
        return first.getX() + ":" + first.getY() + ":" + first.getZ() + "|" + second.getX() + ":" + second.getY() + ":" + second.getZ();
    }

    private int comparePos(BlockPos left, BlockPos right) {
        if (left.getY() != right.getY()) {
            return left.getY() - right.getY();
        }
        if (left.getX() != right.getX()) {
            return left.getX() - right.getX();
        }
        return left.getZ() - right.getZ();
    }

    private static final class BedPair {
        private final BlockPos first;
        private final BlockPos second;

        private BedPair(BlockPos first, BlockPos second) {
            this.first = first;
            this.second = second;
        }
    }

    private static final class BedDefenseInfo {
        private final BlockPos first;
        private final BlockPos second;
        private final Set<String> defenses;
        private final double distanceSq;

        private BedDefenseInfo(BlockPos first, BlockPos second, Set<String> defenses, double distanceSq) {
            this.first = first;
            this.second = second;
            this.defenses = defenses;
            this.distanceSq = distanceSq;
        }
    }
}
