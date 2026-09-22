# Just Enough Fluid Interactions

A NeoForge 1.21.1 mod with one feature: a JEI plugin that shows fluid interactions as recipes.
It shows three kinds of rule:

- every `FluidInteractionRegistry` entry;
- the hardening code a fluid implements in its own spread code, such as vanilla's stone in
  `LavaFluid.spreadTo`;
- the code a `LiquidBlock` subclass implements in its update hooks.

The plugin finds all three by running them in a sandbox level. It then settles every hit in
that sandbox with the semantics of a real level. Gander renders the results as 3D scenes.

## Language: ASD-STE100

All writing follows ASD-STE100, Simplified Technical English: text in the mod, javadoc,
comments, commit messages, this file, the handoff, and every message to the user.

- Present tense and active voice. One topic per sentence. About 20 words or fewer per sentence.
- No "would", "should", "could" or "might". Use "can" for possibility and "must" for requirements.
- Use concrete nouns (the fluid, the block), not abstractions (a level, a position, a write).
- Keep the articles. Write two short sentences and not one sentence joined by "but".
- Give agents a rendered example sentence, not a template.
- In this file: one fact per bullet, nested lists instead of paragraphs, lines under 100 characters.

## Versions

- Minecraft 1.21.1, NeoForge 21.1.249, ModDevGradle 2.0.146, Gradle wrapper 9.2.1, Java 21.
- Parchment 2024.11.17 for 1.21.1.
- JEI is two file ids in `build.gradle`.
  - `compileOnly` is the oldest supported release, the minimum of the `versionRange` in
    `src/main/templates/META-INF/neoforge.mods.toml`.
  - `runtimeOnly` is a current release.
  - Raise the minimum only together with the compile jar.
- Gander `dev.compactmods.gander:{core,levels,rendering,ui}` from GitHub Packages, jar-in-jar.
  - The version is `gander_version` in `gradle.properties`. The jar-in-jar range is `[0.2,1.0)`.
  - Nothing resolves from the local Maven repository.
- EMI (`maven.modrinth:emi`, version `emi_version` in `gradle.properties`).
  - A dependency of the `dev` source set alone.
  - Only the `clientEmiTest` run uses that source set's classpath. That keeps EMI out of
    every other run.
- Test mods on the dev classpath, declared in `build.gradle`, never shipped. They feed the
  smoke test.
  - Gaia Dimension: interactions and lava-tagged fluids.
  - Create, Sable, Create Aeronautics.
  - The Bumblezone with Resourceful Lib: the fixture of the neighbor tier and of the cascade.
    Its sugar water hardens in a `LiquidBlock` subclass's `neighborChanged`. Its honey
    crystal's `onPlace` rewrites adjacent water.
  - DivineRPG: smoldering tar, a lava-tagged fluid with registry interactions and a `spreadTo`
    copied from vanilla.
  - Biomes O' Plenty with GlitchCore and TerraBlender (`localRuntime`): two interactions on
    every fluid type. Its blood and liquid null are lava-tagged. Honey and royal jelly are the
    first third-party pre-emption case.
  - WorldEdit (`localRuntime`): for the plain client.
- Mod id `justenoughfluidinteractions`, package `us.drullk.jefi`.

## Documentation

- A comment states an invariant the code cannot express, and nothing else.
  - No comment narrates what changed, why, or which task or session produced it.
  - Delete such comments when you meet them.
- Do not renumber, duplicate or invent task numbers anywhere.
- The coordinating session writes `CLAUDE.md` and `dev/HANDOFF.md`.
  - An agent in a worktree edits neither.
  - It reports every doc-relevant finding in its final report: a task is done, an assumption
    changed, a fact here is now wrong.
- Keep this file free of facts that go stale on their own: the mod version, jar names, commit
  hashes, dependency file ids, recipe counts, timings. Point at the file that holds the value.
  Numbers belong in the handoff.

## Agent workflow

- Work happens on a task branch in its own worktree.
  - One wrap-up commit per task. Never on `main`. Never a push.
  - The user cherry-picks onto `main`.
- A worktree has no `test-pack`. Link `test-pack/mods` to the main checkout's copy before a
  pack run.
- Remove a worktree when its task is complete, so the branch is the only record of the change.
  - First look for changes under gitignored paths: `dev/`, `run/`, `test-pack/`.
  - Those disappear with the worktree. Report them to the coordinating session and to the
    user before you remove anything.
- "Compiles" is not "done". Before you report a task complete:
  - `./gradlew build`;
  - the three dev runs of "Commands";
  - every check of "Verifying changes".

## Credentials

GitHub Packages credentials are Gradle properties in the user's global
`~/.gradle/gradle.properties`. Never read, print or probe that file or those values.

## Commands

- `./gradlew build`: the jar in `build/libs`, with `META-INF/jarjar/`. Old jars stay there.
- `./gradlew compileJava compileDevJava`: compile only.
- `./gradlew runClientJeiTest`: the JEI smoke test, about 30 s.
  - Deletes the previous test save, creates a flat world, opens the category, exits.
  - Writes `run/screenshots/jei_fluid_interactions_*.png`. Read the PNGs.
- `./gradlew runClientEmiTest`: the same through EMI's JEMI bridge, about 30 s.
  - Writes `run/screenshots/emi_fluid_interactions_*.png` and logs `EMI test ...` lines.
- `./gradlew runClientGroundTest`: the grounding run, about 35 s, always exits 0.
  - Rebuilds every recipe alternative on a bedrock slab in the integrated server's level, the
    source last, waits past the fluid tick delays, and compares.
  - The signal is the `Grounded ... mismatch(es)` line.
- `./gradlew runClient`: the plain client.
- `./gradlew runClientPackTest`: the JEI smoke test in the stress pack, about 3 min.
  - The pack is `test-pack/mods`, a gitignored copy of the Prism instance "JEFI Stress Test".
  - The run copies it into `test-pack/run/mods` without this mod's jar and without the two
    Aether jars, whose bundled mixin targets a compiled lambda name a dev run does not have.
  - The dev checks can log ERROR lines there. They mean nothing in a pack.
  - FML picks the newest copy of a mod that is both on the dev classpath and in the pack, so
    the dev copy of the pack is not the Prism instance.
  - `./gradlew runClientPack` is the plain client in the pack.
- A profile: `-Pjfr=<file>` on a pack run records a JFR profile.
  - Set `JAVA_TOOL_OPTIONS="-XX:FlightRecorderOptions=stackdepth=192"` for the run. A
    recording keeps 64 frames per stack by default, and the probe's stacks are deeper.
  - Print with `jfr print --stack-depth 192 --events jdk.ExecutionSample`. `jfr print` cuts
    every stack to five frames by default.
  - The scripts `dev/jfr-*.py` aggregate the printed text. They keep the `main` thread and
    the `jefi-probe-*` threads.
- The machine's default JDK is 25; the wrapper is fine with it. Only a Gander source build
  needs a JDK 21 in `JAVA_HOME`.

## Source sets

### `src/main`

Plugin code is under `us/drullk/jefi/jei/` in `probe`, `sandbox` and `scene`.

- `InteractionProber`: the coordinator. Read `probeAll` first.
- `RegistryProber`: the registry tier.
- `RuleProber`: the base of `SpreadProber` and `NeighborProber`. `RuleProber.Sweep` holds the
  fixed scene and the memo of one source form at one target.
- `Candidates`: the candidate lists and `sourceStates(type)`.
- `RunMemo`: what earlier runs read at a target.
- `TypePredicate`: the fluid type that NeoForge's own predicate captures.
- `RecipeIds`: the orderings and the id format.
- `SandboxLevel.ORIGIN`: where every tier puts the source.
- `META-INF/accesstransformer.cfg`: the only AT. It opens `FluidInteractionRegistry.INTERACTIONS`.
- `assets/justenoughfluidinteractions/lang/en_us.json`: all translations.
- `us.drullk.jefi.Config`: the client config, file `config/justenoughfluidinteractions-client.toml`.
  - `hideUnprocessable`, `ignoredMods`: applied in `FluidInteractionsJeiPlugin.filter` after
    the probe.
  - `probeThreads`, `probeStallSeconds`: size and guard the pool.
  - `forceProbe`: adds fluids to the spread and neighbor tiers.

### `src/dev`

Development-only classes bound to the mod for runs, never packaged.

- `JeiAutoTest`, `EmiAutoTest`, `GroundAutoTest`: gated by the system properties
  `justenoughfluidinteractions.jeiautotest`, `.emiautotest`, `.groundtest`.
- `AutoTestWorld`: the world creation, the onboarding dismissal, the screenshots, and the
  `FIXTURES` flag, true under any of the three properties.
- `TickSteps`: runs each test as a list of steps on the client tick.
- `EmiAutoTestSteps`: every EMI reference, so no other run loads EMI classes.
- `GroundCheck`: builds one recipe alternative in a real level and compares it.
- `AutoTestConfig`: sets `hideUnprocessable` to false in memory when the client config loads
  in a test run. The file on disk keeps its value.
- `PackRun`: clears `SharedConstants.IS_RUNNING_IN_IDE` for pack runs. NeoForge's gametest
  scan loads every mod's gametest classes in a dev run.
- `JeiAutoTestInteractions`: the dev-only interactions.
- `JeiAutoTestFluids`: two dev-only fluids. A `Fluid` claims its registry holder in its
  constructor, so they are built inside `RegisterEvent`.
  - `hardening_brine`: hardens lava-tagged fluids below it, source form only.
  - `dyed_water`: no spread code; water-tagged through
    `src/dev/resources/data/minecraft/tags/fluid/water.json` with `required: false`; one
    interaction with lava beside it; one with a pointed dripstone that it waterlogs.
- `build.gradle` declares `sourceSets { dev }`. Naming `src/dev/java` or `src/dev/resources`
  again feeds every dev resource to `processDevResources` twice.

## Conventions and gotchas

### Recipe viewers

- The layout uses only JEI API that every viewer implements.
  - EMI's JEMI bridge runs JEI plugins with builders of its own.
  - TMRV ("Too Many Recipe Viewers") stubs JEI 19.27's API for EMI.
- `FluidInteractionCategory` therefore:
  - uses `addRecipePlusSign()` and `addRecipeArrow()`; the `...Widget()` forms postdate the
    minimum JEI;
  - centres them with the two-argument `setPosition`; the aligning overload is abstract in
    newer JEI and absent in EMI;
  - never uses `mezz.jei.common.Internal`;
  - never sets a fluid renderer; TMRV throws for a slot with one that holds a block.
- A builder whose `getRecipeSlots()` is null (EMI) cannot position widgets, route input or
  ask for tooltips.
  - Scenes then become `SceneDrawable`s, the failure text a `TextDrawable`.
  - The scene tooltip lives in the category's `getTooltip`. JEI and EMI call it with
    recipe-relative mouse coordinates. `SceneWidget`'s own tooltip is only the rotate hint.
  - The category's `draw` keeps the slot view per recipe, so a static scene shows the same
    alternatives as the tooltip.
- Under EMI only `IRecipeCategory.handleInput` is called. It is deprecated since JEI 19.6.0;
  JEI 19.53, EMI and TMRV still call it. So the category keeps a per-recipe `SceneRotation`.
- Under TMRV scenes show the first alternative and rotate by click.
- `InertFormIndicator`: an `IRecipeWidget` per input slot whose fluid has an inert other form
  in `FluidInteractionRecipe.inert`. It draws a yellow "!" at the slot's top-left each frame.
  JEI draws a small triple bar there before a slot cycles. The slot's yellow line explains it.
- Slot roles decide what viewers count as a cost.
  - INPUT: a result is written at the placement's offset and no alternative is a flowing
    fluid. A flow costs nothing; its source block survives.
  - CATALYST: everything else. JEI finds it under "uses"; EMI leaves it out of its cost tree.
- JEI slots accept only still fluids. Map flowing states with `FluidInteractionRecipe.stillForm`.
  - Neighbor placements carry the form the probe verified (`Placement.isFlowing`, "Flowing
    <fluid>").
  - A fluid verified in its flowing form is drawn flowing, with a still source beside it, because a
    flow costs no source block (`SceneArrangement`).
  - In a vertical pair, the block the interaction changes keeps that preference. The block that only
    triggers it is drawn still whenever its still form was verified. A vertical flow is fed from the
    south. When both blocks flow, the neighbor is fed from the west. Nothing flows upward.
- JEI shows two recipes per page at the smoke test's window size and GUI scale 2.
- JEI lists bookmarked recipes first. Not a bug, and no API hook to opt out.

### Scenes

- Everything in `scene/` runs on the render thread. Vertex buffers are created and closed there.
- `SceneBakery` pushes and pops the pose around each block tesselation. Vanilla's
  `ModelBlockRenderer.tesselateBlock` translates by the block's random model offset without a
  pop. The fluid drawn afterwards must start from the unoffset pose.
- `GuiGraphics.enableScissor` in 1.21.1 takes absolute GUI coordinates. The widget's pose is
  translated to the widget origin, so read `pose.m30()/m31()`.
- Scenes are orthographic. They rotate by drag or by click.
  - A click that never became a drag turns the yaw 90 degrees, left and right in opposite
    directions.
  - The display flow height is `SceneArrangement.DISPLAY_FLOW_LEVEL`; the probe keeps level 7.
  - The default camera is `SceneRenderer.DEFAULT_YAW` and `DEFAULT_PITCH`.
  - A custom rotate cursor plugs into `SceneCursor.handle()`.
- The occlusion fix has no dev reproduction. If it regresses, check the second depth clear at
  the end of `SceneRenderer.draw`.

### Build

- No mixins. Access transformers are acceptable.
  - FML applies ATs on NeoForge's own classes at runtime. They are not visible at compile time
    under ModDevGradle. Use a reflective lookup (`RegisteredInteractions`).
  - An AT on a vanilla method that `BaseFlowingFluid` overrides fails the compile of every
    `BaseFlowingFluid` subclass here. `SpreadProber` opens `beforeDestroyingBlock` with a
    `MethodHandle` for that reason.
- Never call `FlowingFluid.getFlowing(level, falling)` on modded fluids. Some register flowing
  states without `LEVEL` or `FALLING`, and `setValue` throws. Build the state with
  `defaultFluidState().trySetValue(...)`, as `Candidates` and `SceneArrangement` do.
- A fresh `run/` directory has no `options.txt`, so the client opens the accessibility
  onboarding screen. `JeiAutoTest` dismisses it. A smoke test that sits idle with no `Smoke
  test` line is that dismissal.
- The test deletes its save first. A loaded world stops on the experimental-world backup prompt.

### The sandbox

- `SandboxLevel` reports `isClientSide == false`, so server-guarded interactions run. Anything
  from Gander that assumes a client level is overridden there; model data already is.
- Two modes.
  - Quiet: a write only lands in storage and is recorded. For a tier that calls one hook by hand.
  - Live (`setLive`): `setBlock` mirrors a server level. An identical state is a no-op. Then
    the old state's `onRemove`, the new state's `onPlace`, neighbor notification in vanilla
    order through the sandbox's own `CollectingNeighborUpdater` (Gander's has a chain limit of
    zero), then shape updates. Scheduled fluid ticks go to a queue on a virtual clock.
- What the sandbox does not do.
  - Block scheduled ticks need a `ServerLevel`; they are counted only.
  - `NeighborNotifyEvent` is not fired. No block entities. No random ticks.
  - An entity query answers with no entities. A spawn is denied and counted per entity type.
  - The game rules turn block drops off. A dropped item is an entity the sandbox denies anyway.
- The sandbox keeps a fluid state beside each block, so it hands back the state a tier
  placed, not what the legacy block round-trips to.
- Its random source restarts from one fixed seed for every arrangement. A hook that reads
  `level.getRandom()` answers the same in every launch.
- Reads are recorded only for the registry tier's search. `watch(pos)` tells a tier which
  kinds of read hit one position. `reset` trims a table a settle grew.
- While an arrangement settles, `floor(y)` puts the floor at the lowest placed position. A
  fluid that pours off stops there. Nothing on the floor reaches the arrangement back.
  - The system property `jefi.settleMargin` adds side walls at that distance, for experiments
    only. Walls at distance 0 change results: the cells beside an arrangement send the
    neighbor update that fires a liquid block's own `neighborChanged`.
- The thread that uses a sandbox constructs it. C2ME binds a level's random source to the
  constructing thread and rejects every other thread.

### The probe

- The pool. One fluid type is one task.
  - Each tier runs its types on `probeThreads` threads: default every processor but one, at
    most eight.
  - Each thread owns a sandbox, a settler and its probers.
  - Results assemble in type order, so the ids never depend on the thread count.
  - A tier that throws on the pool runs again on the calling thread, and every later tier too.
  - A type that one thread holds longer than `probeStallSeconds` counts as such a failure. The
    case is a hook that waits for the render thread, which waits for the pool.
- Fluids without a block never enter the probe (`FluidBlocks.hasBlock`). Every candidate list
  holds only fluids with a block.
- The registry tier (`RegistryProber`), per source form:
  - the fluid at `ORIGIN`, the predicate and the action with every fluid candidate in both
    forms beside it, then with every block;
  - a greedy multi-position search when nothing fired; the sandbox records what the predicate
    reads;
  - writes from the predicate count too;
  - NeoForge's own type predicate passes only for its type (`TypePredicate`), so those fluids
    go first.
- `RunMemo`. Between two candidates at one target, the level differs only there.
  - A hook that never reads the target answers for every candidate.
  - A hook that reads only the fluid state answers for every candidate with that fluid state,
    which is every block.
  - When the shared answer changes nothing, the sweep stops.
- The spread tier (`SpreadProber`).
  - It ticks the still and flowing source states with one candidate below or beside. It keeps
    writes that are not air and not a state of the source fluid.
  - Only fluids whose own classes, below `FlowingFluid` and `BaseFlowingFluid`, declare
    `tick`, `spread`, `spreadTo`, `canSpreadTo`, `getNewLiquid` or `beforeDestroyingBlock` are
    ticked. `forceProbe` adds fluids.
  - A fluid that declares none of `tick`, `spread`, `canSpreadTo` gets only the blocks
    vanilla's gate admits.
  - A fluid whose only own code is `beforeDestroyingBlock` gets that hook called directly per
    candidate.
  - A form whose classes declare no hook is inert without a run.
  - Below is the canonical target. The registry's javadoc defers the down direction to
    `spreadTo` (NeoForge issue 1880).
  - The tier records the forms tried without effect (`FluidInteractionRecipe.inert`). Registry
    recipes always have `InertForms.NONE`.
- The neighbor tier (`NeighborProber`).
  - A fluid is probed when its block declares `neighborChanged`, `onPlace` or `updateShape`
    below `LiquidBlock`.
  - It calls `handleNeighborChanged` and `onPlace` on the source's block state with one fluid
    candidate below, beside or above.
  - It never calls the candidate's hooks. A plain `LiquidBlock` runs the registry. Any other
    block is probed as a source in its own turn.
- The settle step (`Settler`). All tiers only generate candidates.
  - It builds every hit live: `Fixtures` first, then the content, then the source. A level
    runs the placed block's hooks before it notifies neighbors.
  - It runs the fluid ticks for `Settler.SETTLE_TICKS`.
  - The results are the settled diff over the arrangement plus the rule's write positions:
    never air, never what was placed, never a state of a poured fluid.
  - The arrangement stays with its tier only where the settled level still holds what the
    rule wrote; a fluid counts as itself at any level. Otherwise it is dropped with a debug
    `A level pre-empts ...` line.
  - Extra changed positions stay as further results: cascades.
  - Settling is deterministic. The first tier's attribution wins. Nothing is cached across
    tiers.
- `Fixtures`, shared with `GroundCheck`: bedrock under anything that falls, and a still source
  feeding every flowing placement from the side away from the arrangement. Fixtures never
  enter a recipe.
- Owners (`InteractionOwners`): the lambda's declaring class, then the namespace of captured
  registry objects. A spread rule is owned by its fluid, a neighbor rule by its block. A mod
  using `InteractionInformation`'s convenience constructors with vanilla blocks reads as
  `neoforge`. Attribution is approximate.
- Failure recipes.
  - An interaction whose every arrangement is pre-empted states the observation
    (`Texts.preempted`): "This interaction would change Lava next to Water into Blackstone.
    However in the world, the Lava changes into Obsidian." When the source stays: "... However
    in the world, the Honey Fluid does not change."
  - An interaction that settles without a result keeps "Unable to process".
  - `hideUnprocessable` hides both.
- Text about another mod's behavior states the observation only: no "bug", "incomplete",
  "missing", "should", "likely". The inert-form lines are yellow. The `Fluid spread form
  difference` line is the one a mod author greps for.

### Recipe ids

- Formats, all with `justenoughfluidinteractions:` in front:
  - registry: `<type ns>/<type path>/<owner>/<n>/<variant>`;
  - spread: `spread/<type ns>/<type path>/<owner>/<n>/<variant>`;
  - neighbor: `neighbor/<type ns>/<type path>/<owner>/<n>/<variant>`.
- Ids stay unique and stable across launches. JEI uses them for bookmarks.
- Types rank `minecraft`, `neoforge`, other namespaces alphabetically, then path
  (`RecipeIds.LOCATION_ORDER`, never `ResourceLocation`'s path-first order).
- Inside a type:
  - registry interactions group by owner in that ranking and number `n`: successes first, then
    the result block's key, then registration index;
  - spread recipes number `n` by target (below, beside); neighbor recipes by below, beside,
    above; both number `variant` by outcome;
  - registration index alone is unstable, because NeoForge dispatches mod setup in parallel.
- Display order is type, then owner rank across tiers, then registry, spread, neighbor. Ids
  are assigned before that regrouping.
- `RecipeMerger` keeps the first member's id. It keys on the owner and the neighbor offset too.

## Verifying changes

Run the smoke test and read the screenshots. `latest.log` has the info lines; the debug lines
are in `run/logs/debug.log`. An `ERROR` from a smoke-test check is a regression.

### Probe lines at info

- `Probed N fluid interaction(s)`, `Probed fluid spread of N fluid type(s)`,
  `Probed fluid neighbors of N fluid type(s)`.
- `Merged N fluid interaction recipe(s) into M`.
- One `RegisterSpawnPlacementsEvent` ERROR from NeoForge about a test mod's entities: noise.

### Probe lines at debug

- `Probing on N thread(s)`.
- One `Probed fluid spread of <type>` and one `Probed fluid neighbors of <type>` per probed
  type, from the `jefi-probe-*` threads.
- The two `Skipped the ... of N of M fluid type(s)` lines.
- `Fluid spread candidates: ...`.
- The `Skipped N candidate run(s)` lines of all three tiers.
- `Swept the predicate's own fluid type first ...`.
- One `Probing the fluid spread of <fluid> through beforeDestroyingBlock alone` per source form
  on that path. In dev: both forms of water, of BOP's blood and liquid null, and still tar.
- Three `After <tier>: settled ...` lines, then `Settled N arrangement(s)`.
- `Denied N entity spawn(s)`.
- `Skipped N fluid interaction(s) on M fluid type(s) whose fluids have no block`. In dev: BOP
  on milk, Create's potion and tea.
- One `A level pre-empts N of the M arrangement(s)` per fully pre-empted interaction. In dev:
  the dev lava fixture, BOP's honey and royal jelly in both forms.
- One `A level pre-empts ...` per dropped alternative and form. In dev: both forms of
  `dyed_water` and of sugar water leave the stone recipe, both forms of sugar water leave the
  tar recipe, each once per lava form.
- Two `Fluid spread form difference` lines: the dev fluid, DivineRPG's tar.
- `No probe of fluid interaction ... succeeded` for exactly six: the dev `minecraft:water#0`
  fallback, the dev pre-empted `minecraft:lava` fixture, and BOP's interactions on
  `the_bumblezone:honey` and `the_bumblezone:royal_jelly` in both forms.
- No `Failed to bake`.

### Smoke-test lines

One each unless stated.

- `Smoke test recipe ...` per recipe, with `0 offender(s)`.
- `merged recipe`: both merge passes collapsed the dev-only mergeable interactions.
- `flowing neighbor`: the cobblestone recipe carries a flowing water neighbor.
- `spread recipe`: lava over water gives stone, with both water forms.
- `order`: the stone recipe precedes every third-party lava recipe.
- `form difference`: the dev fluid's still form, the flowing form inert and named in the tooltip.
- `pre-empted`: `dyed_water` absent from the stone recipe, its own two recipes present.
- `indicator`: the "!" on the dev fluid's source slot.
- `neighbor recipe` per sugar water recipe, found by result, source form and offset, never by
  variant number or alternative count.
- `vertical flow`: the cobblestone neighbor recipe draws flowing sugar water fed from the south,
  still lava above it, and its flowing lava alternative fed from the west. The stone recipe draws a
  lava source over flowing water fed from the south.
- Two `neighbor pre-empted`: sugar water absent from the stone and tar spread recipes.
- Two `neighbor registry pre-empted`: lava absent beside, present above, differing by lava's
  two forms.
- `own rule`: no recipe carries another rule's result.
- `cascade`: honey with still water above carries the crystal and the sugar water it writes.
- `pre-empted interaction` and `pre-empted third-party interaction`, quoting the failure texts.
- `offset recipe`: the dripstone condition recorded waterlogged.
- `recipe ids <sha-256>`: must not change between runs of one checkout.

### The other two runs

- EMI: `EMI test found N recipe(s)` with the JEI run's count, five `EMI test screenshot`
  lines, one `EMI test clicked the left scene` line, no `Exception adding JEMI extras`.
- Grounding: `Grounded N recipe alternative(s) of M recipe(s): 0 mismatch(es)`, with `0 of
  them holding a fluid no level can hold`, and no `Grounding mismatch` line.

### Screenshots

- `jei_fluid_interactions_spread.png`: a lava source over flowing water, then over stone.
- `_form.png`. `_neighbor.png`: flowing sugar water fed from the south, a lava source above it.
- `_cascade.png`: two output slots.
- `_preempted.png`: the source slot over the wrapped observation text.
- `_offset.png`: a grid-aligned water cube around the offset dripstone.
- Crop and enlarge with `sips` or PIL when a detail matters.
