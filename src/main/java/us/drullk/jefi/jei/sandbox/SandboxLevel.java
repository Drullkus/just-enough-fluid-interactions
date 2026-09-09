package us.drullk.jefi.jei.sandbox;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.probe.Placement;

import dev.compactmods.gander.level.VirtualLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelDataManager;

/**
 * A throwaway {@link net.minecraft.world.level.Level} used to execute fluid interactions outside any real world.
 *
 * <p>Built on Gander's {@link VirtualLevel} for all the boilerplate (chunk source, light engine, biome, no-op
 * sounds and events), but with its own block and fluid storage so that:
 * <ul>
 *     <li>placing a block never fires {@code onPlace}, which would otherwise run the real fluid interaction
 *     before it can be observed;</li>
 *     <li>a fluid state can be present without a block, so bucket-only fluids can still be probed;</li>
 *     <li>every read and write can be recorded while an interaction predicate or action runs.</li>
 * </ul>
 *
 * <p>Neighbor updates are swallowed so an interaction cannot recursively trigger further interactions.
 * The level reports itself as server-side so interactions that guard on {@code !level.isClientSide} run.
 */
public final class SandboxLevel extends VirtualLevel {
    /** Everything happens inside one chunk so Gander's bakery only visits a single chunk column. */
    public static final AABB DEFAULT_BOUNDS = new AABB(0, 0, 0, 16, 16, 16);

    private final Long2ObjectMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<FluidState> fluids = new Long2ObjectOpenHashMap<>();
    private final Set<BlockPos> reads = new LinkedHashSet<>();
    private final Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
    private boolean tracking;

    public SandboxLevel(RegistryAccess access) {
        super(access, false);
        setBounds(DEFAULT_BOUNDS);
    }

    // ---- scene authoring -------------------------------------------------------------------------------------

    /** Removes every block, fluid and recording. */
    public void reset() {
        blocks.clear();
        fluids.clear();
        reads.clear();
        writes.clear();
        tracking = false;
    }

    public void place(BlockPos pos, Placement placement) {
        if (placement.fluid() != null) {
            placeFluid(pos, placement.fluid());
        } else {
            placeBlock(pos, placement.block());
        }
    }

    public void placeBlock(BlockPos pos, BlockState state) {
        long key = pos.asLong();
        fluids.remove(key);
        if (state.isAir()) {
            blocks.remove(key);
        } else {
            blocks.put(key, state);
        }
    }

    /** Places a fluid state, using its legacy block when it has one, and keeps the state even when it has none. */
    public void placeFluid(BlockPos pos, FluidState state) {
        placeBlock(pos, state.createLegacyBlock());
        if (!state.isEmpty()) {
            fluids.put(pos.asLong(), state);
        }
    }

    // ---- tracking -------------------------------------------------------------------------------------------

    /** Clears recorded reads and writes and starts recording. */
    public void beginTracking() {
        reads.clear();
        writes.clear();
        tracking = true;
    }

    public void endTracking() {
        tracking = false;
    }

    /** Positions read through {@link #getBlockState} or {@link #getFluidState}, in first-read order. */
    public List<BlockPos> reads() {
        return List.copyOf(reads);
    }

    /** Positions written through any {@code setBlock} variant, in write order, with the final state written. */
    public Map<BlockPos, BlockState> writes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(writes));
    }

    private void note(BlockPos pos) {
        if (tracking) {
            reads.add(pos.immutable());
        }
    }

    private BlockState blockAt(BlockPos pos) {
        BlockState state = blocks.get(pos.asLong());
        return state != null ? state : Blocks.AIR.defaultBlockState();
    }

    // ---- Level overrides ------------------------------------------------------------------------------------

    @Override
    public BlockState getBlockState(BlockPos pos) {
        note(pos);
        return blockAt(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        note(pos);
        FluidState fluid = fluids.get(pos.asLong());
        return fluid != null ? fluid : blockAt(pos).getFluidState();
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        BlockPos key = pos.immutable();
        placeBlock(key, state);
        if (tracking) {
            writes.put(key, state);
        }
        return true;
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags) {
        return setBlock(pos, state, flags, 512);
    }

    @Override
    public boolean setBlockAndUpdate(BlockPos pos, BlockState state) {
        return setBlock(pos, state, Block.UPDATE_ALL);
    }

    /**
     * Nothing here is ever ticked a second time, and a fluid ticked by the spread probe schedules its own next
     * tick, so scheduling has to end at the sandbox rather than reach Gander's tick lists.
     */
    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay) {
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
    }

    /** There is no entity system here, so drops from a block a fluid destroys go nowhere. */
    @Override
    public boolean addFreshEntity(Entity entity) {
        JustEnoughFluidInteractions.LOGGER.debug("Denied attempted entity spawn {}", entity.getType());
        return false;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean isMoving) {
        return setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
        return removeBlock(pos, false);
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public void setBlockEntity(BlockEntity blockEntity) {
    }

    @Override
    public void removeBlockEntity(BlockPos pos) {
    }

    /** Nothing here has block entities, and the model data manager rejects server-side levels anyway. */
    @Override
    public ModelData getModelData(BlockPos pos) {
        return ModelData.EMPTY;
    }

    @Override
    public @Nullable ModelDataManager getModelDataManager() {
        return null;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) {
            return 1.0F;
        }
        return switch (direction) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case EAST, WEST -> 0.6F;
        };
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return true;
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return true;
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block block) {
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, BlockPos fromPos) {
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
    }

    @Override
    public void neighborShapeChanged(Direction direction, BlockState queried, BlockPos pos, BlockPos offsetPos, int flags, int recursionLevel) {
    }

    @Override
    public void blockUpdated(BlockPos pos, Block block) {
    }
}
