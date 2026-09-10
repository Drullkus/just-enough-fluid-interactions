package us.drullk.jefi.jei;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.JustEnoughFluidInteractions;
import us.drullk.jefi.jei.probe.FluidInteractionRecipe;
import us.drullk.jefi.jei.probe.Placement;
import us.drullk.jefi.jei.scene.SceneCache;
import us.drullk.jefi.jei.scene.SceneDrawable;
import us.drullk.jefi.jei.scene.SceneRotation;
import us.drullk.jefi.jei.scene.SceneView;
import us.drullk.jefi.jei.scene.SceneWidget;

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
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * JEI category for probed fluid interactions.
 *
 * <p>Two 3D scenes show the arrangement before and after the interaction, with one arrow between them. Above the
 * left scene sits the input row (source fluid, the neighbor, and any extra required blocks, separated by plus
 * signs); above the right scene sit the results. A recipe whose interaction could not be processed shows only
 * the source fluid and an explanation.
 */
public final class FluidInteractionCategory extends AbstractRecipeCategory<FluidInteractionRecipe> {
    public static final int WIDTH = 170;
    public static final int HEIGHT = 100;

    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID, "textures/gui/icon.png");
    private static final int ICON_SIZE = 16;

    private static final int ROW_Y = 6;
    /** Footprint of a slot including its background, which JEI draws one pixel outside the ingredient. */
    private static final int SLOT_SIZE = 18;
    private static final int PLUS_GAP = 15;
    private static final int RESULT_GAP = 2;
    private static final int MIN_MARGIN = 4;

    private static final int SCENE_Y = 26;
    private static final int SCENE_SIZE = 68;
    private static final int BEFORE_X = 5;
    private static final int AFTER_X = WIDTH - BEFORE_X - SCENE_SIZE;

    private final SceneCache scenes;
    /** The slot view each recipe was last drawn with, which is what a static scene takes its alternatives from. */
    private final Map<FluidInteractionRecipe, IRecipeSlotsView> drawnSlots = new IdentityHashMap<>();

    public FluidInteractionCategory(IGuiHelper guiHelper, SceneCache scenes) {
        super(FluidInteractionsJeiPlugin.TYPE, Texts.title(), guiHelper.drawableBuilder(ICON, 0, 0, ICON_SIZE, ICON_SIZE).setTextureSize(ICON_SIZE, ICON_SIZE).build(), WIDTH, HEIGHT);
        this.scenes = scenes;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, FluidInteractionRecipe recipe, IFocusGroup focuses) {
        if (recipe.isFailure()) {
            IRecipeSlotBuilder source = slot(builder.addInputSlot(centered(SLOT_SIZE), ROW_Y)).setSlotName("source");
            stillFluidsOf(recipe.sourceType()).forEach(fluid -> source.addFluidStack(fluid, FluidType.BUCKET_VOLUME));
            return;
        }

        int[] inputs = inputSlotX(recipe);
        int next = 0;

        boolean sourceFlows = recipe.sources().stream().anyMatch(state -> !state.isSource());
        IRecipeSlotBuilder source = slot(builder.addSlot(role(recipe, BlockPos.ZERO, sourceFlows), inputs[next++], ROW_Y)).setSlotName("source");
        recipe.sourceFluids().forEach(fluid -> source.addFluidStack(fluid, FluidType.BUCKET_VOLUME));
        source.addRichTooltipCallback((view, tooltip) -> {
            tooltip.add(Texts.forms(recipe).withStyle(ChatFormatting.GRAY));
            Fluid shown = displayedFluid(view);
            FluidState inert = shown != null ? recipe.inert().sourceOf(shown) : null;
            if (inert != null) {
                tooltip.add(Texts.inertSource(inert).withStyle(ChatFormatting.YELLOW));
            }
        });

        if (!recipe.neighbors().isEmpty()) {
            boolean neighborFlows = recipe.neighbors().stream().anyMatch(Placement::isFlowing);
            IRecipeSlotBuilder neighbor = slot(builder.addSlot(role(recipe, recipe.neighborOffset(), neighborFlows), inputs[next++], ROW_Y)).setSlotName("neighbor");
            addPlacements(neighbor, recipe.neighbors());
            neighbor.addRichTooltipCallback((view, tooltip) -> {
                tooltip.add(Texts.offset(recipe.neighborOffset()).withStyle(ChatFormatting.GRAY));
                Fluid shown = displayedFluid(view);
                Placement inert = shown != null ? recipe.inert().neighborOf(shown) : null;
                if (inert != null) {
                    tooltip.add(Texts.inertNeighbor(inert).withStyle(ChatFormatting.YELLOW));
                }
            });
        }
        for (var condition : recipe.conditions().entrySet()) {
            BlockPos offset = condition.getKey();
            IRecipeSlotBuilder slot = slot(builder.addSlot(role(recipe, offset, condition.getValue().isFlowing()), inputs[next++], ROW_Y));
            addPlacements(slot, List.of(condition.getValue()));
            slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.offset(offset).withStyle(ChatFormatting.GRAY)));
        }

        int resultX = resultSlotX(recipe);
        for (var result : recipe.results().entrySet()) {
            BlockPos offset = result.getKey();
            IRecipeSlotBuilder slot = slot(builder.addOutputSlot(resultX, ROW_Y));
            addPlacements(slot, List.of(Placement.ofBlock(result.getValue())));
            if (!offset.equals(BlockPos.ZERO)) {
                slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(Texts.offset(offset).withStyle(ChatFormatting.GRAY)));
            }
            resultX += SLOT_SIZE + RESULT_GAP;
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, FluidInteractionRecipe recipe, IFocusGroup focuses) {
        // A builder without a slot view can neither position widgets nor route input to them, so its scenes are
        // static drawables and its text is drawn by hand.
        IRecipeSlotDrawablesView slots = builder.getRecipeSlots();

        if (recipe.isFailure()) {
            int textWidth = WIDTH - 2 * MIN_MARGIN;
            int textHeight = HEIGHT - SCENE_Y - MIN_MARGIN;
            if (slots == null) {
                builder.addDrawable(new TextDrawable(recipe.failure(), textWidth, textHeight)).setPosition(MIN_MARGIN, SCENE_Y);
            } else {
                builder.addText(recipe.failure(), textWidth, textHeight)
                        .setPosition(MIN_MARGIN, SCENE_Y)
                        .setTextAlignment(HorizontalAlignment.CENTER)
                        .setTextAlignment(VerticalAlignment.CENTER);
            }
            return;
        }

        // The widget-returning plus sign and arrow builders postdate the oldest supported JEI, so these are the
        // only entry points to those two textures that every supported version has.
        int[] inputs = inputSlotX(recipe);
        for (int i = 1; i < inputs.length; i++) {
            place(builder.addRecipePlusSign(), inputs[i - 1] - 1 + SLOT_SIZE, ROW_Y, PLUS_GAP, 16);
        }

        IRecipeSlotView source = slots == null ? null : slots.findSlotByName("source").orElse(null);
        IRecipeSlotView neighbor = slots == null ? null : slots.findSlotByName("neighbor").orElse(null);
        SceneRotation rotation = slots == null ? null : new SceneRotation();

        addScene(builder, recipe, false, rotation, source, neighbor, BEFORE_X);
        place(builder.addRecipeArrow(), BEFORE_X + SCENE_SIZE, SCENE_Y, AFTER_X - BEFORE_X - SCENE_SIZE, SCENE_SIZE);
        addScene(builder, recipe, true, rotation, source, neighbor, AFTER_X);

        if (slots != null) {
            if (source != null) {
                InertFormIndicator indicator = InertFormIndicator.forSource(source, recipe, inputs[0] - 1, ROW_Y);
                if (indicator != null) {
                    builder.addWidget(indicator);
                }
            }
            if (neighbor != null && !recipe.neighbors().isEmpty()) {
                InertFormIndicator indicator = InertFormIndicator.forNeighbor(neighbor, recipe, inputs[1] - 1, ROW_Y);
                if (indicator != null) {
                    builder.addWidget(indicator);
                }
            }
        }
    }

    @Override
    public void draw(FluidInteractionRecipe recipe, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
        drawnSlots.put(recipe, slots);
    }

    /** The scene under the mouse, described for the alternatives the layout's slots show. */
    @Override
    public void getTooltip(ITooltipBuilder tooltip, FluidInteractionRecipe recipe, IRecipeSlotsView slots, double mouseX, double mouseY) {
        if (recipe.isFailure() || mouseY < SCENE_Y || mouseY >= SCENE_Y + SCENE_SIZE) {
            return;
        }
        boolean before = mouseX >= BEFORE_X && mouseX < BEFORE_X + SCENE_SIZE;
        boolean after = mouseX >= AFTER_X && mouseX < AFTER_X + SCENE_SIZE;
        if (before || after) {
            SceneView.tooltip(tooltip, recipe, SceneView.variant(recipe, slots, after));
        }
    }

    @Override
    public ResourceLocation getRegistryName(FluidInteractionRecipe recipe) {
        return recipe.id();
    }

    /**
     * A placement is consumed when the interaction writes a result where it stood, unless one of its alternatives
     * is a flowing fluid: that one costs nothing, since the source block feeding it survives, and a slot whose
     * alternatives include a free one is not a cost.
     */
    private static RecipeIngredientRole role(FluidInteractionRecipe recipe, BlockPos offset, boolean anyFlowing) {
        return recipe.results().containsKey(offset) && !anyFlowing ? RecipeIngredientRole.INPUT : RecipeIngredientRole.CATALYST;
    }

    /** A rotatable widget where the layout can position and drive one, a scene at the default angle otherwise. */
    private void addScene(IRecipeExtrasBuilder builder, FluidInteractionRecipe recipe, boolean after, @Nullable SceneRotation rotation,
                          @Nullable IRecipeSlotView source, @Nullable IRecipeSlotView neighbor, int x) {
        if (rotation == null) {
            SceneDrawable drawable = new SceneDrawable(scenes, recipe, () -> SceneView.variant(recipe, drawnSlots.get(recipe), after), SCENE_SIZE, SCENE_SIZE);
            builder.addDrawable(drawable).setPosition(x, SCENE_Y);
            return;
        }
        SceneWidget widget = new SceneWidget(scenes, recipe, after, rotation, source, neighbor, x, SCENE_Y, SCENE_SIZE, SCENE_SIZE);
        builder.addInputHandler(widget);
        builder.addWidget(widget);
    }

    /**
     * Centers a placeable in an area, as JEI's aligning overload of {@code setPosition} does: only the two
     * argument form is guaranteed to be implemented by everything that reads this category's layout.
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

    /** Ingredient x of the first result slot: centered over the right scene, pushed right to clear the input row. */
    private static int resultSlotX(FluidInteractionRecipe recipe) {
        int count = Math.max(1, recipe.results().size());
        int width = count * SLOT_SIZE + (count - 1) * RESULT_GAP;
        int[] inputs = inputSlotX(recipe);
        int rowRight = inputs[inputs.length - 1] - 1 + SLOT_SIZE;
        int left = Math.max(rowRight + MIN_MARGIN, AFTER_X + SCENE_SIZE / 2 - width / 2);
        return Math.min(left, WIDTH - MIN_MARGIN - width) + 1;
    }

    private static int centered(int width) {
        return (WIDTH - width) / 2 + 1;
    }

    /**
     * A slot holds blocks as often as fluids, and an amount means nothing for a block in the world, so every slot
     * keeps JEI's default fluid renderer, which fills the same 16 by 16 at bucket capacity without an amount line.
     */
    private static IRecipeSlotBuilder slot(IRecipeSlotBuilder slot) {
        return slot.setStandardSlotBackground();
    }

    /**
     * Adds each placement as a fluid or item ingredient; blocks without an item are only shown in the scene.
     * JEI only knows still fluids, so both forms of one fluid share a single cycling entry.
     */
    private static void addPlacements(IRecipeSlotBuilder slot, List<Placement> placements) {
        Set<Fluid> fluids = new LinkedHashSet<>();
        for (Placement placement : placements) {
            if (placement.isFluid()) {
                Fluid fluid = FluidInteractionRecipe.stillForm(placement.effectiveFluid());
                if (fluids.add(fluid)) {
                    slot.addFluidStack(fluid, FluidType.BUCKET_VOLUME);
                }
                continue;
            }
            ItemStack item = placement.asItem();
            if (!item.isEmpty()) {
                slot.addItemStack(item);
            }
        }
    }

    /** The still form of the fluid a slot is currently cycling to, or null when it shows an item or nothing. */
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
