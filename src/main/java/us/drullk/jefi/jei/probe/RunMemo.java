package us.drullk.jefi.jei.probe;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.jei.sandbox.SandboxLevel;

import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * What the runs already made at one target say about the runs to come. Between two candidates at one target,
 * the level differs only at that target. A hook that never read the target gives every candidate the same
 * answer. A hook that read only the fluid state there gives the same answer to every candidate with that fluid
 * state. A hook that read the block state there gets a run of its own for every candidate. The sandbox reports
 * what a run read at the target through {@link SandboxLevel#watchedReads()}.
 *
 * @param <V> the answer of a run
 */
final class RunMemo<V> {
    private static final FluidState NO_FLUID = Fluids.EMPTY.defaultFluidState();

    private final Map<FluidState, V> byFluid = new HashMap<>();
    private @Nullable V unread;

    /** The answer an earlier run gives for this candidate, or null when the candidate needs a run of its own. */
    @Nullable V lookup(Placement candidate) {
        if (unread != null) {
            return unread;
        }
        return byFluid.get(fluidOf(candidate));
    }

    /** Records the answer of a run and what the run read at the target. */
    void record(Placement candidate, int reads, V answer) {
        if (reads == 0) {
            unread = answer;
        } else if (reads == SandboxLevel.READ_FLUID) {
            byFluid.putIfAbsent(fluidOf(candidate), answer);
        }
    }

    /** The fluid state the level reports at a candidate's position. A block candidate holds no fluid. */
    private static FluidState fluidOf(Placement candidate) {
        return candidate.fluid() != null ? candidate.fluid() : NO_FLUID;
    }
}
