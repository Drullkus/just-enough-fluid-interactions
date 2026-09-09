package us.drullk.jefi.jei;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.material.FluidState;

/** Translation helpers for the fluid interaction category. */
public final class Texts {
    public static final String PREFIX = "jei." + JustEnoughFluidInteractions.MODID + ".fluid_interactions";

    private Texts() {
    }

    public static MutableComponent title() {
        return Component.translatable(PREFIX);
    }

    public static MutableComponent key(String suffix, Object... args) {
        return Component.translatable(PREFIX + "." + suffix, args);
    }

    /** Hint on the scene widgets; carries its own wording so it reads correctly before the key is translated. */
    public static MutableComponent drag() {
        return Component.translatableWithFallback(PREFIX + ".drag_to_rotate", "Drag to rotate");
    }

    /** Describes a position relative to the source fluid, such as "below the source". */
    public static MutableComponent offset(BlockPos offset) {
        if (offset.equals(BlockPos.ZERO)) {
            return key("offset.source");
        }
        if (offset.equals(FluidInteractionRecipe.NEIGHBOR_OFFSET)) {
            return key("offset.neighbor");
        }
        Direction direction = Direction.fromDelta(offset.getX(), offset.getY(), offset.getZ());
        if (direction != null) {
            return key("offset." + direction.getSerializedName());
        }
        return key("offset.at", offset.getX(), offset.getY(), offset.getZ());
    }

    /** Names one form of a fluid the way a placement line does: "Flowing Poison", "Poison source block". */
    public static MutableComponent form(FluidState state) {
        Component fluid = state.getFluidType().getDescription();
        return key(state.isSource() ? "form.source_of" : "form.flowing_of", fluid);
    }

    /** States that the recipe's other source form spread over the same neighbors and wrote nothing. */
    public static MutableComponent inertSource(FluidState state) {
        return key("form.inert_source", form(state));
    }

    /** States that the other form of a neighbor fluid was left as it was by the same spread. */
    public static MutableComponent inertNeighbor(Placement placement) {
        return key("form.inert_neighbor", form(placement.effectiveFluid()));
    }

    public static MutableComponent forms(FluidInteractionRecipe recipe) {
        boolean source = recipe.matchesSourceForm();
        boolean flowing = recipe.matchesFlowingForm();
        if (source && flowing) {
            return key("form.both");
        }
        return key(flowing ? "form.flowing" : "form.source");
    }
}
