package us.drullk.jefi.jei.scene;

/**
 * Identifies one bakeable scene of a recipe: which of the cycling alternatives it shows and whether the
 * interaction's results have been applied.
 *
 * @param source   index into the recipe's source fluids
 * @param neighbor index into the recipe's neighbor alternatives
 * @param after    whether the results are applied on top of the starting arrangement
 */
public record SceneVariant(int source, int neighbor, boolean after) {
}
