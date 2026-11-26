package com.fnfmod.client.gameplay;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.anim.CharacterAnimations;

/** Routes explicit Player selections with the human while song defaults keep chart sides. */
public final class CharacterSelectionRouting {
    private CharacterSelectionRouting() {}

    public static boolean playerSelectionFollowsOpponentSide(ClientOptions options) {
        return options != null
                && options.animationSet != null
                && !options.animationSet.isBlank()
                && !CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(options.animationSet)
                && !CharacterAnimations.isDisabled(options.animationSet);
    }

    /** Solo gameplay puts the other saved selection on the opposite chart side. */
    public static boolean localUsesOpponentChoice(boolean opponentSide, ClientOptions options) {
        return opponentSide && !playerSelectionFollowsOpponentSide(options);
    }

    /** Only 2D sprite facing needs the extra side-swap inversion. */
    public static boolean player2DFollowsOpponentSide(ClientOptions options) {
        return playerSelectionFollowsOpponentSide(options)
                && ClientOptions.PERFORMER_TYPE_2D.equals(
                        ClientOptions.normalizePerformerType(options.animationSetType));
    }
}
