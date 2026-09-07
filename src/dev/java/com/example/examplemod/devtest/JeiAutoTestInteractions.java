package com.example.examplemod.devtest;

import com.example.examplemod.ExampleMod;

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
 * "Unable to process" fallback) and one whose predicate inspects two positions (the multi-position search).
 */
@EventBusSubscriber(modid = ExampleMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class JeiAutoTestInteractions {
    private static final boolean ENABLED = Boolean.getBoolean("examplemod.jeiautotest");

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
        });
    }
}
