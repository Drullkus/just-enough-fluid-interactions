package us.drullk.jefi.jei.probe;

import java.util.Comparator;

import org.jetbrains.annotations.Nullable;

import us.drullk.jefi.JustEnoughFluidInteractions;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The order of recipes and the ids they carry.
 *
 * <p>JEI keeps bookmarks by recipe id, so an id must stay the same across launches. NeoForge runs mod setup in
 * parallel, so the registration index of an interaction changes between launches. So the ids rank fluid types
 * and owners by namespace. Inside one owner, the results decide the number of a recipe.
 */
public final class RecipeIds {
    private static final String MINECRAFT = "minecraft";
    private static final String NEOFORGE = "neoforge";

    /** Ranks namespaces: {@code minecraft}, then {@code neoforge}, then the others alphabetically. */
    static final Comparator<String> NAMESPACE_ORDER =
            Comparator.comparingInt(RecipeIds::namespaceRank).thenComparing(Comparator.naturalOrder());

    /**
     * Ranks locations by {@link #NAMESPACE_ORDER} on the namespace, then by the path. The own order of
     * {@link ResourceLocation} compares the path first, so no ordering rule uses it.
     */
    static final Comparator<ResourceLocation> LOCATION_ORDER =
            Comparator.comparing(ResourceLocation::getNamespace, NAMESPACE_ORDER).thenComparing(ResourceLocation::getPath);

    /** {@link #NAMESPACE_ORDER} on an owner mod id. A null owner ranks last. */
    static final Comparator<String> OWNER_ORDER = Comparator.nullsLast(NAMESPACE_ORDER);

    private RecipeIds() {
    }

    public static ResourceLocation keyOf(FluidType type) {
        ResourceLocation key = NeoForgeRegistries.FLUID_TYPES.getKey(type);
        return key != null ? key : ResourceLocation.fromNamespaceAndPath("unknown", "unregistered");
    }

    /** The registry key of a result block, or null when there is no result. */
    static @Nullable ResourceLocation blockKey(@Nullable BlockState state) {
        return state != null ? BuiltInRegistries.BLOCK.getKey(state.getBlock()) : null;
    }

    /**
     * The owner as one id segment. A mod id holds only characters that are legal in a path. Every other
     * character becomes {@code _}. An unexpected owner id thus can not split the id into more segments.
     */
    static String ownerSegment(@Nullable String owner) {
        String raw = owner != null ? owner : "unknown";
        StringBuilder sanitized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean legal = c == '_' || c == '-' || c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            sanitized.append(legal ? c : '_');
        }
        return sanitized.toString();
    }

    /**
     * One recipe id. The tier segment is empty for the registry tier, {@code spread/} for the spread tier and
     * {@code neighbor/} for the neighbor tier. Then come the namespace and path of the type. Then come the owner
     * segment, the number of the rule in its owner group, and the variant number.
     */
    static ResourceLocation id(String tier, ResourceLocation typeKey, String ownerSegment, int n, int variant) {
        return ResourceLocation.fromNamespaceAndPath(JustEnoughFluidInteractions.MODID,
                tier + typeKey.getNamespace() + "/" + typeKey.getPath() + "/" + ownerSegment + "/" + n + "/" + variant);
    }

    private static int namespaceRank(String namespace) {
        if (MINECRAFT.equals(namespace)) {
            return 0;
        }
        return NEOFORGE.equals(namespace) ? 1 : 2;
    }
}
