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
 * <p>A fluid the recipe verified in its flowing form is drawn flowing, because a flow costs no source block.
 * A vertical pair has one exception. The block the interaction changes keeps that preference. The other block
 * only triggers the change, pouring down or sitting below, so it is drawn as a still source whenever the recipe
 * verified that form. A lava source over flowing water reads as the lava spreading down into the water, and
 * flowing honey over a lava source reads as the honey arriving.
 *
 * <p>Whatever is drawn in a flowing form gets a full source block of the same fluid on one side. Vanilla
 * only shows the flowing top texture while the flow vector is non-zero. That texture needs a higher fluid to
 * flow from. Beside the source, the feed sits on the side opposite the neighbor. Beside the neighbor, it sits
 * on the side away from the source. So a row of up to four blocks reads outward in both directions: neighbor
 * source, flowing neighbor, flowing source, source.
 *
 * <p>A neighbor above or below the source has no far side on the row. A fluid never flows upward, so a feed
 * below a flow does not read as feeding it. A vertical flow is fed from the south instead. When both blocks of
 * the pair flow, the neighbor is fed from the west, so the two feeds never stack into a pair of their own. The
 * default camera looks from the north-east, so neither feed hides the other block.
 */
public final class SceneArrangement {
    /** Fluid level of a block drawn in its flowing form. Probing uses a full flow, a different value than the one assigned here. */
    public static final int DISPLAY_FLOW_LEVEL = 6;

    /** The side a vertical flow is fed from. */
    private static final BlockPos SOUTH = new BlockPos(0, 0, 1);
    /** The side the neighbor of a vertical pair is fed from when the source flows too. */
    private static final BlockPos WEST = new BlockPos(-1, 0, 0);

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
            scene.put(BlockPos.ZERO, Placement.ofFluid(sourceForm(recipe, fluid)));
        }
        List<Placement> neighbors = recipe.neighbors();
        if (!neighbors.isEmpty()) {
            Placement neighbor = neighbors.get(Math.floorMod(variant.neighbor(), neighbors.size()));
            scene.put(neighborPos, displayForm(neighbor));
        }
        scene.putAll(recipe.conditions());

        boolean sourceFed = feed(scene, BlockPos.ZERO, vertical ? SOUTH : BlockPos.ZERO.subtract(neighborPos));
        feed(scene, neighborPos, vertical ? (sourceFed ? WEST : SOUTH) : neighborPos);

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
     * holds both, the scene draws the form {@link #prefersFlow} names. The probe verified either form.
     */
    public static int neighborIndex(FluidInteractionRecipe recipe, @Nullable ITypedIngredient<?> displayed) {
        if (displayed == null) {
            return 0;
        }
        boolean preferFlowing = prefersFlow(recipe, recipe.neighborOffset());
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
     * The placement to draw for a probed alternative. The scene redraws a fluid verified in flowing form at the
     * display flow level. It draws everything else as probed.
     */
    private static Placement displayForm(Placement placement) {
        if (!placement.isFlowing() || !(placement.effectiveFluid().getType() instanceof FlowingFluid flowing)) {
            return placement;
        }
        return Placement.ofFluid(flowing.getFlowing().defaultFluidState()
                .trySetValue(FlowingFluid.LEVEL, DISPLAY_FLOW_LEVEL)
                .trySetValue(FlowingFluid.FALLING, false));
    }

    /** The state to draw at the source position: flowing when the recipe matched that form and the scene prefers it. */
    private static FluidState sourceForm(FluidInteractionRecipe recipe, Fluid fluid) {
        boolean flow = recipe.matchesFlowingForm() && (prefersFlow(recipe, BlockPos.ZERO) || !recipe.matchesSourceForm());
        if (flow && fluid instanceof FlowingFluid flowing) {
            return flowing.getFlowing().defaultFluidState().trySetValue(FlowingFluid.LEVEL, DISPLAY_FLOW_LEVEL).trySetValue(FlowingFluid.FALLING, false);
        }
        return fluid.defaultFluidState();
    }

    /**
     * Whether the scene draws the flowing form at this position when the recipe verified both forms there. It
     * does beside the source. Above or below the source it does only where the interaction writes a result.
     */
    private static boolean prefersFlow(FluidInteractionRecipe recipe, BlockPos offset) {
        return recipe.neighborOffset().getY() == 0 || recipe.results().containsKey(offset);
    }

    /** Places a source block of the same fluid one step along {@code away} when the position flows. */
    private static boolean feed(Map<BlockPos, Placement> scene, BlockPos pos, BlockPos away) {
        Placement placement = scene.get(pos);
        if (placement == null) {
            return false;
        }
        FluidState fluid = placement.effectiveFluid();
        if (fluid.isEmpty() || fluid.isSource()) {
            return false;
        }
        BlockPos feedPos = pos.offset(away);
        if (scene.containsKey(feedPos)) {
            return false;
        }
        scene.put(feedPos, Placement.ofFluid(FluidInteractionRecipe.stillForm(fluid).defaultFluidState()));
        return true;
    }

    private static Fluid still(Fluid fluid) {
        return fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
    }
}
