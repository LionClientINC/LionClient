package com.lionclient.feature.module;

import com.lionclient.config.ConfigManager;
import com.lionclient.feature.module.impl.AutoClickerModule;
import com.lionclient.feature.module.impl.AntiBotModule;
import com.lionclient.feature.module.impl.AntiFireballModule;
import com.lionclient.feature.module.impl.BedPlatesModule;
import com.lionclient.feature.module.impl.ClickGuiModule;
import com.lionclient.feature.module.impl.ClickRecorderModule;
import com.lionclient.feature.module.impl.ConfigModule;
import com.lionclient.feature.module.impl.HudModule;
import com.lionclient.feature.module.impl.KillAuraModule;
import com.lionclient.feature.module.impl.KnockbackDelayModule;
import com.lionclient.feature.module.impl.LegitScaffoldModule;
import com.lionclient.feature.module.impl.PlayerEspModule;
import com.lionclient.feature.module.impl.ReachModule;
import com.lionclient.feature.module.impl.RightClickerModule;
import com.lionclient.feature.module.impl.SprintModule;
import com.lionclient.feature.module.impl.TrajectoriesModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.Packet;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class ModuleManager {
    private final List<Module> modules = new ArrayList<Module>();
    private final Map<Category, List<Module>> modulesByCategory = new EnumMap<Category, List<Module>>(Category.class);
    private final ConfigManager configManager;
    private final ConfigModule configModule;

    public ModuleManager() {
        for (Category category : Category.values()) {
            modulesByCategory.put(category, new ArrayList<Module>());
        }

        register(new SprintModule());
        register(new BedPlatesModule());
        register(new LegitScaffoldModule());
        register(new AutoClickerModule());
        register(new RightClickerModule());
        register(new ReachModule());
        register(new AntiBotModule());
        register(new AntiFireballModule());
        register(new KillAuraModule());
        register(new KnockbackDelayModule());
        register(new ClickRecorderModule());
        register(new ClickGuiModule());
        register(new PlayerEspModule());
        register(new HudModule());
        register(new TrajectoriesModule());
        configManager = new ConfigManager(this);
        configModule = new ConfigModule(configManager);
        register(configModule);
        configManager.initialize();
    }

    private void register(Module module) {
        modules.add(module);
        modulesByCategory.get(module.getCategory()).add(module);
    }

    public List<Module> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<Module> getModules(Category category) {
        return Collections.unmodifiableList(modulesByCategory.get(category));
    }

    public <T extends Module> T getModule(Class<T> moduleClass) {
        for (Module module : modules) {
            if (moduleClass.isInstance(module)) {
                return moduleClass.cast(module);
            }
        }
        return null;
    }

    public void onClientTick() {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onClientTick();
            }
        }
    }

    public void onClientTick(TickEvent.ClientTickEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onClientTick(event);
            }
        }
    }

    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onPlayerTick(event);
            }
        }
    }

    public void onMouseEvent(MouseEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onMouseEvent(event);
            }
        }
    }

    public void onPlayerJump(LivingEvent.LivingJumpEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onPlayerJump(event);
            }
        }
    }

    public void onRenderTick(TickEvent.RenderTickEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onRenderTick(event);
            }
        }
    }

    public void onRenderWorld(RenderWorldLastEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onRenderWorld(event);
            }
        }
    }

    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onRenderOverlay(event);
            }
        }
    }

    public int getOutboundPacketDelay(Packet<?> packet) {
        int delay = 0;
        for (Module module : modules) {
            if (!module.isEnabled()) {
                continue;
            }

            delay = Math.max(delay, module.getOutboundPacketDelay(packet));
        }
        return delay;
    }

    public void onOutboundPacket(Packet<?> packet) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onOutboundPacket(packet);
            }
        }
    }

    public void onInboundPacket(Packet<?> packet) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onInboundPacket(packet);
            }
        }
    }

    public int getInboundPacketDelay(Packet<?> packet) {
        int delay = 0;
        for (Module module : modules) {
            if (!module.isEnabled()) {
                continue;
            }

            delay = Math.max(delay, module.getInboundPacketDelay(packet));
        }
        return delay;
    }

    public boolean isPacketDelayActive() {
        for (Module module : modules) {
            if (module.isEnabled() && module.isPacketDelayActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean isOutboundPacketDelayActive() {
        for (Module module : modules) {
            if (module.isEnabled() && module.isOutboundPacketDelayActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean isInboundPacketDelayActive() {
        for (Module module : modules) {
            if (module.isEnabled() && module.isInboundPacketDelayActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean consumeFlushRequest() {
        for (Module module : modules) {
            if (module.consumeFlushRequest()) {
                return true;
            }
        }
        return false;
    }

    public boolean consumeOutboundFlushRequest() {
        for (Module module : modules) {
            if (module.consumeOutboundFlushRequest()) {
                return true;
            }
        }
        return false;
    }

    public boolean consumeInboundFlushRequest() {
        for (Module module : modules) {
            if (module.consumeInboundFlushRequest()) {
                return true;
            }
        }
        return false;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public void refreshConfigModule() {
        configModule.rebuildSettings();
    }
}
