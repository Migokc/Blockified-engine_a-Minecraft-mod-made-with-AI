package com.fnfmod.session;

/** Verifies flag/timer restoration without launching a Minecraft world. */
public final class WeatherRollbackChecks {
    private static final class Weather implements WeatherRollback.State {
        int clear, rain, thunder;
        boolean raining, thundering;
        float rainLevel, thunderLevel;
        Weather(int clear, int rain, int thunder, boolean raining, boolean thundering) {
            this.clear = clear; this.rain = rain; this.thunder = thunder;
            this.raining = raining; this.thundering = thundering;
            this.rainLevel = raining ? 1 : 0; this.thunderLevel = thundering ? 1 : 0;
        }
        public int clearTime() { return clear; }
        public int rainTime() { return rain; }
        public int thunderTime() { return thunder; }
        public boolean raining() { return raining; }
        public boolean thundering() { return thundering; }
        public float rainLevel() { return rainLevel; }
        public float thunderLevel() { return thunderLevel; }
        public void setWeather(int clear, int rain, boolean raining, boolean thundering) {
            this.clear = clear; this.rain = rain; this.thunder = rain;
            this.raining = raining; this.thundering = thundering;
        }
        public void setThunderTime(int thunder) { this.thunder = thunder; }
        public void setRainLevel(float value) { rainLevel = value; }
        public void setThunderLevel(float value) { thunderLevel = value; }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Weather weather = new Weather(900, 1200, 3456, true, false);
        WeatherRollback.Snapshot before = WeatherRollback.capture(weather);
        weather.clear = 0; weather.rain = 1; weather.thunder = 2;
        weather.raining = false; weather.thundering = true;
        weather.rainLevel = 0.25f; weather.thunderLevel = 0.75f;
        WeatherRollback.restore(weather, before);
        check(weather.clear == 900, "clear timer");
        check(weather.rain == 1200, "rain timer");
        check(weather.thunder == 3456, "independent thunder timer");
        check(weather.raining && !weather.thundering, "rain/thunder flags");
        check(weather.rainLevel == 1 && weather.thunderLevel == 0, "visual interpolation levels");
        WeatherRollback.restore(weather, null);
        check(weather.thunder == 3456, "null snapshot is harmless");
        System.out.println("Weather rollback flag/timer checks passed.");
    }
}
