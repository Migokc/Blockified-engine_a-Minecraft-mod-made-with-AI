package com.fnfmod.client.audio;

/** Temporarily silences vanilla background music while Blockified gameplay is active. */
public final class GameplayMusicMute {
    public interface MusicVolumeAccess {
        float musicVolume();
        void setMusicVolume(float volume);
        void updatePlayingMusicVolume(float volume);
    }

    private boolean muted;
    private float savedVolume;

    public void mute(MusicVolumeAccess access) {
        if (access == null) return;
        if (!muted) {
            savedVolume = access.musicVolume();
            muted = true;
        }
        // Update the category itself so tracks created after gameplay starts are
        // also silent, then update anything that was already playing.
        access.setMusicVolume(0f);
        access.updatePlayingMusicVolume(0f);
    }

    public void restore(MusicVolumeAccess access) {
        if (!muted || access == null) return;
        access.setMusicVolume(savedVolume);
        access.updatePlayingMusicVolume(savedVolume);
        muted = false;
    }
}
