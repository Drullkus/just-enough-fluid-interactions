package us.drullk.jefi;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(JustEnoughFluidInteractions.MODID)
public class JustEnoughFluidInteractions {
    public static final String MODID = "justenoughfluidinteractions";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JustEnoughFluidInteractions(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }
}
