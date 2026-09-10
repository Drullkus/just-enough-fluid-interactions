package us.drullk.jefi.devtest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.slf4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** The flat creative world and the screenshots that every recipe viewer smoke test needs. */
final class AutoTestWorld {
    /** The dev-only interactions and fluid are registered for whichever viewer's smoke test is running. */
    static final boolean FIXTURES = Boolean.getBoolean("justenoughfluidinteractions.jeiautotest")
            || Boolean.getBoolean("justenoughfluidinteractions.emiautotest");

    private AutoTestWorld() {
    }

    /**
     * Dismisses the accessibility onboarding that a run directory without an {@code options.txt} opens, and
     * reports whether the title screen is up and a world can be created.
     */
    static boolean atTitleScreen(Minecraft mc) {
        if (mc.screen instanceof AccessibilityOnboardingScreen) {
            mc.options.onboardAccessibility = false;
            mc.setScreen(new TitleScreen());
            return false;
        }
        if (mc.screen instanceof TitleScreen) {
            mc.options.pauseOnLostFocus = false;
            return true;
        }
        return false;
    }

    static void enterWorld(Minecraft mc, String worldName, Logger logger, String prefix) {
        deleteExistingWorld(mc, worldName);
        logger.info("{} creating world {}", prefix, worldName);
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(worldName, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(1234L, false, false);
        mc.createWorldOpenFlows().createFreshLevel(worldName, settings, options,
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    /** Runs from the title screen before any level is loaded, so the save directory is never open here. */
    private static void deleteExistingWorld(Minecraft mc, String worldName) {
        Path path = mc.getLevelSource().getLevelPath(worldName);
        if (!Files.isDirectory(path)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Smoke test failed to delete existing world " + worldName, e);
        }
    }

    static void grab(Minecraft mc, String fileName, Logger logger, String prefix) {
        Screenshot.grab(mc.gameDirectory, fileName, mc.getMainRenderTarget(),
                message -> logger.info("{} screenshot: {}", prefix, message.getString()));
    }
}
