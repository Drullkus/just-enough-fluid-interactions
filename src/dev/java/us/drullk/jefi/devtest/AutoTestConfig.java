package us.drullk.jefi.devtest;

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
 */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID, value = Dist.CLIENT)
final class AutoTestConfig {
    private static final Logger LOGGER = LogUtils.getLogger();

    private AutoTestConfig() {
    }

    @SubscribeEvent
    static void onLoading(ModConfigEvent.Loading event) {
        showFailures(event);
    }

    @SubscribeEvent
    static void onReloading(ModConfigEvent.Reloading event) {
        showFailures(event);
    }

    private static void showFailures(ModConfigEvent event) {
        if (!AutoTestWorld.FIXTURES || event.getConfig().getSpec() != Config.SPEC || !Config.HIDE_UNPROCESSABLE.get()) {
            return;
        }
        Config.HIDE_UNPROCESSABLE.set(false);
        LOGGER.info("Test run: hideUnprocessable is false, so the failure recipes show");
    }
}
