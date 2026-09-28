package us.drullk.jefi.jei;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;
import us.drullk.jefi.jei.scene.SceneArrangement;
import us.drullk.jefi.jei.scene.SceneCache;
import us.drullk.jefi.jei.scene.SceneDrawable;
import us.drullk.jefi.jei.scene.SceneRotation;
import us.drullk.jefi.jei.scene.SceneVariant;
import us.drullk.jefi.jei.scene.SceneView;
import us.drullk.jefi.jei.scene.SceneWidget;
import us.drullk.jefi.jei.scene.SlotLookup;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawablesView;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.placement.HorizontalAlignment;
import mezz.jei.api.gui.placement.IPlaceable;
import mezz.jei.api.gui.placement.VerticalAlignment;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.runtime.IIngredientVisibility;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * JEI category for probed fluid interactions.
 *
 * <p>Two 3D scenes show the arrangement before and after the interaction. One arrow sits between the two scenes.
 * Above the left scene sits the input row: the source fluid, the neighbor, and any extra required blocks,
 * separated by plus signs. Above the right scene sit the results. A recipe whose interaction the probe cannot
 * process shows only the source fluid and an explanation.
 *
 * <p>Each slot of a lockstep recipe holds one entry per row. A focus link joins the slots.
 */
public final class FluidInteractionCategory extends AbstractRecipeCategory<FluidInteractionRecipe> {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int WIDTH = 170;
    public static final int HEIGHT = 100;

    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID, "textures/gui/icon.png");
    private static final int ICON_SIZE = 16;

    private static final int ROW_Y = 6;
    /** Footprint of a slot, including its background. JEI draws the background one pixel outside the ingredient. */
    private static final int SLOT_SIZE = 18;
    private static final int PLUS_GAP = 15;
    private static final int RESULT_GAP = 2;
    private static final int MIN_MARGIN = 4;

    private static final int SCENE_Y = 26;
    private static final int SCENE_SIZE = 68;
    private static final int BEFORE_X = 5;
    private static final int AFTER_X = WIDTH - BEFORE_X - SCENE_SIZE;

    private final SceneCache scenes;
    /** The slot view used to draw each recipe most recently. A static scene reads its alternatives from that view. */
    private final Map<FluidInteractionRecipe, IRecipeSlotsView> drawnSlots = new IdentityHashMap<>();
    /** The orbit angles of the scenes a layout drew as drawables. A click on the category can only turn these angles. */
    private final Map<FluidInteractionRecipe, SceneRotation> staticRotations = new IdentityHashMap<>();
    /** The rows each lockstep recipe's slots hold. */
    private final Map<FluidInteractionRecipe, List<FluidInteractionRecipe>> shownRows = new IdentityHashMap<>();
    private final IJeiHelpers helpers;
    /** True when TMRV is loaded. A TMRV slot does not tell which entry it shows. */
    private final boolean tmrv = ModList.get().isLoaded("toomanyrecipeviewers");

    public FluidInteractionCategory(IJeiHelpers helpers, SceneCache scenes) {
        super(FluidInteractionsJeiPlugin.TYPE, Texts.title(), helpers.getGuiHelper().drawableBuilder(ICON, 0, 0, ICON_SIZE, ICON_SIZE).setTextureSize(ICON_SIZE, ICON_SIZE).build(), WIDTH, HEIGHT);
        this.helpers = helpers;
        this.scenes = scenes;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, FluidInteractionRecipe recipe, IFocusGroup focuses) {
        if (recipe.isFailure()) {
            IRecipeSlotBuilder source = slot(builder.addInputSlot(centered(SLOT_SIZE), ROW_Y)).setSlotName("source");
            stillFluidsOf(recipe.sourceType()).forEach(fluid -> source.addFluidStack(fluid, FluidType.BUCKET_VOLUME));
            return;
        }
        if (recipe.isLockstep()) {
            setRows(builder, recipe);
            return;
        }
        int[] inputs = inputSlotX(recipe);
        int next = 0;
        addSourceSlot(builder, recipe, inputs[next++], recipe.sourceFluids());
        if (!recipe.neighbors().isEmpty()) {
            addPlacements(addNeighborSlot(builder, recipe, inputs[next++]), recipe.neighbors());
        }
        int index = 0;
        for (var condition : recipe.conditions().entrySet()) {
            addPlacements(addConditionSlot(builder, recipe, condition.getKey(), index++, inputs[next++]), List.of(condition.getValue()));
        }
        int[] results = resultSlotX(recipe);
        index = 0;
        for (var result : recipe.results().entrySet()) {
            addPlacements(addResultSlot(builder, result.getKey(), index, results[index++]), List.of(Placement.ofBlock(result.getValue())));
        }
    }

    /** One entry per shown row in every linked slot, and one focus link over the linked slots. */
    private void setRows(IRecipeLayoutBuilder builder, FluidInteractionRecipe recipe) {
        List<FluidInteractionRecipe> rows = visibleRows(visibility(), recipe);
        shownRows.put(recipe, rows);
        List<IRecipeSlotBuilder> slots = new ArrayList<>();
        int[] inputs = inputSlotX(recipe);
        int next = 0;
        if (FluidInteractionRecipe.sharesSources(recipe.rows())) {
            addSourceSlot(builder, recipe, inputs[next++], recipe.rows().getFirst().sourceFluids());
        } else {
            slots.add(addSourceSlot(builder, recipe, inputs[next++], rows.stream().map(row -> row.sourceFluids().getFirst()).toList()));
        }
        if (!recipe.neighbors().isEmpty() && FluidInteractionRecipe.sharesNeighbors(recipe.rows())) {
            addPlacements(addNeighborSlot(builder, recipe, inputs[next++]), recipe.neighbors());
        } else if (!recipe.neighbors().isEmpty()) {
            slots.add(addEntries(addNeighborSlot(builder, recipe, inputs[next++]), rows, row -> row.neighbors().getFirst()));
        }
        int index = 0;
        for (BlockPos offset : recipe.conditions().keySet()) {
            IRecipeSlotBuilder slot = addConditionSlot(builder, recipe, offset, index++, inputs[next++]);
            slots.add(addEntries(slot, rows, row -> row.conditions().get(offset)));
        }
        int[] results = resultSlotX(recipe);
        index = 0;
        for (BlockPos offset : recipe.results().keySet()) {
            IRecipeSlotBuilder slot = addResultSlot(builder, offset, index, results[index++]);
            slots.add(addEntries(slot, rows, row -> Placement.ofBlock(row.results().get(offset))));
        }
        try {
            builder.createFocusLink(slots.toArray(IRecipeSlotBuilder[]::new));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("The recipe layout of {} takes no focus link", recipe.id(), e);
        }
    }

    private @Nullable IIngredientVisibility visibility() {
        try {
            return helpers.getIngredientVisibility();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The rows without a hidden entry in a linked slot. JEI drops a hidden entry from its slot alone. */
    public static List<FluidInteractionRecipe> visibleRows(@Nullable IIngredientVisibility visibility, FluidInteractionRecipe recipe) {
        if (visibility == null) {
            return recipe.rows();
        }
        boolean sources = !FluidInteractionRecipe.sharesSources(recipe.rows());
        boolean neighbors = !FluidInteractionRecipe.sharesNeighbors(recipe.rows());
        List<FluidInteractionRecipe> visible = recipe.rows().stream().filter(row -> isVisible(visibility, row, sources, neighbors)).toList();
        return visible.isEmpty() ? recipe.rows() : visible;
    }

    private static boolean isVisible(IIngredientVisibility visibility, FluidInteractionRecipe row, boolean sources, boolean neighbors) {
        List<Placement> entries = new ArrayList<>();
        if (sources) {
            entries.add(Placement.ofFluid(row.sourceFluids().getFirst().defaultFluidState()));
        }
        if (neighbors && !row.neighbors().isEmpty()) {
            entries.add(row.neighbors().getFirst());
        }
        entries.addAll(row.conditions().values());
        row.results().values().forEach(state -> entries.add(Placement.ofBlock(state)));
        try {
            return entries.stream().allMatch(entry -> isVisible(visibility, entry));
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    private static boolean isVisible(IIngredientVisibility visibility, Placement placement) {
        if (placement.isFluid()) {
            Fluid fluid = FluidInteractionRecipe.stillForm(placement.effectiveFluid());
            return visibility.isIngredientVisible(NeoForgeTypes.FLUID_STACK, new FluidStack(fluid, FluidType.BUCKET_VOLUME));
        }
        ItemStack item = placement.asItem();
        return item.isEmpty()
                ? visibility.isIngredientVisible(ItemlessBlock.TYPE, new ItemlessBlock(placement.block()))
                : visibility.isIngredientVisible(VanillaTypes.ITEM_STACK, item);
    }

    /** The source slot cycles the still fluids. Its tooltip names the forms, and the inert form when one exists. */
    private static IRecipeSlotBuilder addSourceSlot(IRecipeLayoutBuilder builder, FluidInteractionRecipe recipe, int x, List<Fluid> fluids) {
        boolean sourceFlows = recipe.sources().stream().anyMatch(state -> !state.isSource());
        IRecipeSlotBuilder source = slot(builder.addSlot(role(recipe, BlockPos.ZERO, sourceFlows), x, ROW_Y)).setSlotName(SlotLookup.SOURCE);
        fluids.forEach(fluid -> source.addFluidStack(fluid, FluidType.BUCKET_VOLUME));
        source.addRichTooltipCallback((view, tooltip) -> {
            tooltip.add(Texts.forms(recipe).withStyle(ChatFormatting.GRAY));
            Fluid shown = displayedFluid(view);
            FluidState inert = shown != null ? recipe.inert().sourceOf(shown) : null;
            if (inert != null) {
                tooltip.add(Texts.inertSource(inert).withStyle(ChatFormatting.YELLOW));
            }
        });
        return source;
    }

    /** The neighbor slot cycles the alternatives. Its tooltip names the position, and the inert form when one exists. */
    private static IRecipeSlotBuilder addNeighborSlot(IRecipeLayoutBuilder builder, FluidInteractionRecipe recipe, int x) {
        boolean neighborFlows = recipe.neighbors().stream().anyMatch(Placement::isFlowing);
        IRecipeSlotBuilder neighbor = slot(builder.addSlot(role(recipe, recipe.neighborOffset(), neighborFlows), x, ROW_Y)).setSlotName(SlotLookup.NEIGHBOR);
        neighbor.addRichTooltipCallback((view, tooltip) -> {
            tooltip.add(Texts.offset(recipe.neighborOffset()).withStyle(ChatFormatting.GRAY));
            Fluid shown = displayedFluid(view);
            Placement inert = shown != null ? recipe.inert().neighborOf(shown) : null;
            if (inert != null) {
                tooltip.add(Texts.inertNeighbor(inert).withStyle(ChatFormatting.YELLOW));
            }
        });
        return neighbor;
    }

    private static IRecipeSlotBuilder addConditionSlot(IRecipeLayoutBuilder builder, FluidInteractionRecipe recipe, BlockPos offset, int index, int x) {
        boolean flows = recipe.rowsOrSelf().stream().map(row -> row.conditions().get(offset)).anyMatch(placement -> placement != null && placement.isFlowing());
        IRecipeSlotBuilder slot = slot(builder.addSlot(role(recipe, offset, flows), x, ROW_Y)).setSlotName(SlotLookup.condition(index));
        slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.offset(offset).withStyle(ChatFormatting.GRAY)));
        return slot;
    }

    /** An output slot of the result row. A result away from the source names its position in the tooltip. */
    private static IRecipeSlotBuilder addResultSlot(IRecipeLayoutBuilder builder, BlockPos offset, int index, int x) {
        IRecipeSlotBuilder slot = slot(builder.addOutputSlot(x, ROW_Y)).setSlotName(SlotLookup.result(index));
        if (!offset.equals(BlockPos.ZERO)) {
            slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.offset(offset).withStyle(ChatFormatting.GRAY)));
        }
        return slot;
    }

    /**
     * A builder without a slot view cannot position widgets or route input to them. Its scenes become static
     * drawables. It draws its own text by hand. The plus sign and arrow come from the builder's own methods,
     * not the newer widget methods. The widget-returning methods postdate the oldest supported JEI version.
     */
    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, FluidInteractionRecipe recipe, IFocusGroup focuses) {
        IRecipeSlotDrawablesView slots = builder.getRecipeSlots();
        if (recipe.isFailure()) {
            addFailureText(builder, recipe, slots == null);
            return;
        }
        int[] inputs = inputSlotX(recipe);
        for (int i = 1; i < inputs.length; i++) {
            place(builder.addRecipePlusSign(), inputs[i - 1] - 1 + SLOT_SIZE, ROW_Y, PLUS_GAP, 16);
        }

        SlotLookup lookup = slots == null ? SlotLookup.NONE : name -> slots.findSlotByName(name).orElse(null);
        IRecipeSlotView source = lookup.slot(SlotLookup.SOURCE);
        IRecipeSlotView neighbor = lookup.slot(SlotLookup.NEIGHBOR);
        SceneRotation rotation = new SceneRotation();
        if (slots == null) {
            staticRotations.put(recipe, rotation);
        }
        addScene(builder, recipe, false, slots == null, rotation, lookup, BEFORE_X);
        place(builder.addRecipeArrow(), BEFORE_X + SCENE_SIZE, SCENE_Y, AFTER_X - BEFORE_X - SCENE_SIZE, SCENE_SIZE);
        addScene(builder, recipe, true, slots == null, rotation, lookup, AFTER_X);

        if (source != null) {
            addIndicator(builder, InertFormIndicator.forSource(source, recipe, inputs[0] - 1, ROW_Y));
        }
        if (neighbor != null && !recipe.neighbors().isEmpty()) {
            addIndicator(builder, InertFormIndicator.forNeighbor(neighbor, recipe, inputs[1] - 1, ROW_Y));
        }
    }

    private static void addFailureText(IRecipeExtrasBuilder builder, FluidInteractionRecipe recipe, boolean drawable) {
        int textWidth = WIDTH - 2 * MIN_MARGIN;
        int textHeight = HEIGHT - SCENE_Y - MIN_MARGIN;
        if (drawable) {
            builder.addDrawable(new TextDrawable(recipe.failure(), textWidth, textHeight)).setPosition(MIN_MARGIN, SCENE_Y);
            return;
        }
        builder.addText(recipe.failure(), textWidth, textHeight)
                .setPosition(MIN_MARGIN, SCENE_Y)
                .setTextAlignment(HorizontalAlignment.CENTER)
                .setTextAlignment(VerticalAlignment.CENTER);
    }

    private static void addIndicator(IRecipeExtrasBuilder builder, @Nullable InertFormIndicator indicator) {
        if (indicator != null) {
            builder.addWidget(indicator);
        }
    }

    @Override
    public void draw(FluidInteractionRecipe recipe, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
        drawnSlots.put(recipe, slots);
    }

    /** The scene under the mouse, described for the alternatives the layout's slots show. */
    @Override
    public void getTooltip(ITooltipBuilder tooltip, FluidInteractionRecipe recipe, IRecipeSlotsView slots, double mouseX, double mouseY) {
        Boolean after = sceneAt(recipe, mouseX, mouseY);
        if (after == null) {
            return;
        }
        SceneView.tooltip(tooltip, recipe, onEmiClock(recipe) ? clockVariant(recipe, after) : SceneView.variant(recipe, slots, after));
        if (staticRotations.containsKey(recipe)) {
            tooltip.add(Texts.clickToRotate().withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * Turns a static scene. A layout that takes widgets sends the click to a {@link SceneWidget} instead, and
     * that widget claims it. So this method only runs for scenes the widget cannot draw. JEI deprecated this
     * method in favor of the widget handlers. It remains the only input a builder without a slot view can deliver.
     */
    @SuppressWarnings("removal")
    @Override
    public boolean handleInput(FluidInteractionRecipe recipe, double mouseX, double mouseY, InputConstants.Key input) {
        SceneRotation rotation = staticRotations.get(recipe);
        float step = SceneRotation.stepOf(input);
        if (rotation == null || step == 0.0f || sceneAt(recipe, mouseX, mouseY) == null) {
            return false;
        }
        rotation.step(step);
        return true;
    }

    /** The middle of the scene drawn before or after the interaction, in recipe-relative coordinates. */
    public static int sceneCenterX(boolean after) {
        return (after ? AFTER_X : BEFORE_X) + SCENE_SIZE / 2;
    }

    public static int sceneCenterY() {
        return SCENE_Y + SCENE_SIZE / 2;
    }

    /** Which scene a recipe-relative point is over: false the one before, true the one after, null neither. */
    private static @Nullable Boolean sceneAt(FluidInteractionRecipe recipe, double mouseX, double mouseY) {
        if (recipe.isFailure() || mouseY < SCENE_Y || mouseY >= SCENE_Y + SCENE_SIZE) {
            return null;
        }
        if (mouseX >= BEFORE_X && mouseX < BEFORE_X + SCENE_SIZE) {
            return Boolean.FALSE;
        }
        return mouseX >= AFTER_X && mouseX < AFTER_X + SCENE_SIZE ? Boolean.TRUE : null;
    }

    @Override
    public ResourceLocation getRegistryName(FluidInteractionRecipe recipe) {
        return recipe.id();
    }

    /**
     * A placement is consumed when the interaction writes a result where it stood. An alternative that is a
     * flowing fluid costs nothing, because the source block that feeds it survives. A slot with a free
     * alternative is never a cost.
     */
    private static RecipeIngredientRole role(FluidInteractionRecipe recipe, BlockPos offset, boolean anyFlowing) {
        return recipe.results().containsKey(offset) && !anyFlowing ? RecipeIngredientRole.INPUT : RecipeIngredientRole.CATALYST;
    }

    /** A rotatable widget when the layout can position and drive it. Otherwise, a drawable that the category turns. */
    private void addScene(IRecipeExtrasBuilder builder, FluidInteractionRecipe recipe, boolean after, boolean drawable, SceneRotation rotation,
                          SlotLookup slots, int x) {
        if (drawable) {
            SceneDrawable scene = new SceneDrawable(scenes, recipe, () -> staticVariant(recipe, after), rotation, SCENE_SIZE, SCENE_SIZE);
            builder.addDrawable(scene).setPosition(x, SCENE_Y);
            return;
        }
        Supplier<SceneVariant> variant = onEmiClock(recipe) ? () -> clockVariant(recipe, after) : () -> SceneView.variant(recipe, slots, after);
        SceneWidget widget = new SceneWidget(scenes, recipe, rotation, variant, x, SCENE_Y, SCENE_SIZE, SCENE_SIZE);
        builder.addInputHandler(widget);
        builder.addWidget(widget);
    }

    private SceneVariant staticVariant(FluidInteractionRecipe recipe, boolean after) {
        return recipe.isLockstep() ? clockVariant(recipe, after) : SceneView.variant(recipe, drawnSlots.get(recipe), after);
    }

    /** True when the scene must find its row from the time, because the slots do not tell. */
    private boolean onEmiClock(FluidInteractionRecipe recipe) {
        return recipe.isLockstep() && (tmrv || staticRotations.containsKey(recipe));
    }

    /** EMI shows one entry of a slot per second, in list order. The scene shows the same row. */
    private SceneVariant clockVariant(FluidInteractionRecipe recipe, boolean after) {
        long seconds = System.currentTimeMillis() / 1000L;
        List<FluidInteractionRecipe> rows = shownRows.getOrDefault(recipe, recipe.rows());
        FluidInteractionRecipe shown = rows.get((int) (seconds % rows.size()));
        int row = Math.max(0, recipe.rows().indexOf(shown));
        int source = (int) (seconds % shown.sourceFluids().size());
        List<Object> entries = shown.neighborEntries();
        int neighbor = entries.isEmpty() ? 0 : SceneArrangement.neighborIndexOfEntry(shown, entries.get((int) (seconds % entries.size())));
        return new SceneVariant(source, neighbor, after, row);
    }

    /**
     * Centers a placeable in an area, the way JEI's aligning {@code setPosition} overload does. Every reader of
     * this category's layout implements only the two-argument form.
     */
    private static void place(IPlaceable<?> placeable, int x, int y, int areaWidth, int areaHeight) {
        placeable.setPosition(x + (areaWidth - placeable.getWidth()) / 2, y + (areaHeight - placeable.getHeight()) / 2);
    }

    private static int inputCount(FluidInteractionRecipe recipe) {
        return 1 + (recipe.neighbors().isEmpty() ? 0 : 1) + recipe.conditions().size();
    }

    /** Ingredient x of each input slot: the row is centered over the left scene, or flush left when too wide. */
    private static int[] inputSlotX(FluidInteractionRecipe recipe) {
        int count = inputCount(recipe);
        int width = count * SLOT_SIZE + (count - 1) * PLUS_GAP;
        int left = Math.max(MIN_MARGIN, BEFORE_X + SCENE_SIZE / 2 - width / 2);
        int[] xs = new int[count];
        for (int i = 0; i < count; i++) {
            xs[i] = left + i * (SLOT_SIZE + PLUS_GAP) + 1;
        }
        return xs;
    }

    /** Ingredient x of each result slot: the row is centered over the right scene, pushed right to clear the input row. */
    private static int[] resultSlotX(FluidInteractionRecipe recipe) {
        int count = Math.max(1, recipe.results().size());
        int width = count * SLOT_SIZE + (count - 1) * RESULT_GAP;
        int[] inputs = inputSlotX(recipe);
        int rowRight = inputs[inputs.length - 1] - 1 + SLOT_SIZE;
        int left = Math.max(rowRight + MIN_MARGIN, AFTER_X + SCENE_SIZE / 2 - width / 2);
        int first = Math.min(left, WIDTH - MIN_MARGIN - width) + 1;
        int[] xs = new int[recipe.results().size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = first + i * (SLOT_SIZE + RESULT_GAP);
        }
        return xs;
    }

    private static int centered(int width) {
        return (WIDTH - width) / 2 + 1;
    }

    /**
     * A slot holds blocks as often as fluids. An amount means nothing for a block in the world. So every slot
     * keeps JEI's default fluid renderer. This renderer fills the same 16 by 16 square at bucket capacity, with
     * no amount line.
     */
    private static IRecipeSlotBuilder slot(IRecipeSlotBuilder slot) {
        return slot.setStandardSlotBackground();
    }

    /**
     * Adds each placement as a fluid, an item, or an {@link ItemlessBlock}.
     * JEI only knows still fluids, so both forms of one fluid share one cycling entry.
     */
    private static void addPlacements(IRecipeSlotBuilder slot, List<Placement> placements) {
        Set<Fluid> fluids = new LinkedHashSet<>();
        for (Placement placement : placements) {
            if (!placement.isFluid() || fluids.add(FluidInteractionRecipe.stillForm(placement.effectiveFluid()))) {
                addEntry(slot, placement);
            }
        }
    }

    /** One entry per row, repeats included. */
    private static IRecipeSlotBuilder addEntries(IRecipeSlotBuilder slot, List<FluidInteractionRecipe> rows, Function<FluidInteractionRecipe, Placement> entry) {
        rows.forEach(row -> addEntry(slot, entry.apply(row)));
        return slot;
    }

    private static void addEntry(IRecipeSlotBuilder slot, Placement placement) {
        if (placement.isFluid()) {
            slot.addFluidStack(FluidInteractionRecipe.stillForm(placement.effectiveFluid()), FluidType.BUCKET_VOLUME);
            return;
        }
        ItemStack item = placement.asItem();
        if (item.isEmpty()) {
            slot.addIngredient(ItemlessBlock.TYPE, new ItemlessBlock(placement.block()));
        } else {
            slot.addItemStack(item);
        }
    }

    /** The still form of the fluid a slot is currently cycling to, or null when it shows no fluid. */
    static @Nullable Fluid displayedFluid(IRecipeSlotView view) {
        ITypedIngredient<?> displayed = view.getDisplayedIngredient().orElse(null);
        if (displayed == null || !(displayed.getIngredient() instanceof FluidStack stack)) {
            return null;
        }
        return FluidInteractionRecipe.stillForm(stack.getFluid().defaultFluidState());
    }

    static List<Fluid> stillFluidsOf(FluidType type) {
        return BuiltInRegistries.FLUID.stream()
                .filter(fluid -> fluid != Fluids.EMPTY && fluid.getFluidType() == type && fluid.defaultFluidState().isSource())
                .toList();
    }
}
