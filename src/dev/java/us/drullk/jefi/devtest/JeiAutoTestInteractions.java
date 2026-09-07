package us.drullk.jefi.devtest;

import us.drullk.jefi.JustEnoughFluidInteractions;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry.InteractionInformation;

/**
 * Development-only fluid interactions registered when the smoke test is enabled, so the JEI category can be
 * checked against cases the bundled mods do not provide: an interaction that can never be exercised (the
 * "Unable to process" fallback), one whose predicate inspects two positions (the multi-position search), and
 * one whose predicate does the work itself and registers an empty action.
 */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class JeiAutoTestInteractions {
    private static final boolean ENABLED = Boolean.getBoolean("justenoughfluidinteractions.jeiautotest");

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
        });
    }
}
