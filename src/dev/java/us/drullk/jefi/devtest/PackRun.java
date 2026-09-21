package us.drullk.jefi.devtest;

import us.drullk.jefi.JustEnoughFluidInteractions;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.SharedConstants;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;

/**
 * A run in the test pack, marked by the system property {@code justenoughfluidinteractions.pack}. In a
 * development run NeoForge's gametest scan loads the gametest classes of every mod, and a pack mod's tests can
 * reference mods the pack lacks. The scan runs when {@link SharedConstants#IS_RUNNING_IN_IDE} is set, which
 * every development run sets. Mod construction comes before the scan, so a pack run clears the flag here.
 */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID, value = Dist.CLIENT)
final class PackRun {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final boolean ACTIVE = Boolean.getBoolean("justenoughfluidinteractions.pack");

    static {
        if (ACTIVE) {
            SharedConstants.IS_RUNNING_IN_IDE = false;
            LOGGER.info("Pack run: NeoForge's gametest scan is off");
        }
    }

    private PackRun() {
    }

    /** The subscriber needs one handler. The static initializer above does the work when the class registers. */
    @SubscribeEvent
    public static void onConstruct(FMLConstructModEvent event) {
    }
}
