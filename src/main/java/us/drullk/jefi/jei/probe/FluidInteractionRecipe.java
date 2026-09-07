package us.drullk.jefi.jei.probe;

import java.util.LinkedHashMap;
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
 * One displayable outcome of a registered fluid interaction, discovered by probing it in a sandbox level.
 *
 * <p>All positions are offsets relative to the source fluid's position. A recipe either describes a successful
 * probe (source states, what must surround the source, and what gets written) or, when {@link #failure} is set,
 * records that the interaction exists but could not be exercised.
 *
 * @param sourceType the fluid type the interaction is registered against
 * @param index      the interaction's index within that type's registration list
 * @param id         a stable unique id for bookmarks and JEI's recipe lookups
 * @param sources    source fluid states that produced this exact outcome (source and/or flowing forms)
 * @param neighbors  alternatives for the tested neighbor position; any one of them triggers the interaction
 * @param conditions additional positions that must hold a specific block or fluid (found by multi-position search)
 * @param results    blocks written by the interaction, keyed by offset; normally just the source position
 * @param failure    non-null when the interaction could not be processed; the other collections are then empty
 * @param owner      mod id credited with registering the interaction, or null when none could be derived
 */
public record FluidInteractionRecipe(
        FluidType sourceType,
        int index,
        ResourceLocation id,
        List<FluidState> sources,
        List<Placement> neighbors,
        Map<BlockPos, Placement> conditions,
        Map<BlockPos, BlockState> results,
        @Nullable Component failure,
        @Nullable String owner) {

    /** The neighbor position used when probing, relative to the source. */
    public static final BlockPos NEIGHBOR_OFFSET = new BlockPos(0, 0, -1);

    public static FluidInteractionRecipe failed(FluidType type, int index, ResourceLocation id, Component reason, @Nullable String owner) {
        return new FluidInteractionRecipe(type, index, id, List.of(), List.of(), Map.of(), Map.of(), reason, owner);
    }

    public boolean isFailure() {
        return failure != null;
    }

    /** Distinct still fluids among {@link #sources}, for the JEI input slot; JEI only knows still fluids. */
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

    /**
     * Everything to place for a rendered scene, relative to the source position.
     *
     * @param after when true, the interaction's results are applied on top of the starting arrangement
     */
    public Map<BlockPos, Placement> scene(boolean after) {
        Map<BlockPos, Placement> scene = new LinkedHashMap<>();
        if (!sources.isEmpty()) {
            scene.put(BlockPos.ZERO, Placement.ofFluid(sources.getFirst()));
        }
        if (!neighbors.isEmpty()) {
            scene.put(NEIGHBOR_OFFSET, neighbors.getFirst());
        }
        scene.putAll(conditions);
        if (after) {
            results.forEach((pos, state) -> scene.put(pos, Placement.ofBlock(state)));
        }
        return scene;
    }
}
