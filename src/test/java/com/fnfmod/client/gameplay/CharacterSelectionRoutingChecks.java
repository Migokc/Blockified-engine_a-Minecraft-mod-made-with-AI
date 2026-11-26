package com.fnfmod.client.gameplay;

import com.fnfmod.client.ClientOptions;

/** Keeps 2D and BBS selection routing aligned across the song handshake and play state. */
public final class CharacterSelectionRoutingChecks {
    private static void check(boolean actual, boolean expected, String message) {
        if (actual != expected) throw new AssertionError(message + ": expected " + expected);
    }

    public static void main(String[] args) {
        ClientOptions options = new ClientOptions();
        options.animationSet = "default";
        options.opponentAnimationSet = "other";
        check(CharacterSelectionRouting.localUsesOpponentChoice(true, options), true,
                "song default stays assigned to chart sides");
        check(CharacterSelectionRouting.localUsesOpponentChoice(false, options), false,
                "player mode uses the Player selection");

        options.animationSet = "my-bbs-form";
        options.animationSetType = ClientOptions.PERFORMER_TYPE_3D;
        check(CharacterSelectionRouting.localUsesOpponentChoice(true, options), false,
                "explicit BBS form follows the human onto Dad's side");
        check(CharacterSelectionRouting.player2DFollowsOpponentSide(options), false,
                "BBS forms do not get sprite-facing inversion");

        options.animationSet = "my-2d-character";
        options.animationSetType = ClientOptions.PERFORMER_TYPE_2D;
        check(CharacterSelectionRouting.localUsesOpponentChoice(true, options), false,
                "explicit 2D character follows the human onto Dad's side");
        check(CharacterSelectionRouting.player2DFollowsOpponentSide(options), true,
                "only explicit 2D characters get sprite-facing inversion");

        options.animationSet = "none";
        check(CharacterSelectionRouting.localUsesOpponentChoice(true, options), true,
                "disabled Player selection does not take over Dad's side");
        System.out.println("2D/BBS character selection routing checks passed.");
    }
}
