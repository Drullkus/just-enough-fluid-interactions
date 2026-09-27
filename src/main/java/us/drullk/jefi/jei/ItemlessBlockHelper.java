package us.drullk.jefi.jei;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.subtypes.UidContext;
import net.minecraft.resources.ResourceLocation;

final class ItemlessBlockHelper implements IIngredientHelper<ItemlessBlock> {
    @Override
    public IIngredientType<ItemlessBlock> getIngredientType() {
        return ItemlessBlock.TYPE;
    }

    @Override
    public String getDisplayName(ItemlessBlock ingredient) {
        return ingredient.name().getString();
    }

    /** One uid per block, so every state of a block finds the same recipes. */
    @SuppressWarnings("removal")
    @Override
    public String getUniqueId(ItemlessBlock ingredient, UidContext context) {
        return "block:" + ingredient.key();
    }

    @Override
    public ResourceLocation getResourceLocation(ItemlessBlock ingredient) {
        return ingredient.key();
    }

    @Override
    public ItemlessBlock copyIngredient(ItemlessBlock ingredient) {
        return ingredient;
    }

    @Override
    public String getErrorInfo(ItemlessBlock ingredient) {
        return ingredient == null ? "null" : ingredient.state().toString();
    }
}
