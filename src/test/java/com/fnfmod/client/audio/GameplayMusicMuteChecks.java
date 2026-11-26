package com.fnfmod.client.audio;

/** Regression check: vanilla music started after gameplay begins must remain muted. */
public final class GameplayMusicMuteChecks {
    private static final class FakeMusic implements GameplayMusicMute.MusicVolumeAccess {
        float categoryVolume = 0.7f;
        float activeTrackVolume = categoryVolume;

        @Override public float musicVolume() { return categoryVolume; }
        @Override public void setMusicVolume(float volume) { categoryVolume = volume; }
        @Override public void updatePlayingMusicVolume(float volume) { activeTrackVolume = volume; }

        void startVanillaTrack() {
            // Minecraft applies the category's configured volume to newly started tracks.
            activeTrackVolume = categoryVolume;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        FakeMusic music = new FakeMusic();
        GameplayMusicMute mute = new GameplayMusicMute();

        mute.mute(music);
        music.startVanillaTrack();
        check(music.activeTrackVolume == 0f,
                "vanilla tracks started during gameplay should be muted");

        mute.restore(music);
        check(Math.abs(music.categoryVolume - 0.7f) < 0.0001f,
                "the user's music volume should be restored after gameplay");
        check(Math.abs(music.activeTrackVolume - 0.7f) < 0.0001f,
                "already-playing vanilla music should be restored after gameplay");
        System.out.println("Gameplay vanilla-music mute checks passed.");
    }
}
