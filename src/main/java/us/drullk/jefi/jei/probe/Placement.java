package us.drullk.jefi.jei.probe;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Something occupying one position of a probe scene: a block state, optionally with an explicit fluid state.
 *
 * @param block the block state at the position, air when only a fluid is present
 * @param fluid the fluid state at the position, or {@code null} to derive it from the block
 */
public record Placement(BlockState block, @Nullable FluidState fluid) {

    public static Placement ofBlock(BlockState state) {
        return new Placement(state, null);
    }

    public static Placement ofFluid(FluidState state) {
        return new Placement(state.createLegacyBlock(), state);
    }

    public FluidState effectiveFluid() {
        return fluid != null ? fluid : block.getFluidState();
    }

    public boolean isFluid() {
        return !effectiveFluid().isEmpty();
    }

    /** The item that best represents this placement in a JEI slot, or empty when the block has no item. */
    public ItemStack asItem() {
        if (isFluid()) {
            return ItemStack.EMPTY;
        }
        var item = block.getBlock().asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    public Component describe() {
        if (isFluid()) {
            return effectiveFluid().getFluidType().getDescription();
        }
        return block.getBlock().getName();
    }
}
