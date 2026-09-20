package us.drullk.jefi.devtest;

import us.drullk.jefi.JustEnoughFluidInteractions;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Development-only smoke test of this mod's category through EMI. The system property
 * {@code -Djustenoughfluidinteractions.emiautotest=true} enables it (see the {@code clientEmiTest} run
 * configuration). EMI runs JEI plugins through its own bridge. That bridge's builders differ from JEI's, so
 * the layout this mod hands out must hold up in both. Screenshots go to {@code run/screenshots} as
 * {@code emi_fluid_interactions_*}.
 */
@EventBusSubscriber(modid = JustEnoughFluidInteractions.MODID, value = Dist.CLIENT)
public final class EmiAutoTest {
    private static final boolean ENABLED = Boolean.getBoolean("justenoughfluidinteractions.emiautotest");

    private EmiAutoTest() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        EmiAutoTestSteps.tick(Minecraft.getInstance());
    }
}
