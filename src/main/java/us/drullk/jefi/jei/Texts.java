package us.drullk.jefi.jei;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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

    public static MutableComponent forms(FluidInteractionRecipe recipe) {
        boolean source = recipe.matchesSourceForm();
        boolean flowing = recipe.matchesFlowingForm();
        if (source && flowing) {
            return key("form.both");
        }
        return key(flowing ? "form.flowing" : "form.source");
    }
}
