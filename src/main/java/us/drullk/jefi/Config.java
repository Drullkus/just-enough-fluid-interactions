package us.drullk.jefi;

import java.util.List;
import java.util.regex.Pattern;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-side config governing which fluid interaction recipes JEI displays. */
public final class Config {
    private static final Pattern MOD_ID_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern FLUID_ID_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue HIDE_UNPROCESSABLE = BUILDER
            .comment("Hide fluid interaction recipes that could not be probed successfully.")
            .define("hideUnprocessable", false);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> IGNORED_MODS = BUILDER
            .comment("Fluid interaction recipes owned by these mod ids are hidden.")
            .defineListAllowEmpty("ignoredMods", List.of(), () -> "", Config::isPlausibleModId);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FORCE_SPREAD_PROBE = BUILDER
            .comment("Fluid ids to probe for spread rules although their classes declare no spread code of their own.")
            .defineListAllowEmpty("forceSpreadProbe", List.of(), () -> "", Config::isPlausibleFluidId);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    private static boolean isPlausibleModId(Object value) {
        return value instanceof String string && MOD_ID_PATTERN.matcher(string).matches();
    }

    private static boolean isPlausibleFluidId(Object value) {
        return value instanceof String string && FLUID_ID_PATTERN.matcher(string).matches();
    }
}
