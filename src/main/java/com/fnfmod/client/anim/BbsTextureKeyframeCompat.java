package com.fnfmod.client.anim;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Restores the texture-keyframe release behavior Blockified characters used
 * before BBS FS 2.6's typed-track refactor.
 *
 * <p>BBS animation states apply form properties for one render and then reset
 * their runtime values. That is useful for ordinary BBS films, but it makes a
 * character's last keyed texture flash back to the form's base texture as soon
 * as a non-looping state ends. Blockified-managed form copies retain only the
 * runtime value of their main/nested {@code texture} properties. A later state
 * can still replace it normally.</p>
 */
public final class BbsTextureKeyframeCompat {
    private static final Set<Object> CONTROLLED_FORMS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private BbsTextureKeyframeCompat() {}

    static synchronized void control(Object form) {
        if (form != null) CONTROLLED_FORMS.add(form);
    }

    static synchronized void release(Object form) {
        if (form != null) CONTROLLED_FORMS.remove(form);
    }

    static synchronized void clear() {
        CONTROLLED_FORMS.clear();
    }

    /** Called by the optional BBS 2.6+ PropertyTrack mixin. */
    public static synchronized boolean keepRuntimeTexture(Object rootForm, Object track) {
        if (rootForm == null || track == null || !CONTROLLED_FORMS.contains(rootForm)) return false;
        String key = String.valueOf(track).trim().toLowerCase(Locale.ROOT);
        return key.equals("texture") || key.endsWith("/texture");
    }
}
