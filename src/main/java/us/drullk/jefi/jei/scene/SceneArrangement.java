package us.drullk.jefi.jei.scene;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;

import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Turns a recipe plus a choice of cycling alternatives into the blocks one scene draws.
 *
 * <p>Whatever is drawn in a flowing form gets a full source block of the same fluid placed on its far side:
 * vanilla only uses the flowing top texture while the flow vector is non-zero, which needs a higher fluid to
 * flow from. The far side of the source position is the one opposite the neighbor and the far side of the
 * neighbor position the one away from the source, so a row of up to four blocks reads outwards in both
 * directions: neighbor source, flowing neighbor, flowing source, source.
 *
 * <p>That flowing display shows a fluid arriving sideways from a fed source, which has no vertical analogue: a
 * fluid never flows upwards, so a feed block below a flowing one would not read as feeding it. A recipe whose
 * neighbor offset is vertical is therefore drawn as two still blocks and nothing else, and
 * {@link #neighborIndex} picks the alternative that matches.
 */
public final class SceneArrangement {
    /** Fluid level of a block drawn in its flowing form; probing uses a full flow, this only has to read as one. */
    public static final int DISPLAY_FLOW_LEVEL = 1;

    private SceneArrangement() {
    }

    /** Everything to place for one variant of a recipe, keyed by offset from the source position. */
    public static Map<BlockPos, Placement> of(FluidInteractionRecipe recipe, SceneVariant variant) {
        Map<BlockPos, Placement> scene = new LinkedHashMap<>();

        BlockPos neighborPos = recipe.neighborOffset();
        boolean vertical = neighborPos.getY() != 0;

        List<Fluid> sources = recipe.sourceFluids();
        if (!sources.isEmpty()) {
            Fluid fluid = sources.get(Math.floorMod(variant.source(), sources.size()));
            scene.put(BlockPos.ZERO, Placement.ofFluid(vertical ? fluid.defaultFluidState() : sourceForm(recipe, fluid)));
        }
        List<Placement> neighbors = recipe.neighbors();
        if (!neighbors.isEmpty()) {
            Placement neighbor = neighbors.get(Math.floorMod(variant.neighbor(), neighbors.size()));
            scene.put(neighborPos, vertical ? stillForm(neighbor) : displayForm(neighbor));
        }
        scene.putAll(recipe.conditions());

        if (!vertical) {
            feed(scene, BlockPos.ZERO, BlockPos.ZERO.subtract(neighborPos));
            feed(scene, neighborPos, neighborPos);
        }

        if (variant.after()) {
            recipe.results().forEach((pos, state) -> scene.put(pos, Placement.ofBlock(state)));
        }
        return scene;
    }

    /** Index of the source fluid a slot is currently displaying, or 0 when it shows something unexpected. */
    public static int sourceIndex(FluidInteractionRecipe recipe, @Nullable ITypedIngredient<?> displayed) {
        if (displayed == null || !(displayed.getIngredient() instanceof FluidStack stack)) {
            return 0;
        }
        List<Fluid> sources = recipe.sourceFluids();
        for (int i = 0; i < sources.size(); i++) {
            if (still(sources.get(i)) == still(stack.getFluid())) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Index of the neighbor alternative a slot is currently displaying, or 0 when it shows something unexpected.
     *
     * <p>A JEI slot only ever shows a still fluid, so both forms of one fluid look the same in it. When a recipe
     * holds both, the preferred one is whichever form the scene draws at the neighbor position: the flowing one
     * beside the source, the still one above or below it. Either was verified by the probe.
     */
    public static int neighborIndex(FluidInteractionRecipe recipe, @Nullable ITypedIngredient<?> displayed) {
        if (displayed == null) {
            return 0;
        }
        boolean preferFlowing = recipe.neighborOffset().getY() == 0;
        Object ingredient = displayed.getIngredient();
        List<Placement> neighbors = recipe.neighbors();
        int match = 0;
        boolean matched = false;
        for (int i = 0; i < neighbors.size(); i++) {
            Placement neighbor = neighbors.get(i);
            if (!shows(neighbor, ingredient)) {
                continue;
            }
            if (neighbor.isFlowing() == preferFlowing) {
                return i;
            }
            if (!matched) {
                match = i;
                matched = true;
            }
        }
        return match;
    }

    private static boolean shows(Placement placement, Object ingredient) {
        if (ingredient instanceof FluidStack stack) {
            return placement.isFluid() && FluidInteractionRecipe.stillForm(placement.effectiveFluid()) == still(stack.getFluid());
        }
        if (ingredient instanceof ItemStack stack) {
            ItemStack item = placement.asItem();
            return !item.isEmpty() && ItemStack.isSameItem(item, stack);
        }
        return false;
    }

    /**
     * The placement to draw for a probed alternative: a fluid verified in flowing form is redrawn at the display
     * flow level, everything else is drawn as probed.
     */
    private static Placement displayForm(Placement placement) {
        if (!placement.isFlowing() || !(placement.effectiveFluid().getType() instanceof FlowingFluid flowing)) {
            return placement;
        }
        return Placement.ofFluid(flowing.getFlowing().defaultFluidState()
                .trySetValue(FlowingFluid.LEVEL, DISPLAY_FLOW_LEVEL)
                .trySetValue(FlowingFluid.FALLING, false));
    }

    /** The placement to draw where no flow can be shown: a fluid in its still form, everything else as probed. */
    private static Placement stillForm(Placement placement) {
        if (!placement.isFlowing()) {
            return placement;
        }
        return Placement.ofFluid(FluidInteractionRecipe.stillForm(placement.effectiveFluid()).defaultFluidState());
    }

    /** The state to draw at the source position: flowing when the interaction matched a flowing source. */
    private static FluidState sourceForm(FluidInteractionRecipe recipe, Fluid fluid) {
        if (recipe.matchesFlowingForm() && fluid instanceof FlowingFluid flowing) {
            return flowing.getFlowing().defaultFluidState().trySetValue(FlowingFluid.LEVEL, DISPLAY_FLOW_LEVEL).trySetValue(FlowingFluid.FALLING, false);
        }
        return fluid.defaultFluidState();
    }

    /** Places a source block of the same fluid one step further along {@code away} when the position flows. */
    private static void feed(Map<BlockPos, Placement> scene, BlockPos pos, BlockPos away) {
        Placement placement = scene.get(pos);
        if (placement == null) {
            return;
        }
        FluidState fluid = placement.effectiveFluid();
        if (fluid.isEmpty() || fluid.isSource()) {
            return;
        }
        BlockPos feedPos = pos.offset(away);
        if (scene.containsKey(feedPos)) {
            return;
        }
        scene.put(feedPos, Placement.ofFluid(FluidInteractionRecipe.stillForm(fluid).defaultFluidState()));
    }

    private static Fluid still(Fluid fluid) {
        return fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
    }
}
