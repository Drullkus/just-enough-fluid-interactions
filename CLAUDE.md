# Just Enough Fluid Interactions

A NeoForge 1.21.1 mod whose only feature is a JEI plugin that displays every `FluidInteractionRegistry` entry as a
recipe, discovered by running the interactions in a sandbox level and rendered as 3D scenes with Gander.

## Versions

- Minecraft 1.21.1, NeoForge 21.1.249, ModDevGradle 2.0.146, Gradle wrapper 9.2.1, Java toolchain 21.
- Parchment 2024.11.17 for 1.21.1.
- JEI `curse.maven:jei-238222:8815666` (19.53.0.425), full jar on the compile classpath.
- Gaia Dimension `curse.maven:gaia-dimension-302529:7021516` (1.21-2.2.288), a mod that registers 11 interactions.
- Gander `dev.compactmods.gander:{core,levels,rendering,ui}` from GitHub Packages, bundled jar-in-jar. Version in
  `gradle.properties` (`gander_version`), currently the published `0.2.32`; the jar-in-jar range is `[0.2,1.0)`.
  Nothing resolves from the local Maven repository.
- Mod id and package are `justenoughfluidinteractions`, `us.drullk.jefi`.

## Documentation and comments

- No comments narrating what changed, why, or which task/session produced it (no "per task 9", "fixed for the handoff", "added by agent", "TODO: see HANDOFF"). A comment states an invariant the code can't express on its own — nothing else. Delete narrative comments you encounter while touching a file, even ones you didn't write.
- Don't renumber, duplicate, or invent new task numbers anywhere — not in code, comments, commit messages, or branch names.
- Agents working in a worktree do not edit `CLAUDE.md` themselves. Report doc-relevant findings (a task is done, an assumption changed, a fact here is now wrong) in your final summary; the user reconciles the docs centrally. This avoids conflicting edits to the same file across parallel branches.
- If your change makes a fact in `CLAUDE.md` or other Markdown file false (a renamed package, a moved file, a retired workaround), say so explicitly in your final report even if you're not the one editing the doc.

## Agent workflow

- Work happens on a task branch in its own worktree: one wrap-up commit, never `main`, never a push.
- "Compiles" is not "done." Before reporting a task complete: `./gradlew build`, then `./gradlew runClientJeiTest`, read the resulting screenshots, and check the run log for `Probed N fluid interaction(s)`, the absence of `Failed to bake`, and that the only `No probe of fluid interaction ... succeeded` line is the dev-only `minecraft:water#0` fallback (that line is info-level and expected exactly once).

## Credentials

GitHub Packages credentials are Gradle properties in the user's global `~/.gradle/gradle.properties`.
Never read, print, or probe that file or those values.

## Commands

- Build the jar: `./gradlew build` (output `build/libs/justenoughfluidinteractions-1.0.0.jar`, includes `META-INF/jarjar/`).
- Compile only: `./gradlew compileJava compileDevJava`.
- Smoke test in a real client: `./gradlew runClientJeiTest`. Deletes any previous test save, creates a flat world, opens the category, writes
  `run/screenshots/jei_fluid_interactions_*.png`, exits. Takes about 30 s. Read the PNGs to verify rendering.
- Plain client: `./gradlew runClient`.
- The machine's default JDK is 25; this project's wrapper is fine with it. Normal builds never build Gander; if you
  ever build it from source, its Gradle 8.11 needs `JAVA_HOME` pointed at a JDK 21.

## Source sets

- `src/main`: the mod. Plugin code under `us/drullk/jefi/jei/` (`probe`, `sandbox`, `scene` packages).
- `src/dev`: development-only classes bound to the mod for runs, never packaged. Gated by the system property
  `justenoughfluidinteractions.jeiautotest` set by the `clientJeiTest` run config.
- `src/main/resources/META-INF/accesstransformer.cfg`: the only AT; opens `FluidInteractionRegistry.INTERACTIONS`.
- `src/main/resources/assets/justenoughfluidinteractions/lang/en_us.json`: all translations.
- `us.drullk.jefi.Config`: the client config (`hideUnprocessable`, `ignoredMods`), registered from the main class
  constructor; `FluidInteractionsJeiPlugin.filter` applies it after probing. File
  `config/justenoughfluidinteractions-client.toml`.

## Conventions and gotchas

- No mixins. Access transformers are acceptable. ATs on NeoForge's own classes are applied at runtime by FML but are
  **not visible at compile time** under ModDevGradle; use a reflective lookup (see `RegisteredInteractions`).
- The sandbox level reports `isClientSide == false` on purpose so interactions guarded on the server side run. Anything
  from Gander that assumes a client level must be overridden on `SandboxLevel` (model data already is).
- `GuiGraphics.enableScissor` in 1.21.1 takes absolute GUI coordinates; the recipe widget's pose is translated to the
  widget origin, so read `pose.m30()/m31()` for the absolute position.
- Everything in `scene/` runs on the render thread. Vertex buffers must be created and closed there.
- Scenes are orthographic and drag-rotatable; the display flow height is `SceneArrangement.DISPLAY_FLOW_LEVEL`
  (probing keeps level 7), the default camera angle is `SceneRenderer.DEFAULT_YAW`/`DEFAULT_PITCH`, and a custom
  rotate cursor would plug into `SceneCursor.handle()`.
- Interaction owners come from `InteractionOwners`: the lambda's declaring class, then the namespace of captured
  registry objects. A third-party mod using `InteractionInformation`'s convenience constructors with only vanilla
  blocks still reads as `neoforge`; treat attribution as approximate.
- JEI slots accept only still fluids; map flowing states with `FluidInteractionRecipe.stillForm`. Neighbor
  placements carry the fluid form the probe verified (`Placement.isFlowing`, described as "Flowing <fluid>");
  when a recipe holds both forms of one fluid the scene draws the flowing one (`SceneArrangement.neighborIndex`).
- JEI shows two recipes per page at the smoke test's window size and GUI scale 2.
- A fresh `run/` directory (every new worktree) has no `options.txt`, so the client opens the accessibility
  onboarding screen before the title screen. `JeiAutoTest` dismisses it itself; if a smoke test ever sits idle with no
  `Smoke test` log line, that dismissal is what to check first. The test deletes its save before creating it, so
  re-runs never load an existing world (loading one would stop on the experimental-world backup prompt).
- Recipe ids (`justenoughfluidinteractions:<type namespace>/<type path>/<index>/<variant>`) must stay unique; JEI uses them for bookmarks.
  `RecipeMerger` keeps the first member's id (probe order) when it collapses recipes, and includes the owner in its
  merge keys so patterns from different mods stay separate.
- Never call `FlowingFluid.getFlowing(level, falling)` on modded fluids: some register flowing states without the
  `LEVEL` or `FALLING` property and `setValue` throws. Build the state with `defaultFluidState().trySetValue(...)`
  as `InteractionProber` and `SceneArrangement` do.

## Verifying changes

Compile, then run the smoke test and read the screenshots. Check the log for `Probed N fluid interaction(s)`,
`Failed to bake`, and `No probe of fluid interaction ... succeeded` (expected once, for the dev fallback; any other
is a regression). Expect one `Merged N fluid interaction recipe(s) into M` line from `RecipeMerger`. The smoke test also logs one
`Smoke test recipe ...` line per recipe with owner, source-state and neighbor counts, `... 0 offender(s)` for the
duplicate-alternative check, and one `Smoke test merged recipe ...` line proving both merge passes collapsed the
dev-only mergeable interactions, and one `Smoke test flowing neighbor ...` line proving the cobblestone recipe
carries a flowing water neighbor (an `ERROR` from either is a regression). Crop and enlarge screenshots with `sips`
when a detail matters.
