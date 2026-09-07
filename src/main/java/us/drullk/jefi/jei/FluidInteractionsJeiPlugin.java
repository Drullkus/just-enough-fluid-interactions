package us.drullk.jefi.jei;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.InteractionProber;
import us.drullk.jefi.jei.probe.RegisteredInteractions;
import us.drullk.jefi.jei.scene.SceneCache;
import com.mojang.logging.LogUtils;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Shows every {@link FluidInteractionRegistry} entry in JEI.
 *
 * <p>JEI starts after tags and recipes have synced, so a client level and its registry access exist by the time
 * recipes are registered. The interactions themselves are registered by mods during common setup, long before.
 */
@JeiPlugin
public final class FluidInteractionsJeiPlugin implements IModPlugin {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID, "fluid_interactions");
    public static final RecipeType<FluidInteractionRecipe> TYPE = RecipeType.create(JustEnoughFluidInteractions.MODID, "fluid_interaction", FluidInteractionRecipe.class);

    private static volatile @Nullable IJeiRuntime runtime;

    private final SceneCache scenes = new SceneCache();

    /** The JEI runtime while one is active, for tooling that wants to open this plugin's category. */
    public static @Nullable IJeiRuntime runtime() {
        return runtime;
    }

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new FluidInteractionCategory(registration.getJeiHelpers().getGuiHelper(), scenes));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            LOGGER.warn("JEI started without a client level; fluid interactions cannot be probed");
            return;
        }
        List<FluidInteractionRecipe> recipes = new InteractionProber(level.registryAccess()).probeAll();
        registration.addRecipes(TYPE, recipes);
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        Set<Item> buckets = new LinkedHashSet<>();
        for (FluidType type : RegisteredInteractions.get().keySet()) {
            FluidInteractionCategory.stillFluidsOf(type).forEach(fluid -> buckets.add(fluid.getBucket()));
        }
        buckets.remove(Items.AIR);
        buckets.forEach(bucket -> registration.addRecipeCatalyst(bucket, TYPE));
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
        scenes.clear();
    }
}
