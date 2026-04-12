package com.lionclient;

import com.lionclient.feature.module.ModuleManager;
import com.lionclient.gui.ClickGuiScreen;
import com.lionclient.input.KeybindHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = LionClient.MOD_ID, name = LionClient.NAME, version = LionClient.VERSION, clientSideOnly = true)
public final class LionClient {
    public static final String MOD_ID = "lionclient";
    public static final String NAME = "LionClient";
    public static final String VERSION = "1.0.0";

    private static LionClient instance;
    private final ModuleManager moduleManager = new ModuleManager();
    private final ClickGuiScreen clickGuiScreen = new ClickGuiScreen(moduleManager);
    private final KeyBinding clickGuiKey = new KeyBinding("key.lionclient.clickgui", Keyboard.KEY_RSHIFT, "key.categories.lionclient");

    public static LionClient getInstance() {
        return instance;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    @EventHandler
    public void onPreInit(FMLPreInitializationEvent event) {
        instance = this;
    }

    @EventHandler
    public void onInit(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(clickGuiKey);
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
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        while (clickGuiKey.isPressed()) {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.currentScreen == null) {
                minecraft.displayGuiScreen(clickGuiScreen);
            } else if (minecraft.currentScreen == clickGuiScreen) {
                minecraft.displayGuiScreen(null);
            }
        }

        moduleManager.onClientTick();
    }

    @SubscribeEvent
    public void onMouseInput(MouseEvent event) {
        moduleManager.onMouseEvent(event);
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
