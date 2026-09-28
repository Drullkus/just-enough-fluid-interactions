# Just Enough Fluid Interactions

A NeoForge 1.21.1 mod with one feature: a JEI plugin that shows fluid interactions as recipes.
It runs three kinds of rule in a sandbox level:

- every `FluidInteractionRegistry` entry (the registry tier);
- the hardening code in a fluid's own spread code, such as `LavaFluid.spreadTo` (the spread tier);
- the update hooks of a `LiquidBlock` subclass (the neighbor tier).

The tiers only find candidates. The settle step builds every hit with the semantics of a real
level and decides the result. Gander draws the results as 3D scenes.

## Language: ASD-STE100

All writing follows ASD-STE100: text in the mod, javadoc, comments, commit messages, this file,
the handoff, and every message to the user.

- Present tense and active voice. One topic per sentence. About 20 words or fewer.
- No "would", "should", "could" or "might". Use "can" for possibility and "must" for requirements.
- Use concrete nouns (the fluid, the block). Keep the articles.
- Write two short sentences, not one sentence joined by "but".
- A comment states one invariant the code cannot express, in about 10 words. No examples, no
  justification, no history of the change.
- Text about another mod's behavior states the observation only: no "bug", "missing", "should".
- Give agents a rendered example sentence, not a template.
- In this file: one fact per bullet, lines under 100 characters.

## Rules

- Never read, print or probe `~/.gradle/gradle.properties`. It holds the GitHub Packages
  credentials.
- Work happens on a `task/<slug>` branch in its own worktree. One commit, never on `main`,
  never a push. The user cherry-picks.
- The coordinating session writes this file and `dev/HANDOFF.md`. It amends the doc delta into
  the task commit. An agent in a worktree edits neither and reports its doc findings.
- Before a worktree goes, look for changes under the gitignored `dev/`, `run/` and `test-pack/`.
  Report them first.
- Every client an agent launches is muted. All test runs must mute themselves; add `-Pmute` to
  `runClient` and `runClientPack`.
- No mixins. Access transformers are acceptable (`META-INF/accesstransformer.cfg`).
- This file holds no value that goes stale: versions, hashes, file ids, counts, timings. Point
  at the file that holds it. Numbers belong in the handoff.
- "Compiles" is not "done". Before you report a task complete: `./gradlew build`, the three dev
  runs, and every check of "Verifying changes".

## Build and runs

- Versions are in `gradle.properties` and `build.gradle`. The target is Java 21. The default
  JDK 25 runs the wrapper.
- JEI is two file ids in `build.gradle`.
  - `compileOnly` is the oldest supported release: the minimum of `versionRange` in
    `src/main/templates/META-INF/neoforge.mods.toml`. Raise them together.
  - `runtimeOnly` is a current release.
- Gander comes from GitHub Packages and ships jar-in-jar. EMI is on the `dev` source set only.
- Test mods on the dev classpath (`build.gradle`) feed the smoke test. They never ship.
- `./gradlew runClientJeiTest`, `runClientEmiTest`, `runClientGroundTest`: the three dev runs,
  about 30 s each. Each deletes its save, makes a flat world, checks, takes screenshots and exits.
- `./gradlew runClientPackTest`: the smoke test in the pack `test-pack/mods` (gitignored).
  - `-Ppack=<name>` runs `test-pack/<name>` instead. `-PpackHeap` sets the heap, default 12g.
  - `-Pshots=<text>,<text>` screenshots every recipe whose id holds a text.
  - `-Pjfr=<file>` records a profile. Print it with `jfr print --stack-depth 192`.
  - A dev-check ERROR in a pack means nothing. Run one pack client at a time.
- A worktree has no `test-pack`. Link `test-pack/mods` to the main checkout's copy.
- An existing `run/config` file keeps its values when a config default changes.

## Code map

Under `src/main/java/us/drullk/jefi/jei/`:

- `probe/InteractionProber.probeAll`: the coordinator. Read it first.
- `RegistryProber`, and `RuleProber` with `SpreadProber` and `NeighborProber`: the three tiers.
- `Settler`: builds each hit live in the sandbox. Its diff is the recipe.
- `Candidates`, `FluidBlocks`, `RunMemo`, `Fixtures`: what the tiers place, and what they skip.
- `RecipeMerger` (`mergeAcrossMods`) and `LockstepMerger` (`mergeWithinMods`): the merges.
- `RecipeIds`: the id formats and orderings. Ids stay stable across launches; JEI bookmarks
  use them.
- `sandbox/SandboxLevel`: the level of every tier and of the settle.
- `FluidInteractionCategory`, `ItemlessBlock*` and `scene/`: the JEI layout and the scenes.
- `us/drullk/jefi/Config`: the client config. `src/dev`: the smoke tests and the dev fixtures.
- Reference detail (tiers, sandbox, merges, ids, viewers) is in `dev/HANDOFF.md`, section 6b.
  `dev/` is gitignored: a worktree agent reads the main checkout's copy.

## Gotchas

- EMI's JEMI bridge and TMRV run the category with builders of their own.
  - Use only API of the minimum JEI that every viewer implements: `addRecipePlusSign()`,
    `addRecipeArrow()`, the two-argument `setPosition`.
  - Never use `mezz.jei.common.Internal`. Never set a fluid renderer: TMRV throws for one on a
    slot that holds a block.
  - `getRecipeSlots()` is null under EMI. There only `handleInput` routes clicks.
- JEI slots hold still fluids only (`FluidInteractionRecipe.stillForm`).
- JEI cycles at most 100 entries of one slot on screen. Linked slots must hold equal counts.
- EMI and TMRV show a slot whose entries hold a whole tag as one fixed tag entry. So under EMI a
  lockstep group whose linked slot holds a tag stays apart (`EmiTagView`). A custom slot renderer
  does not stop that swap. Only JEI passes a focus to `setRecipe`.
- EMI shows a slot's entries one per second, in list order. A TMRV slot does not tell which
  entry it shows, so the scene of a recipe with rows finds its row from the time.
- Which form a scene draws, still or flowing, is the user's decision. Confirm a change recipe by
  recipe first.
- `SandboxLevel` reports `isClientSide == false`. The thread that uses a sandbox builds it:
  C2ME binds a level's random source to the constructing thread.
- Never call `FlowingFluid.getFlowing(level, falling)` on a modded fluid. Build the state with
  `defaultFluidState().trySetValue(...)`.
- An AT on a NeoForge class is not visible at compile time: use reflection
  (`RegisteredInteractions`).
- An AT on a vanilla method that `BaseFlowingFluid` overrides breaks the compile of every
  subclass. `SpreadProber` uses a `MethodHandle` for that reason.
- `scene/` runs on the render thread. `SceneBakery` pops the pose after each block.
  `enableScissor` takes absolute GUI coordinates.
- Two test mods crash a dev run now and then: Create Aeronautics' `LevititeCrystallizerManager`
  and Create's Registrate. Run again.

## Verifying changes

- Read every PNG in `run/screenshots/`. Crop with `sips` or PIL when a detail matters.
- Each smoke-test check logs one `Smoke test ...` line. An ERROR from a check is a regression.
- Noise: DivineRPG's recipe parse errors and one `RegisterSpawnPlacementsEvent` line.
- `Smoke test recipe ids <sha-256>` must not change between runs of one checkout.
- EMI: its recipe count equals JEI's, and no `Exception adding JEMI extras`.
- Grounding: `Grounded N recipe alternative(s) of M recipe(s): 0 mismatch(es)`.
- The full list of expected lines and screenshots is in `dev/HANDOFF.md`, section 6b.
