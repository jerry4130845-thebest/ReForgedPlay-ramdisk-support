package com.replaymod.core;

public final class Setting<T> extends SettingsRegistry.SettingKeys<T> {
    public static final Setting<Boolean> NOTIFICATIONS = make("notifications", "notifications", true);
    public static final Setting<String> RECORDING_PATH = advanced("recordingPath", null, "./replay_recordings/");
    public static final Setting<String> CACHE_PATH = advanced("cachePath", null, "./.replay_cache/");
    public static final Setting<String> TEMP_PATH = advanced("tempPath", null, "./.replay_temp/");

    public static final Setting<String> TEMP_PATH = advanced("tempPath", null, "");
    public static final Setting<String> RECORDING_TEMP_PATH = advanced("recordingTempPath", null, "");
    public static final Setting<String> RAW_PATH = advanced("rawPath", null, "");

    private static <T> Setting<T> make(String key, String displayName, T defaultValue) {
        return new Setting<>(key, displayName, defaultValue, true);
    }

    private static <T> Setting<T> advanced(String key, String displayName, T defaultValue) {
        return new Setting<>(key, displayName, defaultValue, false);
    }

    public Setting(String key, String displayString, T defaultValue, boolean localized) {
        super("core", key, localized && displayString != null ? "replaymod.gui.settings." + displayString : null, defaultValue);
    }
}
