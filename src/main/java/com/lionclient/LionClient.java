package com.lionclient;

import com.lionclient.feature.module.ModuleManager;
import com.lionclient.feature.module.impl.ClickGuiModule;
import com.lionclient.feature.module.impl.HudModule;
import com.lionclient.gui.ClickGuiScreen;
import com.lionclient.gui.HudEditorScreen;
import com.lionclient.gui.ModernClickGuiScreen;
import com.lionclient.input.KeybindHandler;
import com.lionclient.network.KnockbackDelayBuffer;
import com.lionclient.network.PacketDelayManager;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(modid = LionClient.MOD_ID, name = LionClient.NAME, version = LionClient.VERSION, clientSideOnly = true)
public final class LionClient {
    public static final String MOD_ID = "lionclient";
    public static final String NAME = "LionClient";
    public static final String VERSION = "1.0.5";

    private static LionClient instance;
    private final ModuleManager moduleManager = new ModuleManager();
    private final PacketDelayManager packetDelayManager = new PacketDelayManager(moduleManager);
    private final KnockbackDelayBuffer knockbackDelayBuffer = new KnockbackDelayBuffer();
    private final ClickGuiScreen clickGuiScreen = new ClickGuiScreen(moduleManager);
    private final ModernClickGuiScreen modernClickGuiScreen = new ModernClickGuiScreen(moduleManager);

    public static LionClient getInstance() {
        return instance;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public PacketDelayManager getPacketDelayManager() {
        return packetDelayManager;
    }

    public KnockbackDelayBuffer getKnockbackDelayBuffer() {
        return knockbackDelayBuffer;
    }

    @EventHandler
    public void onPreInit(FMLPreInitializationEvent event) {
        instance = this;
    }

    @EventHandler
    public void onInit(FMLInitializationEvent event) {
        KeybindHandler.register(moduleManager);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                moduleManager.getConfigManager().saveCurrent();
            }
        }, "LionClient-ConfigSave"));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        moduleManager.onClientTick(event);

        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        moduleManager.onClientTick();
        knockbackDelayBuffer.onClientTick();
        packetDelayManager.onClientTick();
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        moduleManager.onPlayerTick(event);
    }

    public void toggleClickGui() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen == clickGuiScreen || minecraft.currentScreen == modernClickGuiScreen) {
            minecraft.displayGuiScreen(null);
            return;
        }

        if (minecraft.currentScreen == null) {
            minecraft.displayGuiScreen(ClickGuiModule.getGuiStyle() == ClickGuiModule.GuiStyle.CLASSIC ? clickGuiScreen : modernClickGuiScreen);
        }
    }

    public void refreshClickGuiStyle() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != clickGuiScreen && minecraft.currentScreen != modernClickGuiScreen) {
            return;
        }

        minecraft.displayGuiScreen(ClickGuiModule.getGuiStyle() == ClickGuiModule.GuiStyle.CLASSIC ? clickGuiScreen : modernClickGuiScreen);
    }

    public void openHudEditor() {
        HudModule hudModule = HudModule.getInstance();
        if (hudModule == null) {
            return;
        }

        Minecraft.getMinecraft().displayGuiScreen(new HudEditorScreen(hudModule));
    }

    @SubscribeEvent
    public void onMouseInput(MouseEvent event) {
        moduleManager.onMouseEvent(event);
    }

    @SubscribeEvent
    public void onPlayerJump(LivingEvent.LivingJumpEvent event) {
        moduleManager.onPlayerJump(event);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        moduleManager.onRenderTick(event);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        moduleManager.onRenderWorld(event);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        moduleManager.onRenderOverlay(event);
    }
}
