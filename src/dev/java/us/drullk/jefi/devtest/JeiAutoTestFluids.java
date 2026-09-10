package us.drullk.jefi.devtest;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.JustEnoughFluidInteractions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Development-only fluids.
 *
 * <p>{@code hardening_brine}'s spread hardens a lava-tagged fluid directly below it into tuff, and only while the
 * fluid doing the spreading is the source: the flowing form runs the same gate, fails it, and spreads into the
 * position instead. That is the shape {@code JeiAutoTest} needs to see one form recorded as the one that produced
 * the result and the other as inert. It is in no fluid tag of its own, so nothing already registered treats it as
 * water or lava and no existing recipe changes. Vanilla only lets a water-tagged fluid replace a lava-tagged one,
 * so reaching a lava-tagged target needs the {@code canSpreadTo} override below; both forms share it, which is
 * why both forms reach {@code spreadTo} and only one of them writes anything.
 *
 * <p>{@code dyed_water} runs no spread code of its own and is water-tagged by
 * {@code src/dev/resources/data/minecraft/tags/fluid/water.json}, which puts it among the fluids lava turns into
 * stone below itself. {@code JeiAutoTestInteractions} registers an interaction of its own with a lava neighbor,
 * which in a level fires on the block update that placing the lava sends, long before lava's spread tick; it is
 * the fixture for {@code SpreadPreemption}.
 */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID)
public final class JeiAutoTestFluids {
    private static final boolean ENABLED = Boolean.getBoolean("justenoughfluidinteractions.jeiautotest");

    static final String NAME = "hardening_brine";
    static final Block RESULT = Blocks.TUFF;
    static final Component DISPLAY_NAME = Component.literal("Hardening Brine");

    static final String DYED_NAME = "dyed_water";
    static final Component DYED_DISPLAY_NAME = Component.literal("Dyed Water");

    private static final ResourceLocation WATER_STILL = ResourceLocation.withDefaultNamespace("block/water_still");
    private static final ResourceLocation WATER_FLOW = ResourceLocation.withDefaultNamespace("block/water_flow");
    private static final int TINT = 0xFF6FD08C;
    private static final int DYED_TINT = 0xFF4A6FE3;

    private static @Nullable FluidType type;
    private static @Nullable BaseFlowingFluid source;
    private static @Nullable BaseFlowingFluid flowing;
    private static @Nullable LiquidBlock block;

    private static @Nullable FluidType dyedType;
    private static @Nullable BaseFlowingFluid dyedSource;
    private static @Nullable BaseFlowingFluid dyedFlowing;
    private static @Nullable LiquidBlock dyedBlock;

    private JeiAutoTestFluids() {
    }

    static Fluid sourceFluid() {
        return Objects.requireNonNull(source);
    }

    static Fluid dyedSourceFluid() {
        return Objects.requireNonNull(dyedSource);
    }

    static FluidType dyedType() {
        return Objects.requireNonNull(dyedType);
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        if (!ENABLED) {
            return;
        }
        create();
        event.register(NeoForgeRegistries.Keys.FLUID_TYPES, helper -> {
            helper.register(id(NAME), Objects.requireNonNull(type));
            helper.register(id(DYED_NAME), Objects.requireNonNull(dyedType));
        });
        event.register(Registries.FLUID, helper -> {
            helper.register(id(NAME), Objects.requireNonNull(source));
            helper.register(id(NAME + "_flowing"), Objects.requireNonNull(flowing));
            helper.register(id(DYED_NAME), Objects.requireNonNull(dyedSource));
            helper.register(id(DYED_NAME + "_flowing"), Objects.requireNonNull(dyedFlowing));
        });
        event.register(Registries.BLOCK, helper -> {
            helper.register(id(NAME), Objects.requireNonNull(block));
            helper.register(id(DYED_NAME), Objects.requireNonNull(dyedBlock));
        });
    }

    /**
     * A fluid claims its registry holder in its constructor, so none of this can exist before the registration
     * events unfreeze the registries; every registration event finds it already built.
     */
    private static void create() {
        if (type != null) {
            return;
        }
        type = new FluidType(FluidType.Properties.create()) {
            @Override
            public Component getDescription() {
                return DISPLAY_NAME;
            }

            @Override
            public Component getDescription(FluidStack stack) {
                return DISPLAY_NAME;
            }
        };
        BaseFlowingFluid.Properties properties = new BaseFlowingFluid.Properties(() -> type, () -> source, () -> flowing)
                .block(() -> block);
        source = new Source(properties);
        flowing = new Flowing(properties);
        block = new LiquidBlock(source, liquidProperties());

        dyedType = new FluidType(FluidType.Properties.create()) {
            @Override
            public Component getDescription() {
                return DYED_DISPLAY_NAME;
            }

            @Override
            public Component getDescription(FluidStack stack) {
                return DYED_DISPLAY_NAME;
            }
        };
        BaseFlowingFluid.Properties dyedProperties =
                new BaseFlowingFluid.Properties(() -> dyedType, () -> dyedSource, () -> dyedFlowing).block(() -> dyedBlock);
        dyedSource = new BaseFlowingFluid.Source(dyedProperties);
        dyedFlowing = new BaseFlowingFluid.Flowing(dyedProperties);
        dyedBlock = new LiquidBlock(dyedSource, liquidProperties());
    }

    private static BlockBehaviour.Properties liquidProperties() {
        return BlockBehaviour.Properties.of().replaceable().noCollission().strength(100.0F).noLootTable().liquid();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID, path);
    }

    /** Lets both forms reach a lava-tagged fluid below, which vanilla otherwise reserves for water-tagged ones. */
    private static boolean reaches(FluidState toFluidState, Direction direction) {
        return direction == Direction.DOWN && toFluidState.is(FluidTags.LAVA);
    }

    /** Only the source fluid hardens what it lands on; the flowing fluid falls through to plain spreading. */
    private static boolean hardens(Fluid fluid, BlockState blockState, Direction direction) {
        return fluid == source && direction == Direction.DOWN && blockState.getFluidState().is(FluidTags.LAVA);
    }

    private static final class Source extends BaseFlowingFluid.Source {
        private Source(BaseFlowingFluid.Properties properties) {
            super(properties);
        }

        @Override
        protected boolean canSpreadTo(BlockGetter level, BlockPos fromPos, BlockState fromBlockState, Direction direction,
                                      BlockPos toPos, BlockState toBlockState, FluidState toFluidState, Fluid fluid) {
            return reaches(toFluidState, direction)
                    || super.canSpreadTo(level, fromPos, fromBlockState, direction, toPos, toBlockState, toFluidState, fluid);
        }

        @Override
        protected void spreadTo(LevelAccessor level, BlockPos pos, BlockState blockState, Direction direction, FluidState fluidState) {
            if (hardens(this, blockState, direction)) {
                level.setBlock(pos, RESULT.defaultBlockState(), Block.UPDATE_ALL);
                return;
            }
            super.spreadTo(level, pos, blockState, direction, fluidState);
        }
    }

    private static final class Flowing extends BaseFlowingFluid.Flowing {
        private Flowing(BaseFlowingFluid.Properties properties) {
            super(properties);
        }

        @Override
        protected boolean canSpreadTo(BlockGetter level, BlockPos fromPos, BlockState fromBlockState, Direction direction,
                                      BlockPos toPos, BlockState toBlockState, FluidState toFluidState, Fluid fluid) {
            return reaches(toFluidState, direction)
                    || super.canSpreadTo(level, fromPos, fromBlockState, direction, toPos, toBlockState, toFluidState, fluid);
        }

        @Override
        protected void spreadTo(LevelAccessor level, BlockPos pos, BlockState blockState, Direction direction, FluidState fluidState) {
            if (hardens(this, blockState, direction)) {
                level.setBlock(pos, RESULT.defaultBlockState(), Block.UPDATE_ALL);
                return;
            }
            super.spreadTo(level, pos, blockState, direction, fluidState);
        }
    }

    /** Borrows water's textures so the dev fluids render in the scenes without shipping any of their own. */
    @EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID, value = Dist.CLIENT)
    public static final class Client {
        private Client() {
        }

        @SubscribeEvent
        static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
            if (!ENABLED) {
                return;
            }
            event.registerFluidType(waterTextures(TINT), Objects.requireNonNull(type));
            event.registerFluidType(waterTextures(DYED_TINT), Objects.requireNonNull(dyedType));
        }

        private static IClientFluidTypeExtensions waterTextures(int tint) {
            return new IClientFluidTypeExtensions() {
                @Override
                public ResourceLocation getStillTexture() {
                    return WATER_STILL;
                }

                @Override
                public ResourceLocation getFlowingTexture() {
                    return WATER_FLOW;
                }

                @Override
                public int getTintColor() {
                    return tint;
                }
            };
        }
    }
}
