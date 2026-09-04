package com.fnfmod.session;

import net.minecraft.server.level.ServerLevel;

/** Exact server weather snapshot, including all three transition timers. */
public final class WeatherRollback {
    public record Snapshot(int clearTime, int rainTime, int thunderTime,
                           boolean raining, boolean thundering,
                           float rainLevel, float thunderLevel) {}

    interface State {
        int clearTime();
        int rainTime();
        int thunderTime();
        boolean raining();
        boolean thundering();
        float rainLevel();
        float thunderLevel();
        void setWeather(int clearTime, int rainTime, boolean raining, boolean thundering);
        void setThunderTime(int thunderTime);
        void setRainLevel(float level);
        void setThunderLevel(float level);
    }

    private WeatherRollback() {}

    public static Snapshot capture(ServerLevel level) {
        return capture(access(level));
    }

    public static void restore(ServerLevel level, Snapshot snapshot) {
        restore(access(level), snapshot);
    }

    static Snapshot capture(State state) {
        return new Snapshot(state.clearTime(), state.rainTime(), state.thunderTime(),
                state.raining(), state.thundering(), state.rainLevel(), state.thunderLevel());
    }

    static void restore(State state, Snapshot snapshot) {
        if (state == null || snapshot == null) return;
        // setWeatherParameters sets both rain and thunder duration to its second
        // argument. Restore thunder separately because vanilla stores independent timers.
        state.setWeather(snapshot.clearTime(), snapshot.rainTime(),
                snapshot.raining(), snapshot.thundering());
        state.setThunderTime(snapshot.thunderTime());
        state.setRainLevel(snapshot.rainLevel());
        state.setThunderLevel(snapshot.thunderLevel());
    }

    private static State access(ServerLevel level) {
        net.minecraft.world.level.storage.ServerLevelData data =
                (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
        return new State() {
            @Override public int clearTime() { return data.getClearWeatherTime(); }
            @Override public int rainTime() { return data.getRainTime(); }
            @Override public int thunderTime() { return data.getThunderTime(); }
            @Override public boolean raining() { return level.isRaining(); }
            @Override public boolean thundering() { return level.isThundering(); }
            @Override public float rainLevel() { return level.getRainLevel(1.0f); }
            @Override public float thunderLevel() { return level.getThunderLevel(1.0f); }
            @Override public void setWeather(int clear, int rain, boolean raining, boolean thundering) {
                level.setWeatherParameters(clear, rain, raining, thundering);
            }
            @Override public void setThunderTime(int thunder) { data.setThunderTime(thunder); }
            @Override public void setRainLevel(float value) { level.setRainLevel(value); }
            @Override public void setThunderLevel(float value) { level.setThunderLevel(value); }
        };
    }
}
