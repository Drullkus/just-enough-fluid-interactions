package us.drullk.jefi.jei.probe;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * One displayable outcome of a registered fluid interaction. A probe in a sandbox level finds it.
 *
 * <p>All positions are offsets from the position of the source fluid. A recipe describes a successful probe: the
 * source states, what must surround the source, and what the probe writes. A recipe with a {@link #failure}
 * instead records that the interaction exists and that no probe ran it.
 *
 * @param sourceType     the fluid type the registry keys the interaction on.
 * @param id             a stable unique id for bookmarks and JEI's recipe lookups.
 * @param sources        source fluid states that give this exact outcome, in source form, flowing form or both.
 * @param neighbors      alternatives for the tested neighbor position. Any one of them starts the interaction.
 * @param neighborOffset where the neighbor sits relative to the source. It is {@link #NEIGHBOR_OFFSET} for
 *                       everything the registry probe finds.
 * @param conditions     more positions that must hold a specific block or fluid. The multi-position search
 *                       finds them.
 * @param results        the blocks the interaction writes, keyed by offset. Usually this is only the source
 *                       position.
 * @param failure        not null when no probe processed the interaction. The other collections are then empty.
 * @param owner          the mod id credited with the registration of the interaction, or null when nothing
 *                       identifies one.
 * @param inert          forms the same spread probe tries at this arrangement with no result. It is empty for
 *                       everything the registry probe finds.
 */
public record FluidInteractionRecipe(
        FluidType sourceType,
        ResourceLocation id,
        List<FluidState> sources,
        List<Placement> neighbors,
        BlockPos neighborOffset,
        Map<BlockPos, Placement> conditions,
        Map<BlockPos, BlockState> results,
        @Nullable Component failure,
        @Nullable String owner,
        InertForms inert) {

    /** The neighbor position the probe hands to registered interactions, as an offset from the source. */
    public static final BlockPos NEIGHBOR_OFFSET = new BlockPos(0, 0, -1);

    public static FluidInteractionRecipe failed(FluidType type, ResourceLocation id, Component reason, @Nullable String owner) {
        return new FluidInteractionRecipe(type, id, List.of(), List.of(), NEIGHBOR_OFFSET, Map.of(), Map.of(), reason, owner, InertForms.NONE);
    }

    public boolean isFailure() {
        return failure != null;
    }

    /** The distinct still fluids among {@link #sources}, for the JEI input slot. JEI knows only still fluids. */
    public List<Fluid> sourceFluids() {
        return sources.stream().map(FluidInteractionRecipe::stillForm).distinct().toList();
    }

    public static Fluid stillForm(FluidState state) {
        Fluid fluid = state.getType();
        return fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
    }

    public boolean matchesSourceForm() {
        return sources.stream().anyMatch(FluidState::isSource);
    }

    public boolean matchesFlowingForm() {
        return sources.stream().anyMatch(s -> !s.isSource());
    }

    public @Nullable BlockState resultAtSource() {
        return results.get(BlockPos.ZERO);
    }

}
