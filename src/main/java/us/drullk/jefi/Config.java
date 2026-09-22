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
            .comment("Hide recipes of interactions that the probe cannot run, and of interactions that do not occur",
                    "in the world because a different rule occurs first.")
            .define("hideUnprocessable", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> IGNORED_MODS = BUILDER
            .comment("The probe hides the recipes of the interactions from these mod ids.")
            .defineListAllowEmpty("ignoredMods", List.of(), () -> "", Config::isPlausibleModId);

    public static final ModConfigSpec.IntValue PROBE_THREADS = BUILDER
            .comment("The number of threads that probe the fluid interactions when JEI starts.",
                    "Value 0 uses all processors but one, up to 8. The value 1 uses one thread.",
                    "Use 1 if the fluid code of a mod is not safe on another thread.")
            .defineInRange("probeThreads", 0, 0, 16);

    public static final ModConfigSpec.IntValue PROBE_STALL_SECONDS = BUILDER
            .comment("The maximum time in seconds for one fluid type per parallel process.",
                    "If one fluid type takes more time, the probe stops the threads and continues on one thread.")
            .defineInRange("probeStallSeconds", 30, 5, 600);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FORCE_PROBE = BUILDER
            .comment("The probe probes these fluid ids in every tier, also when their classes declare no code of their own.")
            .defineListAllowEmpty("forceProbe", List.of(), () -> "", Config::isPlausibleFluidId);

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
