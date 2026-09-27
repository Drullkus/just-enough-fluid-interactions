package us.drullk.jefi.devtest;

import org.slf4j.Logger;

import us.drullk.jefi.JustEnoughFluidInteractions;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Mutes the client in memory before the title music; {@code options.txt} keeps its volume. */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID, value = Dist.CLIENT)
final class MuteClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ACTIVE = Boolean.getBoolean("justenoughfluidinteractions.mute");

    private MuteClient() {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        if (!ACTIVE) {
            return;
        }
        event.enqueueWork(() -> {
            Minecraft.getInstance().options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            LOGGER.info("Muted the client for a test run");
        });
    }
}
