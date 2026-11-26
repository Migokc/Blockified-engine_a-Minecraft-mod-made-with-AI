package com.fnfmod.client.anim;

/** Standalone checks for the BBS 2.6 sticky-texture compatibility boundary. */
public final class BbsTextureKeyframeCompatChecks {
    private BbsTextureKeyframeCompatChecks() {}

    public static void main(String[] args) {
        Object controlled = new Object();
        Object unrelated = new Object();

        require(!BbsTextureKeyframeCompat.keepRuntimeTexture(controlled, "texture"),
                "unregistered forms must keep normal BBS behavior");
        BbsTextureKeyframeCompat.control(controlled);
        require(BbsTextureKeyframeCompat.keepRuntimeTexture(controlled, "texture"),
                "root texture should remain keyed");
        require(BbsTextureKeyframeCompat.keepRuntimeTexture(controlled, "a1b2c3d4/texture"),
                "nested form texture should remain keyed");
        require(!BbsTextureKeyframeCompat.keepRuntimeTexture(controlled, "transform"),
                "non-texture properties must reset");
        require(!BbsTextureKeyframeCompat.keepRuntimeTexture(controlled, "texture.materials.face"),
                "material tracks keep their own BBS lifecycle");
        require(!BbsTextureKeyframeCompat.keepRuntimeTexture(unrelated, "texture"),
                "ordinary BBS forms must be untouched");

        BbsTextureKeyframeCompat.release(controlled);
        require(!BbsTextureKeyframeCompat.keepRuntimeTexture(controlled, "texture"),
                "released forms must return to normal BBS behavior");
        System.out.println("BBS texture-keyframe compatibility checks passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
