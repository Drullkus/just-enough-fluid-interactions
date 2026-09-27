package us.drullk.jefi.jei;

import java.util.List;

import com.mojang.serialization.Codec;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.registration.IModIngredientRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** A block without an item, as a JEI ingredient. */
public record ItemlessBlock(BlockState state) {
    public static final IIngredientType<ItemlessBlock> TYPE = () -> ItemlessBlock.class;
    public static final Codec<ItemlessBlock> CODEC = BlockState.CODEC.xmap(ItemlessBlock::new, ItemlessBlock::state);

    /** The ingredient list holds none. A recipe slot still finds their recipes. */
    static void register(IModIngredientRegistration registration) {
        registration.register(TYPE, List.of(), new ItemlessBlockHelper(), new ItemlessBlockRenderer(), CODEC);
    }

    public ResourceLocation key() {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    public Component name() {
        return state.getBlock().getName();
    }
}
