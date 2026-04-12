package com.lionclient.feature.setting;

public final class ActionSetting extends Setting {
    private final Runnable action;
    private final ValueProvider valueProvider;

    public ActionSetting(String name, Runnable action, ValueProvider valueProvider) {
        super(name);
        this.action = action;
        this.valueProvider = valueProvider;
    }

    public void run() {
        action.run();
    }

    @Override
    public String getValueText() {
        return valueProvider == null ? "RUN" : valueProvider.get();
    }

    public interface ValueProvider {
        String get();
    }
}
