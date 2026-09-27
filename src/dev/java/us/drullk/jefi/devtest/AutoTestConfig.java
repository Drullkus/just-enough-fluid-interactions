package us.drullk.jefi.devtest;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import us.drullk.jefi.Config;
import us.drullk.jefi.JustEnoughFluidInteractions;
import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Shows the failure recipes in every development run. The dev checks count them, and the client config hides
 * them by default. The run directory of a new worktree gets that default. So this listener sets the option to
 * false when the client config loads. The file on disk keeps its own value.
 *
 * <p>Outside a pack, the listener also adds {@link #LOCKSTEP_MOD} to {@code mergeWithinMods}.
 */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID, value = Dist.CLIENT)
final class AutoTestConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The test mod whose recipes merge into rows in a dev run. */
    static final String LOCKSTEP_MOD = "gaiadimension";

    private AutoTestConfig() {
    }

    @SubscribeEvent
    static void onLoading(ModConfigEvent.Loading event) {
        adjust(event);
    }

    @SubscribeEvent
    static void onReloading(ModConfigEvent.Reloading event) {
        adjust(event);
    }

    private static void adjust(ModConfigEvent event) {
        if (!AutoTestWorld.FIXTURES || event.getConfig().getSpec() != Config.SPEC) {
            return;
        }
        if (Config.HIDE_UNPROCESSABLE.get()) {
            Config.HIDE_UNPROCESSABLE.set(false);
            LOGGER.info("Test run: hideUnprocessable is false, so the failure recipes show");
        }
        List<? extends String> withinMods = Config.MERGE_WITHIN_MODS.get();
        if (!PackRun.ACTIVE && !withinMods.contains(LOCKSTEP_MOD)) {
            List<String> merged = new ArrayList<>(withinMods);
            merged.add(LOCKSTEP_MOD);
            Config.MERGE_WITHIN_MODS.set(merged);
            LOGGER.info("Test run: mergeWithinMods is {}", merged);
        }
    }
}
