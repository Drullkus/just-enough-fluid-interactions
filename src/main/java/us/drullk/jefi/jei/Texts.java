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

    /** Hint on the scene widgets. It carries its own wording, so it reads correctly before the key is translated. */
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
     * Two sentences in Simplified Technical English. The first sentence states what the interaction changes the
     * source into, given the neighbor beside it. The second sentence states what the source changes into in the
     * world, or that it does not change.
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

    /**
     * Describes a position relative to the source fluid in words: "below-the source", "above-west of the
     * source", "2 blocks north of the source". Only a position off every axis and outside the cube around
     * the source falls back to its coordinates.
     */
    public static MutableComponent offset(BlockPos offset) {
        if (offset.equals(BlockPos.ZERO)) {
            return key("offset.source");
        }
        if (offset.equals(FluidInteractionRecipe.NEIGHBOR_OFFSET)) {
            return key("offset.neighbor");
        }
        int x = offset.getX();
        int y = offset.getY();
        int z = offset.getZ();
        Direction direction = Direction.fromDelta(x, y, z);
        if (direction != null) {
            return key("offset." + direction.getSerializedName());
        }
        Direction axis = Direction.fromDelta(Integer.signum(x), Integer.signum(y), Integer.signum(z));
        if (axis != null) {
            return key("offset.blocks", Math.abs(x) + Math.abs(y) + Math.abs(z), key("offset." + axis.getSerializedName()));
        }
        if (Math.abs(x) > 1 || Math.abs(y) > 1 || Math.abs(z) > 1) {
            return key("offset.at", x, y, z);
        }
        MutableComponent side = key("offset." + (z < 0 ? "north" : z > 0 ? "south" : "") + (x < 0 ? "west" : x > 0 ? "east" : ""));
        if (y == 0) {
            return side;
        }
        return key(y > 0 ? "offset.upper" : "offset.lower", side);
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

    /** States that the same spread left a neighbor fluid's other form as it was. */
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
