package us.drullk.jefi.jei.scene;

/**
 * Identifies one bakeable scene of a recipe: which of the cycling alternatives it shows and whether the
 * interaction's results have been applied.
 *
 * @param source   index into the source fluids of the recipe, or of the row of a lockstep recipe.
 * @param neighbor index into the neighbor alternatives of the recipe, or of the row of a lockstep recipe.
 * @param after    whether the results are applied on top of the starting arrangement.
 * @param row      index into the rows of a lockstep recipe.
 */
public record SceneVariant(int source, int neighbor, boolean after, int row) {
    public SceneVariant(int source, int neighbor, boolean after) {
        this(source, neighbor, after, 0);
    }

    /** The same alternatives in the other phase. */
    public SceneVariant otherPhase() {
        return new SceneVariant(source, neighbor, !after, row);
    }
}
