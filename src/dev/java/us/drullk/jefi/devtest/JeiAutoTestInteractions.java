package us.drullk.jefi.devtest;

import java.util.List;

import us.drullk.jefi.JustEnoughFluidInteractions;

import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry.InteractionInformation;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Development-only fluid interactions registered when the smoke test is enabled, so the JEI category can be
 * checked against cases the bundled mods do not provide: an interaction that can never be exercised (the
 * "Unable to process" fallback), one whose predicate inspects two positions (the multi-position search), one
 * whose predicate does the work itself and registers an empty action, and a set that must collapse into a
 * single recipe through both of {@code RecipeMerger}'s passes.
 */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID)
public final class JeiAutoTestInteractions {
    private static final boolean ENABLED = Boolean.getBoolean("justenoughfluidinteractions.jeiautotest");

    /** Neighbors of the mergeable interactions; each is registered for every type in {@link #MERGE_TYPES}. */
    static final List<Block> MERGE_NEIGHBORS = List.of(Blocks.HAY_BLOCK, Blocks.DRIED_KELP_BLOCK);
    static final List<Holder<FluidType>> MERGE_TYPES = List.of(NeoForgeMod.WATER_TYPE, NeoForgeMod.LAVA_TYPE);
    static final Block MERGE_RESULT = Blocks.MOSS_BLOCK;

    private JeiAutoTestInteractions() {
    }

    @SubscribeEvent
    static void onCommonSetup(FMLCommonSetupEvent event) {
        if (!ENABLED) {
            return;
        }
        event.enqueueWork(() -> {
            // Never fires: shows the fallback recipe.
            FluidInteractionRegistry.addInteraction(NeoForgeMod.WATER_TYPE.value(), new InteractionInformation(
                    (level, pos, relativePos, state) -> false, Blocks.STONE.defaultBlockState()));
            // Water with a magma block above it and sand beside it turns to glass: needs the greedy search.
            FluidInteractionRegistry.addInteraction(NeoForgeMod.WATER_TYPE.value(), new InteractionInformation(
                    (level, pos, relativePos, state) -> level.getBlockState(pos.above()).is(Blocks.MAGMA_BLOCK)
                            && level.getBlockState(relativePos).is(Blocks.SAND),
                    Blocks.GLASS.defaultBlockState()));
            // Water beside a bone block becomes clay, written by the predicate with nothing left for the action.
            FluidInteractionRegistry.addInteraction(NeoForgeMod.WATER_TYPE.value(), new InteractionInformation(
                    (level, pos, relativePos, state) -> {
                        if (!level.getBlockState(relativePos).is(Blocks.BONE_BLOCK)) {
                            return false;
                        }
                        level.setBlock(pos, Blocks.CLAY.defaultBlockState(), Block.UPDATE_ALL);
                        return true;
                    },
                    (level, pos, relativePos, state) -> {}));
            // Same neighbors and result for two fluid types: both merge passes must collapse these into one recipe.
            for (Holder<FluidType> type : MERGE_TYPES) {
                for (Block neighbor : MERGE_NEIGHBORS) {
                    FluidInteractionRegistry.addInteraction(type.value(), new InteractionInformation(
                            (level, pos, relativePos, state) -> level.getBlockState(relativePos).is(neighbor),
                            MERGE_RESULT.defaultBlockState()));
                }
            }
        });
    }
}
