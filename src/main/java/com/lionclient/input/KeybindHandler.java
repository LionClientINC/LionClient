package com.lionclient.input;

import com.lionclient.feature.module.Module;
import com.lionclient.feature.module.ModuleManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public final class KeybindHandler {
    private final ModuleManager moduleManager;

    private KeybindHandler(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    public static void register(ModuleManager moduleManager) {
        FMLCommonHandler.instance().bus().register(new KeybindHandler(moduleManager));
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!Keyboard.getEventKeyState()) {
            return;
        }

        int keyCode = Keyboard.getEventKey();
        for (Module module : moduleManager.getModules()) {
            if (module.getKeyCode() == keyCode) {
                module.toggle();
            }
        }
    }
}
