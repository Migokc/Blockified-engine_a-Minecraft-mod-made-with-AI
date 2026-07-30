package com.fnfmod.client.gameplay;

import com.fnfmod.block.FunkinMachineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

/**
 * The active song's stage facing, captured once from the Funkin' Machine.
 *
 * <p>Everything on the stage — camera axes, performer tweens, Lua world sprites,
 * the axis gizmo — is oriented from the machine's block facing. Reading that
 * facing from the block every frame is unsafe: a Lua/event tween can move the
 * player past the client's view distance of the machine, unloading the machine
 * chunk on the client. {@code getBlockState} then returns air, the facing falls
 * back to north, and every stage axis silently rotates mid-song.
 *
 * <p>The facing cannot change during a song (the machine block is fixed), so it
 * is captured once while the chunk is still loaded and reused everywhere. This
 * keeps the stage axes stable no matter where a performer is tweened.
 */
public final class StageOrientation {

    private static Direction facing = Direction.NORTH;
    private static boolean captured;

    private StageOrientation() {}

    /** Caches the facing for the current song. Call once the machine is loaded. */
    public static void set(Direction value) {
        facing = value == null ? Direction.NORTH : value;
        captured = true;
    }

    /** Clears the cache when gameplay ends. */
    public static void clear() {
        facing = Direction.NORTH;
        captured = false;
    }

    /** The captured stage facing (north until {@link #set} is called). */
    public static Direction facing() {
        return facing;
    }

    /** Whether a facing has been captured this song (vs. the north default). */
    public static boolean isCaptured() {
        return captured;
    }

    /**
     * The cached facing, capturing it from the block on first use if it has not
     * been set yet and the machine chunk is loaded. Once cached, the block is
     * never read again, so an unloaded machine chunk can no longer rotate axes.
     */
    public static Direction facingOr(BlockGetter level, BlockPos machine) {
        if (captured) return facing;
        if (level != null && machine != null) {
            var state = level.getBlockState(machine);
            if (state.hasProperty(FunkinMachineBlock.FACING)) {
                set(state.getValue(FunkinMachineBlock.FACING));
            }
        }
        return facing;
    }
}
