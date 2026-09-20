# Just Enough Fluid Interactions

A NeoForge 1.21.1 mod whose only feature is a JEI plugin that displays fluid interactions as recipes: every
`FluidInteractionRegistry` entry, the hardening rules fluids implement in their own spread code (vanilla's
lava-over-water stone lives in `LavaFluid.spreadTo`, not the registry), and the rules liquid block subclasses
implement in their update hooks. All three are discovered by running them in a sandbox level, every hit is then
settled in that sandbox with a real level's semantics, and the results are rendered as 3D scenes with Gander.

## Versions

- Minecraft 1.21.1, NeoForge 21.1.249, ModDevGradle 2.0.146, Gradle wrapper 9.2.1, Java toolchain 21.
- Parchment 2024.11.17 for 1.21.1.
- JEI: the full jar, as two file ids in `build.gradle`. `compileOnly` is the oldest supported release, the minimum
  of the `versionRange` in `src/main/templates/META-INF/neoforge.mods.toml`; `runtimeOnly` is a current release.
  Raise the minimum only together with the compile jar.
- Test mods on the dev classpath, declared in `build.gradle` and never shipped: Gaia Dimension (registers
  interactions and lava-tagged fluids), Create, Sable, Create Aeronautics, The Bumblezone with Resourceful Lib
  (its sugar water hardens through a `LiquidBlock` subclass's `neighborChanged`, the neighbor tier's fixture, and
  its honey crystal's `onPlace` rewrites adjacent water, the cascade fixture), DivineRPG (smoldering tar: a
  lava-tagged fluid with registry interactions and a `spreadTo` copied from vanilla, so it exercises the spread
  tier, settling, and the sugar water case from above). They exist to feed the smoke test.
  EMI (`maven.modrinth:emi`, version `emi_version` in `gradle.properties`, Modrinth's Maven in `build.gradle`) is a
  dependency of the `dev` source set alone, and only the `clientEmiTest` run launches with that source set's
  classpath (`runs.clientEmiTest.sourceSet`), which is what keeps EMI out of every other run; a per-run
  `additionalRuntimeClasspath` would load it as a plain library, not a mod.
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
- "Compiles" is not "done." Before reporting a task complete: `./gradlew build`, then `./gradlew runClientJeiTest`, read the resulting screenshots, and check the run log for `Probed N fluid interaction(s)`, the absence of `Failed to bake`, and that the only `No probe of fluid interaction ... succeeded` line is the dev-only `minecraft:water#0` fallback (that line is debug-level, so it is in the dev run's `run/logs/debug.log` and not in `latest.log`, and is expected exactly once; the smoke test's `Smoke test recipe ... (from justenoughfluidinteractions): 0 source state(s)` line for the fallback recipe is the same evidence at info). Then `./gradlew runClientGroundTest` must report `0 mismatch(es)` and `./gradlew runClientEmiTest` the same recipe count as the JEI run.

## Credentials

GitHub Packages credentials are Gradle properties in the user's global `~/.gradle/gradle.properties`.
Never read, print, or probe that file or those values.

## Commands

- Build the jar: `./gradlew build` (output `build/libs/justenoughfluidinteractions-<mod_version>.jar`, version from
  `gradle.properties`, includes `META-INF/jarjar/`; jars of earlier versions stay in `build/libs` until cleaned).
- Compile only: `./gradlew compileJava compileDevJava`.
- Smoke test in a real client: `./gradlew runClientJeiTest`. Deletes any previous test save, creates a flat world, opens the category, writes
  `run/screenshots/jei_fluid_interactions_*.png`, exits. Takes about 30 s. Read the PNGs to verify rendering.
- Smoke test through EMI: `./gradlew runClientEmiTest`. Same world and JEI plugin, driven through `EmiApi` (EMI 1.1.24
  beside JEI, EMI's JEMI bridge running the plugin), writes `run/screenshots/emi_fluid_interactions_*.png`, logs
  `EMI test ...` lines, exits. About 30 s. Read the PNGs: plus signs, arrow and both scenes must be in place.
- Ground the probes in a real level: `./gradlew runClientGroundTest`. Same world, no screenshots, always exits 0.
  Places every alternative of every recipe JEI holds on a bedrock slab in the integrated server's level, the
  source placed last, waits past the fluid tick delays, and compares results and catalysts. About 35 s. The
  `Grounded N recipe alternative(s) of M recipe(s): K mismatch(es)` line is the signal; a mismatch means the
  sandbox and a real level disagree, and each one is logged as `Grounding mismatch <id> ...` at ERROR.
- Plain client: `./gradlew runClient`.
- The machine's default JDK is 25; this project's wrapper is fine with it. Normal builds never build Gander; if you
  ever build it from source, its Gradle 8.11 needs `JAVA_HOME` pointed at a JDK 21.

## Source sets

- `src/main`: the mod. Plugin code under `us/drullk/jefi/jei/` (`probe`, `sandbox`, `scene` packages).
- `src/dev`: development-only classes bound to the mod for runs, never packaged. `JeiAutoTest` is gated by the
  system property `justenoughfluidinteractions.jeiautotest` (`clientJeiTest` run config), `EmiAutoTest` by
  `justenoughfluidinteractions.emiautotest` (`clientEmiTest`), `GroundAutoTest` by
  `justenoughfluidinteractions.groundtest` (`clientGroundTest`), and the fixtures below register under any of them
  (`AutoTestWorld.FIXTURES`). `AutoTestWorld` holds the world creation, onboarding dismissal and screenshot code
  all three share; `EmiAutoTestSteps` holds every EMI reference, so no other run loads EMI classes; `GroundCheck`
  builds one recipe alternative in a real level and compares it. The compile
  classpath extends `main`'s `compileOnly`, so the smoke test compiles against the same JEI as the mod. `JeiAutoTestInteractions`
  registers dev-only interactions and `JeiAutoTestFluids` two dev-only fluids: `hardening_brine` (hardens
  lava-tagged fluids below it in source form only) and `dyed_water` (no spread code of its own, water-tagged
  through `src/dev/resources/data/minecraft/tags/fluid/water.json` with `required: false`, since runs without the
  test property never register it; it has its own interaction with lava beside it). A `Fluid` claims its registry
  holder in its constructor, so dev fluids are built inside `RegisterEvent`, never statically. `build.gradle`
  declares the source set as `sourceSets { dev }`; naming `src/dev/java` or `src/dev/resources` again feeds every
  dev resource to `processDevResources` twice.
- `src/main/resources/META-INF/accesstransformer.cfg`: the only AT; opens `FluidInteractionRegistry.INTERACTIONS`.
- `src/main/resources/assets/justenoughfluidinteractions/lang/en_us.json`: all translations.
- `us.drullk.jefi.Config`: the client config (`hideUnprocessable`, `ignoredMods`, `forceProbe`), registered
  from the main class constructor; `FluidInteractionsJeiPlugin.filter` applies the first two after probing,
  `SpreadProber` and `NeighborProber` read the third. File `config/justenoughfluidinteractions-client.toml`.

## Conventions and gotchas

- The recipe layout is drawn only through JEI API that every viewer reading it implements: EMI's JEMI bridge
  runs JEI plugins with builders of its own, and TMRV ("Too Many Recipe Viewers") stubs JEI 19.27's API for EMI.
  So `FluidInteractionCategory` uses the builder's `addRecipePlusSign()`/`addRecipeArrow()` (the `...Widget()`
  forms postdate the minimum JEI) centred by hand with the two-argument `setPosition` (the aligning overload is
  abstract in newer JEI and EMI's placeable lacks it), never `mezz.jei.common.Internal`, never a fluid renderer
  (TMRV throws for a slot that has one and holds a block). A builder whose `getRecipeSlots()` is null (EMI) cannot
  position widgets, route input or ask widgets for tooltips, so scenes become `SceneDrawable`s and failure text a
  `TextDrawable`; the scene tooltip lives in the category's `getTooltip`, which JEI and EMI both call with
  recipe-relative mouse coordinates, and `SceneWidget`'s own tooltip is only the rotate hint. The category's `draw`
  stashes the slot view it is drawn with per recipe so a static scene shows the same alternatives as the tooltip.
  Under EMI only `IRecipeCategory.handleInput` is called (deprecated for removal since JEI 19.6.0 but called by
  JEI 19.53, EMI and TMRV; a JEI dropping it removes EMI's click path), so the category keeps a per-recipe
  `SceneRotation` for its static drawables; under TMRV scenes show the first alternative only and rotate by click.
- `InertFormIndicator` is an `IRecipeWidget` per source and neighbor input slot, created only when one of that
  slot's fluids has an inert other form recorded in `FluidInteractionRecipe.inert`; it reads the displayed fluid
  each frame and draws a yellow "!" at the slot's top-left, where JEI itself draws a small triple bar for about a
  second before a slot cycles to its next ingredient. It has no tooltip of its own; the slot's yellow line is the
  explanation.
- Slot roles decide what recipe viewers count as a cost: a placement is INPUT only when a result is written at its
  offset and none of its alternatives is a flowing fluid (a flowing fluid costs nothing, its source block
  survives); everything else is CATALYST, which JEI still finds under "uses" and EMI leaves out of its cost tree.
- No mixins. Access transformers are acceptable. ATs on NeoForge's own classes are applied at runtime by FML but are
  **not visible at compile time** under ModDevGradle; use a reflective lookup (see `RegisteredInteractions`).
- The sandbox level reports `isClientSide == false` on purpose so interactions guarded on the server side run. Anything
  from Gander that assumes a client level must be overridden on `SandboxLevel` (model data already is). The sandbox
  has two modes. Quiet: a write only lands in storage and is recorded, for a tier calling one rule's hook by hand.
  Live (`setLive`): `setBlock` mirrors a server level (an identical state is a no-op, the old state's `onRemove`,
  then the new state's `onPlace`, then neighbor notification in vanilla order through the sandbox's own
  `CollectingNeighborUpdater`, since Gander's has a chain limit of zero, then shape updates), and scheduled fluid
  ticks go to a queue drained by trigger time, priority and order on a virtual clock. Block scheduled ticks need a
  `ServerLevel` and are only counted; NeoForge's `NeighborNotifyEvent` is not fired; no block entities, entities
  or random ticks. Bucket-only fluids are stored as a fluid state without a block.
- `GuiGraphics.enableScissor` in 1.21.1 takes absolute GUI coordinates; the recipe widget's pose is translated to the
  widget origin, so read `pose.m30()/m31()` for the absolute position.
- Everything in `scene/` runs on the render thread. Vertex buffers must be created and closed there.
- Scenes are orthographic and rotate by drag or by click (a click that never became a drag turns the yaw 90
  degrees, left and right click opposite ways, pitch unchanged); the display flow height is `SceneArrangement.DISPLAY_FLOW_LEVEL`
  (probing keeps level 7), the default camera angle is `SceneRenderer.DEFAULT_YAW`/`DEFAULT_PITCH`, and a custom
  rotate cursor would plug into `SceneCursor.handle()`.
- Interaction owners come from `InteractionOwners`: the lambda's declaring class, then the namespace of captured
  registry objects; a spread-discovered rule is owned by its fluid (`ofFluid`: the fluid's class, then its registry
  namespace). A third-party mod using `InteractionInformation`'s convenience constructors with only vanilla
  blocks still reads as `neoforge`; treat attribution as approximate.
- `SpreadProber` is the second discovery tier: it ticks each fluid type's still and flowing source states at the
  origin with one candidate below or beside and keeps writes that are neither air nor a state of the source fluid.
  Only fluids whose own classes, below `FlowingFluid` and NeoForge's `BaseFlowingFluid`, declare one of `tick`,
  `spread`, `spreadTo`, `canSpreadTo`, `getNewLiquid`, `beforeDestroyingBlock` are ticked (vanilla's always are,
  `beforeDestroyingBlock` being abstract in `FlowingFluid`); the rest can only place themselves. A rule added from
  outside a fluid's classes, such as a mixin into `FlowingFluid`, needs the fluid listed in `forceProbe`.
  Fluids declaring none of `tick`, `spread`, `canSpreadTo` are offered only the blocks vanilla's gate admits
  (`LiquidBlockContainer` or `!blocksMotion()`); every fluid candidate is always offered.
  Below is the canonical target: the registry's own javadoc tests every direction except down and defers
  down-interaction changes to `FlowingFluid#spreadTo` (NeoForge issue 1880 closed the stone request as intended).
  It also records, per recipe, the source and neighbor forms it tried at the same arrangement that produced
  nothing (`FluidInteractionRecipe.inert`, always `InertForms.NONE` for registry recipes, where each form has its
  own recipe).
- `NeighborProber` is the third discovery tier: a fluid is probed when the block of one of its states declares
  `neighborChanged`, `onPlace` or `updateShape` in its class chain below `LiquidBlock` (cached per block class;
  `forceProbe` adds exceptions). Per source form, per fluid candidate in both forms, at below, beside and above,
  it calls `handleNeighborChanged` and `onPlace` on the source's block state; the candidate's own hooks are never
  called, since a plain `LiquidBlock` would run the registry (the first tier's job) and any other block is probed as
  a source in its own turn. Owner via `InteractionOwners.ofBlock`.
- All three tiers only generate candidates: each calls one rule by hand in the sandbox's quiet mode, and what that
  rule alone wrote is the sign that an arrangement is worth building. `Settler` then builds every hit in the live
  sandbox (`Fixtures` first, then the content, the source last, because a level runs the placed block's own hooks
  before it notifies neighbors, and the fluid placed last is the one every tier models as its source), runs the
  fluid tick queue for `Settler.SETTLE_TICKS` virtual ticks, and takes the settled diff over the arrangement's
  positions plus the rule's own write positions (never air, never what was placed, never a state of a fluid the
  arrangement poured) as the recipe's results. The arrangement stays with the tier that proposed it only where the
  settled level still holds what that rule wrote (a fluid counts as itself at any level); anything else there is
  another channel's outcome and belongs to that channel's own recipe, so the alternative is dropped with a debug
  `A level pre-empts ...` line naming what stands there instead. Positions the level changed beyond the rule's
  writes, such as a result block rewriting a neighbor in its own `onPlace`, stay in the recipe as further results.
  Settles are cached per arrangement, so two tiers proposing the same one get the same answer and the first tier's
  attribution wins; the cross-tier dedup keys on the physical arrangement (sources, alternatives, neighbor offset,
  conditions), never on results. `Fixtures` is shared with `GroundCheck`: bedrock under anything that would fall,
  and for every placement recorded flowing a still source of the same fluid on the side away from the
  arrangement, because a flowing fluid with nothing feeding it drains within a tick. Fixtures never enter a recipe.
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
  interactions, `justenoughfluidinteractions:spread/<type namespace>/<type path>/<owner>/<n>/<variant>` for
  spread-discovered ones and `justenoughfluidinteractions:neighbor/<type namespace>/<type path>/<owner>/<n>/<variant>`
  for update-hook ones, and must stay unique and stable across launches; JEI uses them for bookmarks. Fluid types
  rank `minecraft`, `neoforge`, then other namespaces alphabetically, then path (`InteractionProber.LOCATION_ORDER`,
  never `ResourceLocation`'s path-first natural order). Within a type, registry interactions are grouped by owner in
  the same ranking and numbered (`n`) with successes first, then the result block's key, then registration index;
  spread recipes number `n` by target position (below, then beside), neighbor recipes by below, beside, above, and
  both `variant` by outcome, so a cascade that changes an outcome can move a variant number. Registration index
  alone is unstable because NeoForge dispatches mod setup in parallel. Display order is the type order, then owner
  rank across all tiers, with registry, then spread, then neighbor recipes inside one owner; ids are assigned before
  that regrouping and never depend on it. `RecipeMerger` keeps the first member's id in that order and includes the
  owner and the neighbor offset in its merge keys so patterns from different mods, or at different positions, stay
  separate.
- Never call `FlowingFluid.getFlowing(level, falling)` on modded fluids: some register flowing states without the
  `LEVEL` or `FALLING` property and `setValue` throws. Build the state with `defaultFluidState().trySetValue(...)`
  as `InteractionProber` and `SceneArrangement` do.

## Verifying changes

Compile, then run the smoke test and read the screenshots. Check the log for `Probed N fluid interaction(s)`,
`Probed fluid spread of N fluid type(s)` and `Probed fluid neighbors of N fluid type(s)` (`debug.log` adds one
`Probed fluid spread of <type> ...` and one `Probed fluid neighbors of <type> ...` line per probed type, one
`Skipped the fluid spread of N of M fluid type(s)` and one `Skipped the fluid neighbors of N of M fluid type(s)`
line, one `Settled N arrangement(s) in T ms, reusing R already settled; B block scheduled tick(s) were asked for
...` line, and one `A level pre-empts ...` line per dropped alternative: in dev, both forms of `dyed_water` and of
sugar water leave the stone recipe, both forms of sugar water leave the tar recipe, each logged twice because the
inert re-probe settles them again), `Failed to bake`, and `No probe of fluid interaction ... succeeded`
(debug-level, so it is in the dev run's `run/logs/debug.log`, not `latest.log`; expected once, for the dev
fallback; any other is a regression). Per-interaction failure lines are debug so a large pack does not spam the
production log; production users see failures only as "Unable to process" recipes and through the
`hideUnprocessable` config. Expect one `Merged N fluid interaction recipe(s) into M` line from `RecipeMerger` and
two info `Fluid spread form difference: ...` lines from `SpreadProber`, for the dev fluid and for DivineRPG's tar.
The smoke test also logs one `Smoke test recipe ...` line per recipe with owner, source-state and neighbor
counts, `... 0 offender(s)` for the duplicate-alternative check, one `Smoke test merged recipe ...` line proving
both merge passes collapsed the dev-only mergeable interactions, one `Smoke test flowing neighbor ...` line
proving the cobblestone recipe carries a flowing water neighbor, one `Smoke test spread recipe ...` line proving
lava over water yields stone with both water forms as alternatives, one `Smoke test order ...` line proving that
stone recipe precedes every third-party lava recipe, one `Smoke test form difference ...` line proving the dev
fluid's recipe carries the still form with the flowing form inert and names it in the tooltip text, one `Smoke
test pre-empted ...` line proving `dyed_water` is absent from the stone recipe's alternatives while its own two
recipes exist, one `Smoke test indicator ...` line proving the dev fluid's recipe shows the "!" on its source
slot, four `Smoke test neighbor recipe ...` lines for sugar water's recipes (stone from the source form and
cobblestone from the flowing form, beside and above), two `Smoke test neighbor pre-empted ...` lines proving sugar
water is absent from the stone and tar spread recipes, one `Smoke test neighbor registry pre-empted ...` line
proving lava is absent from sugar water's beside recipes and present in its above recipes, one `Smoke test own
rule ...` line proving no recipe carries another rule's result, one `Smoke test cascade ...` line proving the
honey recipe with still water above carries both the crystal and the sugar water the crystal writes (an `ERROR`
from any of these is a regression), and one `Smoke test recipe ids <sha-256>` line; that hash must not change
between runs of the same checkout. The EMI run must log `EMI test found N recipe(s)` with the same N as the JEI
run's recipe count, five `EMI test screenshot` lines, one `EMI test clicked the left scene ...` line and no
`Exception adding JEMI extras`. The grounding run must log `Grounded N recipe alternative(s) of M recipe(s): 0
mismatch(es)` (the dev fallback recipe is not grounded, and alternatives whose fluid has no block are skipped and
counted in the `Ground test found ...` line) and no `Grounding mismatch` line. The stone, dev-fluid, sugar water
and honey recipes get their own screenshots, `jei_fluid_interactions_spread.png`, `_form.png`, `_neighbor.png`
and `_cascade.png`; the first should show lava directly over water then over stone, the last two output slots.
Crop and enlarge screenshots with `sips` or PIL when a detail matters.
