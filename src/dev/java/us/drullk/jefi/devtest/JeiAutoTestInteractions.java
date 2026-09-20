package us.drullk.jefi.devtest;

import java.util.List;

import us.drullk.jefi.JustEnoughFluidInteractions;

import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry.InteractionInformation;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Development-only fluid interactions. The smoke test registers them when it is enabled. The smoke test uses
 * them to check the JEI category against cases the bundled mods do not provide. One interaction can never be
 * exercised, for the "Unable to process" fallback. One interaction's predicate inspects two positions, for the
 * multi-position search. One interaction's predicate does the work itself and registers an empty action. One
 * set of interactions must collapse into a single recipe, through both of {@code RecipeMerger}'s passes. One
 * interaction is registered on a water-tagged fluid for a lava neighbor. A level runs it before lava's spread
 * tick, so settling leaves that fluid out of vanilla's stone recipe. One interaction is registered on lava for
 * a water neighbor. A level answers it with the interaction registered before it.
 */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID)
public final class JeiAutoTestInteractions {
    private static final boolean ENABLED = AutoTestWorld.FIXTURES;

    /** Neighbors of the mergeable interactions. Each one is registered for every type in {@link #MERGE_TYPES}. */
    static final List<Block> MERGE_NEIGHBORS = List.of(Blocks.HAY_BLOCK, Blocks.DRIED_KELP_BLOCK);
    static final List<Holder<FluidType>> MERGE_TYPES = List.of(NeoForgeMod.WATER_TYPE, NeoForgeMod.LAVA_TYPE);
    static final Block MERGE_RESULT = Blocks.MOSS_BLOCK;

    /** The block the last-registered lava interaction writes. A level never holds this block, because an earlier interaction answers first. */
    static final Block PREEMPTED_RESULT = Blocks.BLACKSTONE;

    /** Results of the dev fluid's own lava interaction, one per source form, as a colored water mod registers them. */
    static final Block DYED_SOURCE_RESULT = Blocks.BLUE_TERRACOTTA;
    static final Block DYED_FLOWING_RESULT = Blocks.CYAN_TERRACOTTA;

    /** Written at the source when the dev fluid waterlogs {@link #OFFSET_CONDITION} beside it. */
    static final Block OFFSET_RESULT = Blocks.POLISHED_TUFF;
    /** A waterloggable block whose {@code offsetType} is {@code XZ}, not {@code NONE} (checked against 1.21.1's {@code Blocks}). */
    static final Block OFFSET_CONDITION = Blocks.POINTED_DRIPSTONE;

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
            // Water beside a bone block becomes clay. The predicate writes it, leaving nothing for the action.
            FluidInteractionRegistry.addInteraction(NeoForgeMod.WATER_TYPE.value(), new InteractionInformation(
                    (level, pos, relativePos, state) -> {
                        if (!level.getBlockState(relativePos).is(Blocks.BONE_BLOCK)) {
                            return false;
                        }
                        level.setBlock(pos, Blocks.CLAY.defaultBlockState(), Block.UPDATE_ALL);
                        return true;
                    },
                    (level, pos, relativePos, state) -> {}));
            // The dev water-tagged fluid reacts to lava beside it, with a different block per source form. A
            // level runs this on the block update that placing the lava sends, before lava's spread tick reaches it.
            FluidInteractionRegistry.addInteraction(JeiAutoTestFluids.dyedType(), new InteractionInformation(
                    (level, pos, relativePos, state) -> level.getFluidState(relativePos).getFluidType() == NeoForgeMod.LAVA_TYPE.value(),
                    (level, pos, relativePos, state) -> level.setBlock(pos,
                            (state.isSource() ? DYED_SOURCE_RESULT : DYED_FLOWING_RESULT).defaultBlockState(), Block.UPDATE_ALL)));
            // Same neighbors and result for two fluid types: both merge passes must collapse these into one recipe.
            for (Holder<FluidType> type : MERGE_TYPES) {
                for (Block neighbor : MERGE_NEIGHBORS) {
                    FluidInteractionRegistry.addInteraction(type.value(), new InteractionInformation(
                            (level, pos, relativePos, state) -> level.getBlockState(relativePos).is(neighbor),
                            MERGE_RESULT.defaultBlockState()));
                }
            }
            // The dev water-tagged fluid beside a pointed dripstone waterlogs it with plain water, not with
            // itself. The dripstone is a random-offset block that shares its cell with a fluid, the fixture for
            // the scene renderer. A settled level keeps this write, because the fluid it holds is not one the
            // arrangement itself poured.
            FluidInteractionRegistry.addInteraction(JeiAutoTestFluids.dyedType(), new InteractionInformation(
                    (level, pos, relativePos, state) -> level.getBlockState(relativePos).is(OFFSET_CONDITION),
                    (level, pos, relativePos, state) -> {
                        level.setBlock(pos, OFFSET_RESULT.defaultBlockState(), Block.UPDATE_ALL);
                        level.setBlock(relativePos,
                                OFFSET_CONDITION.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_ALL);
                    }));
            // This interaction is for lava with water beside it. The interaction registered on lava before this
            // one answers first, so every arrangement settles as that one's result. This one's failure recipe
            // states what stands there instead.
            FluidInteractionRegistry.addInteraction(NeoForgeMod.LAVA_TYPE.value(), new InteractionInformation(
                    (level, pos, relativePos, state) -> level.getFluidState(relativePos).getFluidType() == NeoForgeMod.WATER_TYPE.value(),
                    PREEMPTED_RESULT.defaultBlockState()));
        });
    }
}
