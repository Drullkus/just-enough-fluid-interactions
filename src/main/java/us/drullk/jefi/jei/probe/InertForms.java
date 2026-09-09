package us.drullk.jefi.jei.probe;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Forms that a {@link SpreadProber} arrangement was also tried with and that wrote nothing: source states of the
 * recipe's own fluid, and neighbor alternatives whose other form the same sources left as it was.
 *
 * <p>Always {@link #NONE} for a recipe that comes from {@link net.neoforged.neoforge.fluids.FluidInteractionRegistry},
 * where each form of a fluid is a separate registered interaction with a recipe of its own.
 *
 * @param sources   source states tried against every one of the recipe's neighbors with no result
 * @param neighbors neighbor placements tried against every one of the recipe's source states with no result
 */
public record InertForms(List<FluidState> sources, List<Placement> neighbors) {
    public static final InertForms NONE = new InertForms(List.of(), List.of());

    public boolean isEmpty() {
        return sources.isEmpty() && neighbors.isEmpty();
    }

    /** The inert source state of one still fluid, or null when every form of it produced the result. */
    public @Nullable FluidState sourceOf(Fluid still) {
        return sources.stream()
                .filter(state -> FluidInteractionRecipe.stillForm(state) == still)
                .findFirst()
                .orElse(null);
    }

    /** The inert neighbor alternative of one still fluid, or null when every form of it produced the result. */
    public @Nullable Placement neighborOf(Fluid still) {
        return neighbors.stream()
                .filter(placement -> placement.isFluid() && FluidInteractionRecipe.stillForm(placement.effectiveFluid()) == still)
                .findFirst()
                .orElse(null);
    }
}
