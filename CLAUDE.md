# Just Enough Fluid Interactions

A NeoForge 1.21.1 mod whose only feature is a JEI plugin that displays fluid interactions as recipes: every
`FluidInteractionRegistry` entry, plus the hardening rules fluids implement in their own spread code (vanilla's
lava-over-water stone lives in `LavaFluid.spreadTo`, not the registry). Both are discovered by running them in a
sandbox level and rendered as 3D scenes with Gander.

## Versions

- Minecraft 1.21.1, NeoForge 21.1.249, ModDevGradle 2.0.146, Gradle wrapper 9.2.1, Java toolchain 21.
- Parchment 2024.11.17 for 1.21.1.
- JEI: the full jar, as two file ids in `build.gradle`. `compileOnly` is the oldest supported release, the minimum
  of the `versionRange` in `src/main/templates/META-INF/neoforge.mods.toml`; `runtimeOnly` is a current release.
  Raise the minimum only together with the compile jar.
- Test mods on the dev classpath, declared in `build.gradle` and never shipped: Gaia Dimension (registers
  interactions and lava-tagged fluids), Create, Sable, Create Aeronautics. They exist to feed the smoke test.
- Gander `dev.compactmods.gander:{core,levels,rendering,ui}` from GitHub Packages, bundled jar-in-jar. Version in
  `gradle.properties` (`gander_version`), currently the published `0.2.32`; the jar-in-jar range is `[0.2,1.0)`.
  Nothing resolves from the local Maven repository.
- Mod id and package are `justenoughfluidinteractions`, `us.drullk.jefi`.

## Documentation and comments

- No comments narrating what changed, why, or which task/session produced it (no "per task 9", "fixed for the handoff", "added by agent", "TODO: see HANDOFF"). A comment states an invariant the code can't express on its own — nothing else. Delete narrative comments you encounter while touching a file, even ones you didn't write.
- Don't renumber, duplicate, or invent new task numbers anywhere — not in code, comments, commit messages, or branch names.
- Agents working in a worktree do not edit `CLAUDE.md` themselves. Report doc-relevant findings (a task is done, an assumption changed, a fact here is now wrong) in your final summary; the user reconciles the docs centrally. This avoids conflicting edits to the same file across parallel branches.
- If your change makes a fact in `CLAUDE.md` or other Markdown file false (a renamed package, a moved file, a retired workaround), say so explicitly in your final report even if you're not the one editing the doc.
- `CLAUDE.md` changes only when the user asks, so keep it free of facts that go stale on their own: this mod's version, jar file names, commit hashes, dependency file ids. Point at the file that holds the value instead.

## Agent workflow

- Work happens on a task branch in its own worktree: one wrap-up commit, never `main`, never a push.
- "Compiles" is not "done." Before reporting a task complete: `./gradlew build`, then `./gradlew runClientJeiTest`, read the resulting screenshots, and check the run log for `Probed N fluid interaction(s)`, the absence of `Failed to bake`, and that the only `No probe of fluid interaction ... succeeded` line is the dev-only `minecraft:water#0` fallback (that line is debug-level, so it is in the dev run's `run/logs/debug.log` and not in `latest.log`, and is expected exactly once; the smoke test's `Smoke test recipe ... (from justenoughfluidinteractions): 0 source state(s)` line for the fallback recipe is the same evidence at info).

## Credentials

GitHub Packages credentials are Gradle properties in the user's global `~/.gradle/gradle.properties`.
Never read, print, or probe that file or those values.

## Commands

- Build the jar: `./gradlew build` (output `build/libs/justenoughfluidinteractions-<mod_version>.jar`, version from
  `gradle.properties`, includes `META-INF/jarjar/`; jars of earlier versions stay in `build/libs` until cleaned).
- Compile only: `./gradlew compileJava compileDevJava`.
- Smoke test in a real client: `./gradlew runClientJeiTest`. Deletes any previous test save, creates a flat world, opens the category, writes
  `run/screenshots/jei_fluid_interactions_*.png`, exits. Takes about 30 s. Read the PNGs to verify rendering.
- Plain client: `./gradlew runClient`.
- The machine's default JDK is 25; this project's wrapper is fine with it. Normal builds never build Gander; if you
  ever build it from source, its Gradle 8.11 needs `JAVA_HOME` pointed at a JDK 21.

## Source sets

- `src/main`: the mod. Plugin code under `us/drullk/jefi/jei/` (`probe`, `sandbox`, `scene` packages).
- `src/dev`: development-only classes bound to the mod for runs, never packaged. Gated by the system property
  `justenoughfluidinteractions.jeiautotest` set by the `clientJeiTest` run config. Its compile classpath extends
  `main`'s `compileOnly`, so the smoke test compiles against the same JEI as the mod. `JeiAutoTestInteractions`
  registers dev-only interactions and `JeiAutoTestFluids` a dev-only fluid (`hardening_brine`, hardens lava-tagged
  fluids below it in source form only); a `Fluid` claims its registry holder in its constructor, so dev fluids are
  built inside `RegisterEvent`, never statically.
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
  registry objects; a spread-discovered rule is owned by its fluid (`ofFluid`: the fluid's class, then its registry
  namespace). A third-party mod using `InteractionInformation`'s convenience constructors with only vanilla
  blocks still reads as `neoforge`; treat attribution as approximate.
- `SpreadProber` is the second discovery tier: it ticks each fluid type's still and flowing source states at the
  origin with one candidate below or beside and keeps writes that are neither air nor a state of the source fluid.
  Below is the canonical target: the registry's own javadoc tests every direction except down and defers
  down-interaction changes to `FlowingFluid#spreadTo` (NeoForge issue 1880 closed the stone request as intended).
  Its fixtures (a bedrock floor for sideways targets, a feeding source above a flowing source) never enter a recipe.
  It also records, per recipe, the source and neighbor forms it tried at the same arrangement that wrote nothing
  (`FluidInteractionRecipe.inert`, always `InertForms.NONE` for registry recipes, where each form has its own
  recipe).
- Text about another mod's behavior states the observation only, never a judgment: no "bug", "incomplete",
  "missing", "should", "likely". Only that mod's developer knows intent. The inert-form lines ("%s spreads without
  interacting") are yellow so they stand out from the gray descriptive lines, and the matching
  `Fluid spread form difference` log line is info, not debug, because it is the signal a mod author would grep for.
- JEI slots accept only still fluids; map flowing states with `FluidInteractionRecipe.stillForm`. Neighbor
  placements carry the fluid form the probe verified (`Placement.isFlowing`, described as "Flowing <fluid>");
  when a recipe holds both forms of one fluid the scene draws the flowing one beside the source
  (`SceneArrangement.neighborIndex`); a recipe whose neighbor is above or below (`neighborOffset`) is drawn as two
  still blocks with no feeding sources, because nothing flows upward.
- JEI shows two recipes per page at the smoke test's window size and GUI scale 2.
- A fresh `run/` directory (every new worktree) has no `options.txt`, so the client opens the accessibility
  onboarding screen before the title screen. `JeiAutoTest` dismisses it itself; if a smoke test ever sits idle with no
  `Smoke test` log line, that dismissal is what to check first. The test deletes its save before creating it, so
  re-runs never load an existing world (loading one would stop on the experimental-world backup prompt).
- Recipe ids are `justenoughfluidinteractions:<type namespace>/<type path>/<owner>/<n>/<variant>` for registry
  interactions and `justenoughfluidinteractions:spread/<type namespace>/<type path>/<owner>/<n>/<variant>` for
  spread-discovered ones, and must stay unique and stable across launches; JEI uses them for bookmarks. Fluid types
  rank `minecraft`, `neoforge`, then other namespaces alphabetically, then path (`InteractionProber.LOCATION_ORDER`,
  never `ResourceLocation`'s path-first natural order). Within a type, registry interactions are grouped by owner in
  the same ranking and numbered (`n`) with successes first, then the result block's key, then registration index;
  spread recipes number `n` by target position (below, then beside) and `variant` by outcome. Registration index
  alone is unstable because NeoForge dispatches mod setup in parallel. Display order is the type order, then owner
  rank across both probes, with registry recipes before spread recipes inside one owner; ids are assigned before
  that regrouping and never depend on it. `RecipeMerger` keeps the first member's id in that order and includes the
  owner and the neighbor offset in its merge keys so patterns from different mods, or at different positions, stay
  separate.
- Never call `FlowingFluid.getFlowing(level, falling)` on modded fluids: some register flowing states without the
  `LEVEL` or `FALLING` property and `setValue` throws. Build the state with `defaultFluidState().trySetValue(...)`
  as `InteractionProber` and `SceneArrangement` do.

## Verifying changes

Compile, then run the smoke test and read the screenshots. Check the log for `Probed N fluid interaction(s)`,
`Probed fluid spread of N fluid type(s)`, `Failed to bake`, and `No probe of fluid interaction ... succeeded` (debug-level, so it is in the dev run's
`run/logs/debug.log`, not `latest.log`; expected once, for the dev fallback; any other is a regression). Per-interaction failure lines are
debug so a large pack does not spam the production log; production users see failures only as "Unable to process"
recipes and through the `hideUnprocessable` config. Expect one `Merged N fluid interaction recipe(s) into M` line from `RecipeMerger`. The smoke test also logs one
`Smoke test recipe ...` line per recipe with owner, source-state and neighbor counts, `... 0 offender(s)` for the
duplicate-alternative check, and one `Smoke test merged recipe ...` line proving both merge passes collapsed the
dev-only mergeable interactions, and one `Smoke test flowing neighbor ...` line proving the cobblestone recipe
carries a flowing water neighbor, one `Smoke test spread recipe ...` line proving lava over water yields stone with
both water forms as alternatives, one `Smoke test order ...` line proving that stone recipe precedes every
third-party lava recipe, one `Smoke test form difference ...` line proving the dev fluid's recipe carries the still
form with the flowing form inert and names it in the tooltip text (an `ERROR` from any of these is a regression),
and one `Smoke test recipe ids <sha-256>` line; that hash must not change between runs of the same checkout.
`SpreadProber` logs exactly one info `Fluid spread form difference: ...` line in the dev run, for the dev fluid. The
stone and dev-fluid recipes get their own screenshots, `jei_fluid_interactions_spread.png` and
`jei_fluid_interactions_form.png`; the first should show lava directly over water then over stone. Crop and
enlarge screenshots with `sips` or PIL when a detail matters.
