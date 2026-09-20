package us.drullk.jefi.jei;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidType;

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
    public static MutableComponent rotate() {
        return Component.translatableWithFallback(PREFIX + ".drag_or_click_to_rotate", "Drag or click to rotate");
    }

    /** The same hint where only clicks reach a scene, which is every layout that cannot take widgets. */
    public static MutableComponent clickToRotate() {
        return Component.translatableWithFallback(PREFIX + ".click_to_rotate", "Click to rotate");
    }

    /** The text of a failure recipe whose interaction the probe cannot run, with the owner when one is known. */
    public static MutableComponent unable(FluidType type, @Nullable String owner) {
        if (owner == null) {
            return key("unable", type.getDescription());
        }
        return key("unable_from", type.getDescription(), owner);
    }

    /**
     * Two sentences in Simplified Technical English: what the interaction changes the source into, given the
     * neighbor beside it, and what the source changes into in the world, or that it does not change.
     */
    public static MutableComponent preempted(FluidState source, @Nullable Placement neighbor, BlockState found, BlockState wrote) {
        Component sourceName = Placement.ofFluid(source).describe();
        Component foundName = found.getBlock().getName();
        Component wroteName = wrote.getBlock().getName();
        boolean unchanged = found.getBlock() == source.createLegacyBlock().getBlock();
        if (neighbor == null) {
            return unchanged
                    ? key("preempted.alone.unchanged", sourceName, wroteName, sourceName)
                    : key("preempted.alone", sourceName, wroteName, sourceName, foundName);
        }
        return unchanged
                ? key("preempted.unchanged", sourceName, neighbor.describe(), wroteName, sourceName)
                : key("preempted", sourceName, neighbor.describe(), wroteName, sourceName, foundName);
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
