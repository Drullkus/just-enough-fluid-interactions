package us.drullk.jefi.jei.sandbox;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.jei.probe.Placement;
import com.mojang.logging.LogUtils;

import dev.compactmods.gander.level.VirtualLevel;
import it.unimi.dsi.fastutil.Hash;
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
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelDataManager;

/**
 * A throwaway {@link net.minecraft.world.level.Level} used to execute fluid interactions outside any real world.
 *
 * <p>The level builds on Gander's {@link VirtualLevel} for the boilerplate: chunk source, light engine, biome,
 * and no-op sounds and events. It keeps its own block and fluid storage. This storage lets the level report
 * back the exact fluid state a probe placed, not whatever its block turns into. It also lets the level record
 * every read and write.
 *
 * <p>The level has two modes. In quiet mode, a write only lands in storage: no {@code onRemove}, no
 * {@code onPlace}, no neighbor notification, no shape update. A probe tier needs this mode while it calls one
 * hook of one rule by hand and reads what that hook alone wrote. In live mode ({@link #setLive(boolean)}), a
 * write goes through the steps a server level takes. First the old state's {@code onRemove} runs. Then the new
 * state's {@code onPlace} runs. Then neighbor notification runs in vanilla's order, through a
 * {@link CollectingNeighborUpdater}. Then the shape updates run. Scheduled fluid ticks then land in a queue.
 * {@link #settle(int, int)} drains this queue against a virtual game time.
 *
 * <p>The level reports itself as server-side, so interactions that guard on {@code !level.isClientSide} run.
 * Block scheduled ticks need a {@code ServerLevel}, so the level only counts them. The level denies entities.
 * The level does not run random ticks. Neighbor notification skips NeoForge's {@code NeighborNotifyEvent}, so
 * no event of a mod fires while the probe runs recipes.
 */
public final class SandboxLevel extends VirtualLevel {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Everything happens inside one chunk so Gander's bakery only visits a single chunk column. */
    public static final AABB DEFAULT_BOUNDS = new AABB(0, 0, 0, 16, 16, 16);

    /** Where a probe puts the source fluid: inside the bounds, with room on every side. */
    public static final BlockPos ORIGIN = new BlockPos(7, 4, 7);

    /** What a server level allows. Gander builds its own updater with a limit of zero. That limit skips every update. */
    private static final int MAX_CHAINED_NEIGHBOR_UPDATES = 1_000_000;

    /** Seed for keeping level simulations deterministic */
    private static final long SEED = new BigInteger("JEFI".getBytes(StandardCharsets.US_ASCII)).longValue();

    /**
     * The number of writes a quiet hook run stays under. A run that writes more than this, such as a settle
     * that lets a fluid pour, has grown the tables past their default capacity.
     */
    private static final int SMALL_RUN = Hash.DEFAULT_INITIAL_SIZE;

    private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<FluidState> fluids = new Long2ObjectOpenHashMap<>();
    private final Set<BlockPos> reads = new LinkedHashSet<>();
    private final Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
    private final NeighborUpdater updater = new CollectingNeighborUpdater(this, MAX_CHAINED_NEIGHBOR_UPDATES);
    private final SandboxTicks<Fluid> fluidTicks = new SandboxTicks<>();
    private final SandboxTicks<Block> blockTicks = new SandboxTicks<>();
    /** A bit of {@link #watchedReads()}: the block state of the watched position was read. */
    public static final int READ_BLOCK = 1;
    /** A bit of {@link #watchedReads()}: the fluid state of the watched position was read. */
    public static final int READ_FLUID = 2;

    private int writesSinceReset;
    private boolean tracking;
    private boolean trackingReads;
    private boolean watching;
    private long watchedKey;
    private int watchedReads;
    private boolean live;
    private long gameTime;
    private int blockTicksRequested;

    public SandboxLevel(RegistryAccess access) {
        super(access, false);
        setBounds(DEFAULT_BOUNDS);
        random.setSeed(SEED);
    }

    // ---- scene authoring -------------------------------------------------------------------------------------

    /**
     * Removes every block, fluid, scheduled tick and recording, and returns the level to its quiet mode. A clear
     * costs a table's whole capacity, not its size, and a table never shrinks on its own. So after a run that
     * grew the tables, this method trims them back, and the clear of every later small run stays small.
     */
    public void reset() {
        boolean grown = writesSinceReset > SMALL_RUN;
        blocks.clear();
        fluids.clear();
        if (grown) {
            blocks.trim(SMALL_RUN);
            fluids.trim(SMALL_RUN);
        }
        writesSinceReset = 0;
        reads.clear();
        writes.clear();
        fluidTicks.clear();
        blockTicks.clear();
        tracking = false;
        trackingReads = false;
        watching = false;
        watchedReads = 0;
        live = false;
        gameTime = 0;
        random.setSeed(SEED);
    }

    public void place(BlockPos pos, Placement placement) {
        if (placement.fluid() != null) {
            placeFluid(pos, placement.fluid());
        } else {
            placeBlock(pos, placement.block());
        }
    }

    public void placeBlock(BlockPos pos, BlockState state) {
        writesSinceReset++;
        long key = pos.asLong();
        fluids.remove(key);
        if (state.isAir()) {
            blocks.remove(key);
        } else {
            blocks.put(key, state);
        }
    }

    /** Places a fluid state through its own block, keeping the state itself as what the level reports there. */
    public void placeFluid(BlockPos pos, FluidState state) {
        placeBlock(pos, state.createLegacyBlock());
        if (!state.isEmpty()) {
            fluids.put(pos.asLong(), state);
        }
    }

    /** Places one part of a settling arrangement the way a level does: a real {@code setBlock}, every flag set. */
    public void placeLive(BlockPos pos, Placement placement) {
        setBlock(pos, placement.block(), Block.UPDATE_ALL);
    }


    // ---- modes and ticking ----------------------------------------------------------------------------------

    /** Whether a write runs the level's own channels. Off while a tier calls one rule's hook by hand. */
    public void setLive(boolean live) {
        this.live = live;
    }

    /**
     * Runs the scheduled fluid ticks the way a server level does. Each round advances the virtual game time to
     * the next due tick. It then collects everything due at that time, in {@link ScheduledTick#DRAIN_ORDER}, and
     * runs the collected batch. A tick scheduled while the batch runs waits for a later round, exactly as in a
     * level. The method stops when nothing is due within {@code maxGameTicks} of where the settle started. It
     * also stops when {@code maxRuns} ticks have run.
     *
     * @return how many fluid ticks ran
     */
    public int settle(int maxGameTicks, int maxRuns) {
        long deadline = gameTime + maxGameTicks;
        int runs = 0;
        while (runs < maxRuns) {
            long next = fluidTicks.nextTrigger();
            if (next > deadline) {
                break;
            }
            gameTime = Math.max(gameTime + 1, next);
            for (ScheduledTick<Fluid> tick : fluidTicks.drain(gameTime)) {
                if (runs >= maxRuns) {
                    break;
                }
                runs++;
                FluidState state = getFluidState(tick.pos());
                if (!state.is(tick.type())) {
                    continue;
                }
                try {
                    state.tick(this, tick.pos());
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.debug("Ticking {} at {} threw while settling an arrangement",
                            state.getFluidType().getDescriptionId(), tick.pos().toShortString(), e);
                }
            }
        }
        return runs;
    }

    /** The total number of block scheduled ticks requested. None of them can run without a server level. */
    public int blockTicksRequested() {
        return blockTicksRequested;
    }

    // ---- tracking -------------------------------------------------------------------------------------------

    /** Clears the recorded writes and starts recording them. Reads are not recorded. */
    public void beginTracking() {
        beginTracking(false);
    }

    /**
     * Clears the recorded reads and writes and starts recording. A recorded read costs an allocation and a hash
     * per position, and a fluid tick reads dozens of positions. So reads are recorded only when {@code reads} is
     * set, for the one caller that uses the list.
     */
    public void beginTracking(boolean reads) {
        this.reads.clear();
        writes.clear();
        tracking = true;
        trackingReads = reads;
    }

    public void endTracking() {
        tracking = false;
        trackingReads = false;
    }

    /**
     * Positions read through {@link #getBlockState} or {@link #getFluidState}, in first-read order. Empty unless
     * {@link #beginTracking(boolean)} asked for them.
     */
    public List<BlockPos> reads() {
        return reads.isEmpty() ? List.of() : List.copyOf(reads);
    }

    /**
     * Starts to record which kinds of read hit one position. A tier watches the target of a run. Between two
     * candidates at one target, the level differs only there. So a hook that never reads the target gives every
     * candidate the same answer, and a hook that reads only the fluid state there gives the same answer to every
     * candidate with that fluid state.
     */
    public void watch(BlockPos pos) {
        watchedKey = pos.asLong();
        watching = true;
        watchedReads = 0;
    }

    /** The kinds of read that hit the watched position since {@link #watch}: {@link #READ_BLOCK}, {@link #READ_FLUID}. */
    public int watchedReads() {
        return watchedReads;
    }

    /** Positions written through any {@code setBlock} variant, in write order, with the final state written. */
    public Map<BlockPos, BlockState> writes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(writes));
    }

    private void note(BlockPos pos, int kind) {
        if (trackingReads) {
            reads.add(pos.immutable());
        }
        if (watching && pos.asLong() == watchedKey) {
            watchedReads |= kind;
        }
    }

    private BlockState blockAt(BlockPos pos) {
        BlockState state = blocks.get(pos.asLong());
        return state != null ? state : Blocks.AIR.defaultBlockState();
    }

    // ---- Level overrides ------------------------------------------------------------------------------------

    @Override
    public BlockState getBlockState(BlockPos pos) {
        note(pos, READ_BLOCK);
        return blockAt(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        note(pos, READ_FLUID);
        FluidState fluid = fluids.get(pos.asLong());
        return fluid != null ? fluid : blockAt(pos).getFluidState();
    }

    /**
     * Mirrors {@code Level.setBlock}, together with the part of {@code LevelChunk.setBlockState} a level without
     * block entities has. Writing an identical state changes nothing. The old state's {@code onRemove} runs
     * first, as if the block were removed. If that call places a different block there, the write stops.
     * Otherwise the new state's {@code onPlace} runs, before the neighbors hear anything. While the level is
     * quiet, none of this runs. The write is only recorded then.
     */
    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        if (isOutsideBuildHeight(pos)) {
            return false;
        }
        BlockPos at = pos.immutable();
        if (!live) {
            store(at, state);
            return true;
        }
        BlockState old = blockAt(at);
        if (old == state) {
            return false;
        }
        store(at, state);
        boolean moving = (flags & Block.UPDATE_MOVE_BY_PISTON) != 0;
        old.onRemove(this, at, state, moving);
        if (!blockAt(at).is(state.getBlock())) {
            return false;
        }
        state.onPlace(this, at, old, moving);
        markAndNotify(at, old, state, flags, recursionLeft);
        return true;
    }

    private void store(BlockPos pos, BlockState state) {
        placeBlock(pos, state);
        if (tracking) {
            writes.put(pos, state);
        }
    }

    /** {@code Level.markAndNotifyBlock} without the parts only a client or a chunk cares about. */
    private void markAndNotify(BlockPos pos, BlockState old, BlockState state, int flags, int recursionLeft) {
        if (blockAt(pos) != state) {
            return;
        }
        if ((flags & Block.UPDATE_NEIGHBORS) != 0) {
            blockUpdated(pos, old.getBlock());
        }
        if ((flags & Block.UPDATE_KNOWN_SHAPE) == 0 && recursionLeft > 0) {
            int shapeFlags = flags & ~(Block.UPDATE_NEIGHBORS | Block.UPDATE_SUPPRESS_DROPS);
            old.updateIndirectNeighbourShapes(this, pos, shapeFlags, recursionLeft - 1);
            state.updateNeighbourShapes(this, pos, shapeFlags, recursionLeft - 1);
            state.updateIndirectNeighbourShapes(this, pos, shapeFlags, recursionLeft - 1);
        }
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags) {
        return setBlock(pos, state, flags, 512);
    }

    @Override
    public boolean setBlockAndUpdate(BlockPos pos, BlockState state) {
        return setBlock(pos, state, Block.UPDATE_ALL);
    }

    @Override
    public long getGameTime() {
        return gameTime;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return blockTicks;
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return fluidTicks;
    }

    /**
     * Schedules the tick the way a {@code LevelAccessor} does. It uses this level's own virtual game time
     * instead of the shared, never-advancing level data Gander hands every virtual level.
     */
    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
        blockTicksRequested++;
        blockTicks.schedule(new ScheduledTick<>(block, pos, gameTime + delay, priority, nextSubTickCount()));
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay) {
        scheduleTick(pos, block, delay, TickPriority.NORMAL);
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
        fluidTicks.schedule(new ScheduledTick<>(fluid, pos, gameTime + delay, priority, nextSubTickCount()));
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
        scheduleTick(pos, fluid, delay, TickPriority.NORMAL);
    }

    /** There is no entity system here, so drops from a block a fluid destroys go nowhere. */
    @Override
    public boolean addFreshEntity(Entity entity) {
        LOGGER.debug("Denied attempted entity spawn {}", entity.getType());
        return false;
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
        if (live) {
            updater.updateNeighborsAtExceptFromFacing(pos, block, null);
        }
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, Direction skipSide) {
        if (live) {
            updater.updateNeighborsAtExceptFromFacing(pos, block, skipSide);
        }
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, BlockPos fromPos) {
        if (live) {
            updater.neighborChanged(pos, block, fromPos);
        }
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        if (live) {
            updater.neighborChanged(state, pos, block, fromPos, isMoving);
        }
    }

    @Override
    public void neighborShapeChanged(Direction direction, BlockState queried, BlockPos pos, BlockPos offsetPos, int flags, int recursionLevel) {
        if (live) {
            updater.shapeUpdate(direction, queried, pos, offsetPos, flags, recursionLevel);
        }
    }

    @Override
    public void blockUpdated(BlockPos pos, Block block) {
        updateNeighborsAt(pos, block);
    }

    /**
     * One kind's scheduled ticks. They are ordered and deduplicated the way a level's chunk tick containers are:
     * one pending tick per position and type. The queue drains by trigger time, then priority, then scheduling
     * order.
     */
    private static final class SandboxTicks<T> implements LevelTickAccess<T> {
        private final PriorityQueue<ScheduledTick<T>> queue = new PriorityQueue<>(ScheduledTick.DRAIN_ORDER);
        private Map<Slot, ScheduledTick<T>> pending = new HashMap<>();
        private int scheduledSinceClear;

        @Override
        public void schedule(ScheduledTick<T> tick) {
            if (pending.putIfAbsent(new Slot(tick.pos(), tick.type()), tick) == null) {
                queue.add(tick);
                scheduledSinceClear++;
            }
        }

        @Override
        public boolean hasScheduledTick(BlockPos pos, T type) {
            return pending.containsKey(new Slot(pos, type));
        }

        @Override
        public boolean willTickThisTick(BlockPos pos, T type) {
            ScheduledTick<T> tick = pending.get(new Slot(pos, type));
            return tick != null && tick.triggerTick() <= nextTrigger();
        }

        @Override
        public int count() {
            return queue.size();
        }

        long nextTrigger() {
            ScheduledTick<T> next = queue.peek();
            return next != null ? next.triggerTick() : Long.MAX_VALUE;
        }

        List<ScheduledTick<T>> drain(long time) {
            List<ScheduledTick<T>> due = new ArrayList<>();
            while (!queue.isEmpty() && queue.peek().triggerTick() <= time) {
                ScheduledTick<T> tick = queue.poll();
                pending.remove(new Slot(tick.pos(), tick.type()));
                due.add(tick);
            }
            return due;
        }

        /** A clear walks the map's whole table, so a map that a settle grew is replaced, not cleared. */
        void clear() {
            queue.clear();
            if (scheduledSinceClear > SMALL_RUN) {
                pending = new HashMap<>();
            } else {
                pending.clear();
            }
            scheduledSinceClear = 0;
        }

        private record Slot(BlockPos pos, Object type) {
        }
    }
}
