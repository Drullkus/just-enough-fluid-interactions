# Just Enough Fluid Interactions

A NeoForge 1.21.1 mod with one feature: a JEI plugin that shows fluid interactions as recipes. It shows every
`FluidInteractionRegistry` entry, the hardening rules that fluids implement in their own spread code, and the
rules that liquid block subclasses implement in their update hooks. Vanilla's lava-over-water stone is an example
of the second kind: it lives in `LavaFluid.spreadTo`, not in the registry. The plugin discovers all three kinds by
running them in a sandbox level. It then settles every hit in that sandbox with the semantics of a real level, and
renders the results as 3D scenes with Gander.

## Language: ASD-STE100

All writing must follow ASD-STE100, Simplified Technical English. This applies to text in the mod (lang strings,
config comments, tooltips, failure texts), to documentation in the code (javadoc, comments, commit messages, this
file), and to every message to the user, including agent reports and handoff notes. The rules that matter most:

- Present tense and active voice. One topic per sentence. About 20 words or fewer per sentence.
- No "would", "should", "could" or "might". Use "can" for possibility and "must" for requirements.
- Use STE vocabulary where it exists: "occur", "change", "different", "not". Use concrete nouns (the fluid, the
  block) and not abstractions (a level, a position, a write).
- Keep the articles. Write two short sentences and not one sentence joined by "but".
- Give agents a rendered example sentence, not a template.

## Versions

- Minecraft 1.21.1, NeoForge 21.1.249, ModDevGradle 2.0.146, Gradle wrapper 9.2.1, Java toolchain 21.
- Parchment 2024.11.17 for 1.21.1.
- JEI: the full jar, as two file ids in `build.gradle`. `compileOnly` is the oldest supported release. It is the
  minimum of the `versionRange` in `src/main/templates/META-INF/neoforge.mods.toml`. `runtimeOnly` is a current
  release. Raise the minimum only together with the compile jar.
- Test mods on the dev classpath, declared in `build.gradle` and never shipped. They exist to feed the smoke test.
  Gaia Dimension registers interactions and lava-tagged fluids. Create, Sable and Create Aeronautics are there too.
  The Bumblezone, with Resourceful Lib, is the fixture of the neighbor tier and of the cascade. Its sugar water
  hardens through the `neighborChanged` of a `LiquidBlock` subclass. The `onPlace` of its honey crystal rewrites
  the adjacent water. DivineRPG's smoldering tar is a lava-tagged fluid with registry interactions and a `spreadTo`
  copied from vanilla. It exercises the spread tier, the settle step, and the sugar water case. Biomes O' Plenty,
  with GlitchCore and TerraBlender (`localRuntime`), registers two interactions on every fluid type. One of them
  writes a block that depends on the source state. Its blood and liquid null are lava-tagged, so it widens every
  lava-tagged alternative set and adds registry recipes on the fluid types of other mods. It also supplies the first
  third-party pre-emption case: honey and royal jelly, whose own block classes answer before the registry.
  WorldEdit (`localRuntime`) is there for the user's convenience in the plain client.
  EMI (`maven.modrinth:emi`, version `emi_version` in `gradle.properties`, Modrinth's Maven in `build.gradle`) is a
  dependency of the `dev` source set alone. Only the `clientEmiTest` run launches with the classpath of that source
  set (`runs.clientEmiTest.sourceSet`). That is what keeps EMI out of every other run. A per-run
  `additionalRuntimeClasspath` loads it as a plain library, not as a mod.
- Gander `dev.compactmods.gander:{core,levels,rendering,ui}` from GitHub Packages, bundled jar-in-jar. The version
  is in `gradle.properties` (`gander_version`), currently the published `0.2.32`. The jar-in-jar range is
  `[0.2,1.0)`. Nothing resolves from the local Maven repository.
- Mod id and package are `justenoughfluidinteractions`, `us.drullk.jefi`.

## Documentation and comments

- No comments that narrate what changed, why, or which task or session produced it (no "per task 9", "fixed for
  the handoff", "added by agent", "TODO: see HANDOFF"). A comment states an invariant the code cannot express on
  its own, and nothing else. Delete narrative comments you meet while you touch a file, also the ones you did not
  write.
- Do not renumber, duplicate, or invent new task numbers anywhere: not in code, comments, commit messages, or
  branch names.
- Agents in a worktree do not edit `CLAUDE.md` themselves. Report doc-relevant findings (a task is done, an
  assumption changed, a fact here is now wrong) in your final summary. The user reconciles the docs centrally.
  This avoids conflicting edits to the same file across parallel branches.
- If your change makes a fact in `CLAUDE.md` or in a different Markdown file false, say so in your final report.
  Examples: a renamed package, a moved file, a retired workaround. Say so also when you do not edit the doc.
- `CLAUDE.md` changes only when the user asks. Keep it free of facts that go stale on their own: this mod's
  version, jar file names, commit hashes, dependency file ids. Point at the file that holds the value instead.

## Agent workflow

- Work happens on a task branch in its own worktree: one wrap-up commit, never `main`, never a push. A worktree
  has no `test-pack`. Link `test-pack/mods` to the copy in the main checkout before a pack run. A fresh `run/`
  directory also gets the default client config, and `hideUnprocessable` is true there. The dev smoke test then
  shows no failure recipe, logs two ERROR lines, and gives a different hash. Copy
  `run/config/justenoughfluidinteractions-client.toml` from the main checkout before you compare with the numbers
  in the handoff.
- "Compiles" is not "done." Before you report a task complete: `./gradlew build`, then `./gradlew runClientJeiTest`,
  read the screenshots, and check the run log. The log must have `Probed N fluid interaction(s)` and no `Failed to
  bake`. The `No probe of fluid interaction ... succeeded` lines must be only the expected ones. They are debug
  level, so they are in the dev run's `run/logs/debug.log` and not in `latest.log`. The expected ones are the
  dev-only `minecraft:water#0` fallback, the dev-only pre-empted `minecraft:lava` fixture, and Biomes O' Plenty's
  interactions on `the_bumblezone:honey` and `the_bumblezone:royal_jelly` in both forms, six in all. The smoke
  test's `Smoke test recipe ... : 0 source state(s)` lines are the same evidence at info. Then
  `./gradlew runClientGroundTest` must report `0 mismatch(es)`, and `./gradlew runClientEmiTest` must report the
  same recipe count as the JEI run.

## Credentials

GitHub Packages credentials are Gradle properties in the user's global `~/.gradle/gradle.properties`.
Never read, print, or probe that file or those values.

## Commands

- Build the jar: `./gradlew build`. The output is `build/libs/justenoughfluidinteractions-<mod_version>.jar`, with
  the version from `gradle.properties`, and includes `META-INF/jarjar/`. Jars of earlier versions stay in
  `build/libs` until cleaned.
- Compile only: `./gradlew compileJava compileDevJava`.
- Smoke test in a real client: `./gradlew runClientJeiTest`. It deletes the previous test save, creates a flat
  world, opens the category, writes `run/screenshots/jei_fluid_interactions_*.png`, and exits. It takes about 30 s.
  Read the PNGs to verify the rendering.
- Smoke test through EMI: `./gradlew runClientEmiTest`. Same world and JEI plugin, driven through `EmiApi` (EMI
  1.1.24 beside JEI, EMI's JEMI bridge running the plugin). It writes `run/screenshots/emi_fluid_interactions_*.png`,
  logs `EMI test ...` lines, and exits. About 30 s. Read the PNGs: the plus signs, the arrow and both scenes must be
  in place.
- Ground the probes in a real level: `./gradlew runClientGroundTest`. Same world, no screenshots, always exits 0.
  It places every alternative of every recipe JEI holds on a bedrock slab in the integrated server's level, the
  source last. It waits past the fluid tick delays and compares the results and the catalysts. About 35 s. The
  `Grounded N recipe alternative(s) of M recipe(s): K mismatch(es)` line is the signal. A mismatch means that the
  sandbox and a real level disagree. Each mismatch is logged as `Grounding mismatch <id> ...` at ERROR.
- Plain client: `./gradlew runClient`.
- The stress pack: `./gradlew runClientPackTest` runs the JEI smoke test in the pack at `test-pack/mods`. That
  folder is gitignored. It is a copy of the Prism instance "JEFI Stress Test". The run copies the pack into
  `test-pack/run/mods` without this mod's jar and without the two Aether jars. A bundled mixin of the Aether
  targets a compiled lambda name, and the recompiled Minecraft of a dev run does not have that name. The run logs
  the pack's `Probed ...` lines and its `Smoke test recipe ids` hash. The dev checks log ERROR lines there, and
  those lines mean nothing in a pack. The run takes about 3 min. `./gradlew runClientPack` is a plain client in
  the same pack. FML picks the newest copy of a mod that is on the dev classpath and also in the pack, so the dev
  copy of the pack is not the Prism instance.
- A profile: `-Pjfr=<file>` on either pack run records a JFR profile. A recording keeps 64 frames per stack by
  default, and the probe's stacks are deeper. Set `JAVA_TOOL_OPTIONS="-XX:FlightRecorderOptions=stackdepth=192"`
  for the run, because the run task passes the environment to the game. Print the recording with
  `jfr print --stack-depth 192 --events jdk.ExecutionSample`, because `jfr print` cuts every stack to five frames
  by default. Keep the `main` thread samples whose stack holds `registerRecipes`. That frame isolates the plugin.
  The scripts `dev/jfr-agg.py`, `dev/jfr-agg-scopes.py`, `dev/jfr-agg-buckets.py`, `dev/jfr-tree.py` and
  `dev/jfr-tree-window.py` do this over the printed text.
- The machine's default JDK is 25. This project's wrapper is fine with it. Normal builds never build Gander. If you
  build Gander from source, its Gradle 8.11 needs `JAVA_HOME` pointed at a JDK 21.

## Source sets

- `src/main`: the mod. Plugin code is under `us/drullk/jefi/jei/` (`probe`, `sandbox`, `scene` packages). In
  `probe`, `InteractionProber` is only the coordinator: `probeAll` is the list of steps to read first.
  `RegistryProber` is the registry tier. `RuleProber` is the base of `SpreadProber` and `NeighborProber`.
  `Candidates` holds the candidate lists and `sourceStates(type)`. `RecipeIds` holds the orderings and the id
  format. `RunMemo` holds what earlier runs read at a target. `TypePredicate` reads the fluid type that NeoForge's
  own predicate captures. `RuleProber.Sweep` is the runs of one source form at one target. It holds the fixed
  scene and the memo. `SandboxLevel.ORIGIN` is where every tier puts the source.
- `src/dev`: development-only classes, bound to the mod for runs and never packaged. `JeiAutoTest` is gated by the
  system property `justenoughfluidinteractions.jeiautotest` (`clientJeiTest` run config), `EmiAutoTest` by
  `justenoughfluidinteractions.emiautotest` (`clientEmiTest`), and `GroundAutoTest` by
  `justenoughfluidinteractions.groundtest` (`clientGroundTest`). The fixtures below register under any of the three
  (`AutoTestWorld.FIXTURES`). `AutoTestWorld` holds the world creation, the onboarding dismissal and the screenshot
  code that all three share. `TickSteps` runs each test as a list of steps on the client tick, one step at a time.
  `EmiAutoTestSteps` holds every EMI reference, so no other run loads EMI classes. `GroundCheck` builds one recipe
  alternative in a real level and compares it. The compile classpath extends `main`'s `compileOnly`, so the smoke
  test compiles against the same JEI as the mod. `JeiAutoTestInteractions` registers dev-only interactions.
  `JeiAutoTestFluids` registers two dev-only fluids. `hardening_brine` hardens lava-tagged fluids below it, in
  source form only. `dyed_water` has no spread code of its own. It is water-tagged through
  `src/dev/resources/data/minecraft/tags/fluid/water.json` with `required: false`, because runs without the test
  property never register it. It has its own interaction with lava beside it. It has a second interaction with a
  pointed dripstone beside it, which waterlogs the dripstone. That is the fixture for a fluid that shares a cell
  with an offset block. `JeiAutoTestInteractions` also registers a lava interaction with a water neighbor. Vanilla's
  earlier lava-plus-water interaction pre-empts it: the fixture for the pre-emption failure text. A `Fluid` claims
  its registry holder in its constructor, so dev fluids are built inside `RegisterEvent`, never statically.
  `PackRun` clears `SharedConstants.IS_RUNNING_IN_IDE` for the pack runs. NeoForge's gametest scan loads the
  gametest classes of every mod in a dev run, and the tests of a pack mod can reference mods the pack lacks.
  `build.gradle` declares the source set as `sourceSets { dev }`. If you name `src/dev/java` or
  `src/dev/resources` again, every dev resource goes to `processDevResources` twice.
- `src/main/resources/META-INF/accesstransformer.cfg`: the only AT. It opens `FluidInteractionRegistry.INTERACTIONS`.
- `src/main/resources/assets/justenoughfluidinteractions/lang/en_us.json`: all translations.
- `us.drullk.jefi.Config`: the client config (`hideUnprocessable`, `ignoredMods`, `forceProbe`), registered from
  the constructor of the main class. `FluidInteractionsJeiPlugin.filter` applies the first two after the probe.
  `SpreadProber` and `NeighborProber` read the third. The file is `config/justenoughfluidinteractions-client.toml`.

## Conventions and gotchas

- The recipe layout uses only JEI API that every viewer that reads it implements. EMI's JEMI bridge runs JEI
  plugins with builders of its own. TMRV ("Too Many Recipe Viewers") stubs JEI 19.27's API for EMI. So
  `FluidInteractionCategory` uses the builder's `addRecipePlusSign()` and `addRecipeArrow()`, because the
  `...Widget()` forms postdate the minimum JEI. It centres them by hand with the two-argument `setPosition`, because
  the aligning overload is abstract in newer JEI and EMI's placeable lacks it. It never uses
  `mezz.jei.common.Internal`. It never sets a fluid renderer, because TMRV throws for a slot that has one and holds
  a block. A builder whose `getRecipeSlots()` is null (EMI) cannot position widgets, route input, or ask widgets
  for tooltips. So its scenes become `SceneDrawable`s and its failure text a `TextDrawable`. The scene tooltip
  lives in the category's `getTooltip`, which JEI and EMI both call with recipe-relative mouse coordinates.
  `SceneWidget`'s own tooltip is only the rotate hint. The category's `draw` keeps the slot view it is drawn with,
  per recipe, so a static scene shows the same alternatives as the tooltip. Under EMI only
  `IRecipeCategory.handleInput` is called. It is deprecated for removal since JEI 19.6.0, and JEI 19.53, EMI and
  TMRV still call it. A JEI that drops it removes EMI's click path. So the category keeps a per-recipe
  `SceneRotation` for its static drawables. Under TMRV, scenes show the first alternative only and rotate by click.
- `InertFormIndicator` is an `IRecipeWidget` per source and neighbor input slot. It is created only when one of
  the fluids of that slot has an inert other form recorded in `FluidInteractionRecipe.inert`. It reads the displayed
  fluid each frame and draws a yellow "!" at the top-left of the slot. JEI itself draws a small triple bar there
  for about a second before a slot cycles to its next ingredient. The indicator has no tooltip of its own. The
  slot's yellow line is the explanation.
- Slot roles decide what recipe viewers count as a cost. A placement is INPUT only when a result is written at its
  offset and none of its alternatives is a flowing fluid. A flowing fluid costs nothing, because its source block
  survives. Everything else is CATALYST. JEI still finds a catalyst under "uses". EMI leaves it out of its cost tree.
- No mixins. Access transformers are acceptable. FML applies ATs on NeoForge's own classes at runtime, but they are
  **not visible at compile time** under ModDevGradle. Use a reflective lookup (see `RegisteredInteractions`). A
  transformer that opens a vanilla method which NeoForge's `BaseFlowingFluid` overrides fails the compile of every
  `BaseFlowingFluid` subclass in this project, because the override stays protected in the compile artifact.
  `SpreadProber` opens `beforeDestroyingBlock` with a `MethodHandle` for that reason.
- The sandbox level reports `isClientSide == false` on purpose, so interactions guarded on the server side run.
  Anything from Gander that assumes a client level must be overridden on `SandboxLevel` (model data already is).
  The sandbox has two modes. Quiet: a write only lands in storage and is recorded. That is for a tier that calls
  one rule's hook by hand. Live (`setLive`): `setBlock` mirrors a server level. An identical state is a no-op.
  Then come the old state's `onRemove`, the new state's `onPlace`, the neighbor notification in vanilla order, and
  the shape updates. The notification goes through the sandbox's own `CollectingNeighborUpdater`, because Gander's
  has a chain limit of zero. Scheduled fluid ticks go to a queue on a virtual clock, drained by trigger time,
  priority and order. Block scheduled ticks need a `ServerLevel` and are only counted. NeoForge's
  `NeighborNotifyEvent` is not fired. There are no block entities and no random ticks. An entity query answers
  with no entities, and a spawn is denied. The sandbox keeps a fluid state beside each block, so it hands back
  exactly the state a tier placed, not what the legacy block round-trips to. Its random source starts every
  arrangement from one fixed seed, so a hook that reads `level.getRandom()` gives the same answer in every launch.
  It records writes while a tier asks for them. It records reads only when the search of the registry tier asks,
  because a read costs an allocation. `watch(pos)` tells a tier which kinds of read hit one position. `reset`
  trims a table that a settle grew, so the clear of a small run stays small. While an arrangement settles,
  `floor(y)` puts the floor of the level at the lowest placed position. A fluid that pours off the arrangement
  stops there and does not flood the level below. Nothing on the floor reaches the arrangement back, because
  only air stands between. The
  system property `jefi.settleMargin` adds walls around a settle at that distance, for experiments. A negative
  value, the default, adds none. Walls at distance 0 change results: the cells beside an arrangement send the
  neighbor update that fires the `neighborChanged` rule of the liquid block itself. The sandbox also counts live
  writes, neighbor updates and fluid ticks. The coordinator logs the totals after each tier at debug as
  `After <tier>: settled ...`.
- Fluids without a block never enter the probe (`FluidBlocks.hasBlock`: a fluid whose default state's
  `createLegacyBlock()` is air, or a type none of whose fluids has one). Registry interactions keyed on such a type
  are skipped with a debug line per type and one debug summary. Every candidate list in every tier holds only fluids
  with a block. So no recipe and no alternative can name a fluid that a level cannot hold.
- A registry interaction whose every settled arrangement is pre-empted becomes a failure recipe. Its text states the
  observation from the first pre-empted arrangement as two sentences. `Texts.preempted` takes the source form, the
  neighbor form, the block the interaction makes, and the block the source changes into in the world. Example: "This
  interaction would change Lava next to Water into Blackstone. However in the world, the Lava changes into Obsidian."
  When the source stays as it was: "This interaction would change Honey Fluid next to Blood into Flesh. However in the
  world, the Honey Fluid does not change." An interaction whose arrangements settle without a result keeps "Unable to
  process". Both are failure recipes, and `hideUnprocessable` hides both.
- `SceneBakery` pushes and pops the pose around each block tesselation. Vanilla's
  `ModelBlockRenderer.tesselateBlock` translates by the block's random model offset without a pop. The fluid drawn
  at the same position afterwards must start from the unoffset pose.
- `GuiGraphics.enableScissor` in 1.21.1 takes absolute GUI coordinates. The recipe widget's pose is translated to
  the widget origin, so read `pose.m30()/m31()` for the absolute position.
- Everything in `scene/` runs on the render thread. Vertex buffers must be created and closed there.
- Scenes are orthographic. They rotate by drag or by click. A click that never became a drag turns the yaw 90
  degrees, left and right click in opposite directions, with the pitch unchanged. The display flow height is
  `SceneArrangement.DISPLAY_FLOW_LEVEL` (the probe keeps level 7). The default camera angle is
  `SceneRenderer.DEFAULT_YAW`/`DEFAULT_PITCH`. A custom rotate cursor plugs into `SceneCursor.handle()`.
- Interaction owners come from `InteractionOwners`: the lambda's declaring class, then the namespace of captured
  registry objects. A spread-discovered rule is owned by its fluid (`ofFluid`: the fluid's class, then its registry
  namespace). A third-party mod that uses `InteractionInformation`'s convenience constructors with only vanilla
  blocks still reads as `neoforge`. Treat attribution as approximate.
- `SpreadProber` is the second discovery tier. It ticks the still and flowing source states of each fluid type at the
  origin with one candidate below or beside. It keeps writes that are not air and not a state of the source fluid.
  Only fluids whose own classes declare one of `tick`, `spread`, `spreadTo`, `canSpreadTo`, `getNewLiquid`,
  `beforeDestroyingBlock` below `FlowingFluid` and NeoForge's `BaseFlowingFluid` are ticked. Vanilla's fluids always
  qualify, because `beforeDestroyingBlock` is abstract in `FlowingFluid`. The other fluids can only place themselves.
  A rule added from outside a fluid's classes, such as a mixin into `FlowingFluid`, needs the fluid in `forceProbe`.
  Fluids that declare none of `tick`, `spread`, `canSpreadTo` are offered only the blocks vanilla's gate admits
  (`LiquidBlockContainer` or `!blocksMotion()`). Every fluid candidate is always offered. A fluid whose own classes
  declare `beforeDestroyingBlock` alone spreads as vanilla does, except inside that hook. The tier calls the hook
  directly with each candidate instead of a whole tick. The hook runs for candidates that a tick never reaches, and
  the settle step drops those. A source form whose own classes declare no hook is probed because its other form does.
  Its inherited code writes only own states, so the tier records it inert without a run. Below is the canonical
  target: the registry's own javadoc tests every direction except down and defers down-interaction changes to
  `FlowingFluid#spreadTo`. NeoForge issue 1880 closed the stone request as intended. The tier also records, per
  recipe, the source and neighbor forms it tried at the same arrangement that produced nothing
  (`FluidInteractionRecipe.inert`). That is always `InertForms.NONE` for registry recipes, where each form has its own
  recipe.
- `NeighborProber` is the third discovery tier. A fluid is probed when the block of one of its states declares
  `neighborChanged`, `onPlace` or `updateShape` in its class chain below `LiquidBlock`. The answer is cached per block
  class. `forceProbe` adds exceptions. Per source form, per fluid candidate in both forms, at below, beside and above,
  it calls `handleNeighborChanged` and `onPlace` on the source's block state. The candidate's own hooks are never
  called. A plain `LiquidBlock` runs the registry, which is the job of the first tier. Every other block is probed as
  a source in its own turn. The owner comes from `InteractionOwners.ofBlock`.
- All three tiers only generate candidates. Each calls one rule by hand in the sandbox's quiet mode. What that
  rule alone wrote is the sign that an arrangement is worth building. `Settler` then builds every hit in the live
  sandbox: `Fixtures` first, then the content, then the source. The source goes last because a level runs the hooks
  of the placed block before it notifies the neighbors. The fluid placed last is the one every tier models as its
  source. The settler runs the fluid tick queue for `Settler.SETTLE_TICKS` virtual ticks. The recipe's results
  are the settled diff over the positions of the arrangement plus the rule's own write positions. A result is
  never air, never what was placed, and never a state of a fluid the arrangement poured. The arrangement stays with
  the tier that proposed it only where the settled level still holds what that rule wrote. A fluid counts as itself
  at any level. Anything different there is the outcome of a different channel and belongs to that channel's own
  recipe. So the alternative is dropped with a debug `A level pre-empts ...` line that names what stands there.
  Positions the level changed beyond the rule's writes stay in the recipe as further results. An example is a
  result block that rewrites a neighbor in its own `onPlace`. Settling is deterministic, so two tiers that propose
  the same arrangement get the same answer, and the attribution of the first tier wins. Nothing is cached across
  tiers. `RuleProber` keeps the settled result of every arrangement of the type in progress, so the inert check
  settles nothing twice. The cross-tier dedup keys on the physical arrangement (sources, alternatives, neighbor
  offset, conditions), never on results. `Fixtures` is shared with `GroundCheck`. It puts bedrock under anything
  that can fall. For every placement recorded flowing, it puts a still source of the same fluid on the side away
  from the arrangement. A flowing fluid with nothing that feeds it drains within a tick. Fixtures never
  enter a recipe.
- `RunMemo` skips the candidate runs that an earlier run answers for. Between two candidates at one target, the
  level differs only at that target. A hook that does not read the target gives every candidate the same answer.
  A hook that reads only the fluid state there gives the same answer to every candidate with that fluid state.
  Every block candidate holds the empty fluid state, so one block run answers for all of them. When the shared
  answer of a sweep changes nothing, the sweep stops. The settled map of `RuleProber` holds only the arrangements
  that a hook writes in. The registry tier also reads the fluid type that NeoForge's own predicate captures
  (`TypePredicate`: the type constructors of `InteractionInformation` build one comparison) and sweeps the fluids
  of that type first. It sweeps every candidate only when none of them passes.
- Text about the behavior of a different mod states the observation only, never a judgment: no "bug", "incomplete",
  "missing", "should", "likely". Only that mod's developer knows the intent. The inert-form lines ("%s spreads without
  interacting") are yellow, so they stand out from the gray descriptive lines. The matching `Fluid spread form
  difference` log line is at debug. It is the line a mod author greps for.
- JEI slots accept only still fluids. Map flowing states with `FluidInteractionRecipe.stillForm`. Neighbor
  placements carry the fluid form the probe verified (`Placement.isFlowing`, described as "Flowing <fluid>"). When
  a recipe holds both forms of one fluid, the scene draws the flowing one beside the source
  (`SceneArrangement.neighborIndex`). A recipe whose neighbor is above or below (`neighborOffset`) is drawn as two
  still blocks with no feeding sources, because nothing flows upward.
- JEI shows two recipes per page at the smoke test's window size and GUI scale 2.
- A fresh `run/` directory (every new worktree) has no `options.txt`, so the client opens the accessibility
  onboarding screen before the title screen. `JeiAutoTest` dismisses it itself. If a smoke test sits idle with no
  `Smoke test` log line, check that dismissal first. The test deletes its save before it creates it, so re-runs
  never load an existing world. A loaded world stops on the experimental-world backup prompt.
- Recipe ids are `justenoughfluidinteractions:<type namespace>/<type path>/<owner>/<n>/<variant>` for registry
  interactions, `justenoughfluidinteractions:spread/<type namespace>/<type path>/<owner>/<n>/<variant>` for
  spread-discovered ones and `justenoughfluidinteractions:neighbor/<type namespace>/<type path>/<owner>/<n>/<variant>`
  for update-hook ones. They must stay unique and stable across launches, because JEI uses them for bookmarks.
  Fluid types rank `minecraft`, `neoforge`, then other namespaces alphabetically, then path
  (`RecipeIds.LOCATION_ORDER`, never `ResourceLocation`'s path-first natural order). Within a type, registry
  interactions are grouped by owner in the same ranking and numbered (`n`): successes first, then the result
  block's key, then the registration index. Spread recipes number `n` by target position (below, then beside).
  Neighbor recipes number `n` by below, beside, above. Both number `variant` by outcome, so a cascade that changes
  an outcome can move a variant number. The registration index alone is unstable, because NeoForge dispatches mod
  setup in parallel. Display order is the type order, then the owner rank across all tiers, with registry, then
  spread, then neighbor recipes inside one owner. Ids are assigned before that regrouping and never depend on it.
  `RecipeMerger` keeps the first member's id in that order. It includes the owner and the neighbor offset in its
  merge keys, so patterns from different mods, or at different positions, stay separate.
- Never call `FlowingFluid.getFlowing(level, falling)` on modded fluids. Some register flowing states without the
  `LEVEL` or `FALLING` property, and `setValue` throws. Build the state with `defaultFluidState().trySetValue(...)`
  as `Candidates` and `SceneArrangement` do.

## Verifying changes

Compile, then run the smoke test and read the screenshots. Check the log for `Probed N fluid interaction(s)`, `Probed
fluid spread of N fluid type(s)` and `Probed fluid neighbors of N fluid type(s)`. The `debug.log` adds one `Probed
fluid spread of <type> ...` and one `Probed fluid neighbors of <type> ...` line per probed type, one `Skipped the
fluid spread of N of M fluid type(s)` and one `Skipped the fluid neighbors of N of M fluid type(s)` line, one `Settled
N arrangement(s) in T ms; B block scheduled tick(s) were asked for ...` line, and one `A level pre-empts ...` line per
dropped alternative and source form. In dev, both forms of `dyed_water` and of sugar water leave the stone recipe, and
both forms of sugar water leave the tar recipe. Each is logged once for still lava and once for flowing lava. Check
for `Failed to bake` and for `No probe of fluid interaction ... succeeded`. The last is debug level, so it is in the
dev run's `run/logs/debug.log`, not in `latest.log`. It is expected for the six interactions listed under "Agent
workflow". Any other one is a regression. Per-interaction failure lines are debug, so a large pack does not spam the
production log. Production users see failures only as "Unable to process" recipes or as the pre-emption observation,
and through the `hideUnprocessable` config. Expect these lines, all at debug. One `Skipped N fluid interaction(s) on M
fluid type(s) whose fluids have no block` line. In dev, those are BOP's interactions on milk and Create's potion and
tea. One `A level pre-empts N of the M arrangement(s) of fluid interaction ...` line per fully pre-empted interaction:
the dev lava fixture, and BOP's honey and royal jelly in both forms. Two `Fluid spread form difference: ...` lines
from `SpreadProber`, for the dev fluid and for DivineRPG's tar. One `Fluid spread candidates: ...` line with the
candidate counts. One `Skipped N candidate run(s) of the fluid interactions whose predicate did not read the neighbor`
line. One `Skipped N candidate run(s) of the <tier> whose hook did not read the target` line per rule tier. One `Swept
the predicate's own fluid type first in N sweep(s) ...` line. One `Probing the fluid spread of <fluid> through
beforeDestroyingBlock alone ...` line per source form on that path. In dev, those are both forms of water, of BOP's
blood and of its liquid null, and the still form of DivineRPG's tar. Three `After <tier>: settled ...` lines with the
settle counters. At info, one `Merged N fluid interaction recipe(s) into M` line from `RecipeMerger` and the three
`Probed ...` summary lines. Every dev run also logs one `RegisterSpawnPlacementsEvent` ERROR from NeoForge's server
lifecycle hooks about the entities of a test mod. That line is noise.

The smoke test also logs these lines. An `ERROR` from any of these checks is a regression.

- One `Smoke test recipe ...` line per recipe with the owner, the source-state count and the neighbor count, and
  `... 0 offender(s)` for the duplicate-alternative check.
- One `Smoke test merged recipe ...` line. It proves that both merge passes collapsed the dev-only mergeable
  interactions.
- One `Smoke test flowing neighbor ...` line. It proves that the cobblestone recipe carries a flowing water neighbor.
- One `Smoke test spread recipe ...` line. It proves that lava over water yields stone with both water forms as
  alternatives.
- One `Smoke test order ...` line. It proves that the stone recipe precedes every third-party lava recipe.
- One `Smoke test form difference ...` line. It proves that the dev fluid's recipe carries the still form with the
  flowing form inert, and names that form in the tooltip text.
- One `Smoke test pre-empted ...` line. It proves that `dyed_water` is absent from the stone recipe's alternatives
  and that its own two recipes exist.
- One `Smoke test indicator ...` line. It proves that the dev fluid's recipe shows the "!" on its source slot.
- One `Smoke test neighbor recipe ...` line per sugar water neighbor recipe. Those are the four hardening ones, stone
  from the source form and cobblestone from the flowing form, beside and above, and the above-position ones that BOP's
  blood and liquid null add. The checks find recipes by result, source form and neighbor offset, never by variant
  number or alternative count.
- Two `Smoke test neighbor pre-empted ...` lines. They prove that sugar water is absent from the stone and tar
  spread recipes.
- Two `Smoke test neighbor registry pre-empted ...` lines, one per hardening result. They prove that lava is absent
  from sugar water's beside recipe and present in its above recipe, and that the two differ by exactly lava's two
  forms.
- One `Smoke test own rule ...` line. It proves that no recipe carries the result of a different rule.
- One `Smoke test cascade ...` line. It proves that the honey recipe with still water above carries both the
  crystal and the sugar water the crystal writes.
- One `Smoke test pre-empted interaction ...` line that quotes the failure text of the dev lava fixture, and one
  `Smoke test pre-empted third-party interaction ...` line that quotes BOP's honey one.
- One `Smoke test offset recipe ...` line. It proves that the dripstone condition is recorded waterlogged.
- One `Smoke test recipe ids <sha-256>` line. That hash must not change between runs of the same checkout.

The EMI run must log `EMI test found N recipe(s)` with the same N as the JEI run's recipe count, five `EMI test
screenshot` lines, one `EMI test clicked the left scene ...` line, and no `Exception adding JEMI extras`. The
grounding run must log `Grounded N recipe alternative(s) of M recipe(s): 0 mismatch(es)` (failure recipes are not
grounded), with `0 of them holding a fluid no level can hold` in the `Ground test found ...` line, and no
`Grounding mismatch` line. The stone, dev-fluid, sugar water, honey, pre-empted and offset recipes get their own
screenshots: `jei_fluid_interactions_spread.png`, `_form.png`, `_neighbor.png`, `_cascade.png`, `_preempted.png`
and `_offset.png`. The first must show lava directly over water, then over stone. The cascade one must show two
output slots. The pre-empted one must show the source slot over the wrapped observation text. The offset one must
show a grid-aligned water cube around the offset dripstone. Crop and enlarge screenshots with `sips` or PIL when a
detail matters.
