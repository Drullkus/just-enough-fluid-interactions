package us.drullk.jefi.jei.scene;

import org.jetbrains.annotations.Nullable;

import mezz.jei.api.gui.ingredient.IRecipeSlotView;

/** Finds a slot of one recipe layout by its name. */
@FunctionalInterface
public interface SlotLookup {
    SlotLookup NONE = name -> null;
    String SOURCE = "source";
    String NEIGHBOR = "neighbor";

    @Nullable IRecipeSlotView slot(String name);

    /** The name of the condition slot for the recipe's condition offset at this index. */
    static String condition(int index) {
        return "condition_" + index;
    }

    /** The name of the result slot for the recipe's result offset at this index. */
    static String result(int index) {
        return "result_" + index;
    }
}
