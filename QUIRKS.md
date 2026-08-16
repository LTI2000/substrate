# QUIRKS

A catalog of the unusual, hacky, or otherwise notable design and implementation
decisions in this codebase. Not a bug list — everything here works as intended.
This exists so nobody (including a future me) mistakes deliberate weirdness for
an oversight.

## Language patterns

- **Sealed interface as an exhaustiveness enforcer** — `Role` is a sealed
  interface over eight behavior records (`Conduit`, `Generator`, `Mine`,
  `Converter`, `Producer`, `Buffer`, `AutoTap`, `Amplifier`), each carrying its
  own payload. `Engine.tick`, `Engine.recompute`, `Game.describe`, and
  `BoardPanel`'s status line all dispatch over it with pattern-matching
  `switch` and no `default` branch, so a new machine behavior is a compile
  error everywhere it isn't yet handled.
  (`src/main/java/substrate/Role.java:10-35`)

- **Enums as a config database** — `Machine` is one enum with 20 constants,
  each built inline via `Spec.of(...)` carrying label, cost, power draw,
  `Role`, tech gate, and flavor text. There's no external data file; the enum
  *is* the content table, and its declaration order is literally the build
  menu's order (a load-bearing comment says so).
  (`src/main/java/substrate/Machine.java:8-101`)

- **Fluent "wither" methods on an immutable record** — `Spec` exposes
  `onOre()`, `smelts()`, `research()`, each returning a new `Spec` with one
  flag flipped, chained as suffix calls at each machine definition. A
  hand-rolled stand-in for Java's record `with`-copy syntax, purely for
  readability of the enum table.
  (`src/main/java/substrate/Spec.java:15-17`)

- **Arity-overloaded builder instead of varargs** — `Cost.of(...)` has four
  hand-written overloads (0–4 resource/amount pairs) rather than a varargs or
  fluent builder, capped exactly at the largest cost table in use (`REACTOR`,
  4 resources).
  (`src/main/java/substrate/Cost.java:10-34`)

- **Prerequisite table forced out of the enum by a language limitation** — the
  class comment says it outright: "Prerequisites live in a table because an
  enum constant cannot reference its siblings from its own constructor." So
  the 26-tech dependency DAG lives in a static `EnumMap<Tech, Set<Tech>>`
  built in a static initializer, separate from the constants it governs.
  (`src/main/java/substrate/Tech.java:8-9, 50-78`)

## Odd algorithms

- **Rejection-sampling world generation** — `OreGen.survey` regenerates the
  entire ore layout up to 400 times until a 7×7 "opening" near the core has at
  least 3 iron, 2 copper, 2 coal. Brute force instead of constructive
  placement, but simple and guaranteed-playable.
  (`src/main/java/substrate/OreGen.java:21-28`)

- **Randomized flood-fill ore veins** — each vein grows by popping a random
  cell from a frontier list, stamping ore, and pushing its four neighbors
  back onto the same list — a randomized BFS/DFS hybrid that gives veins an
  organic blob shape. The frontier is never deduplicated; repeats are only
  filtered by an `ore[i] != null` check on pop.
  (`src/main/java/substrate/OreGen.java:30-58`)

- **Chebyshev distance for near/far bands** — vein placement measures
  chessboard distance from the core, not Euclidean, so the "near" and "far"
  rings are actually squares.
  (`src/main/java/substrate/OreGen.java:76-78`)

- **Largest-rectangle-in-histogram, repurposed for fusion** — `Fusion.bestRect`
  is the classic maximal-rectangle-in-a-binary-matrix algorithm, but scores
  candidates as `w*h*1000 - abs(w-h)` — area dominates, with an aspect-ratio
  tiebreak nudging toward squarer shapes.
  (`src/main/java/substrate/Fusion.java:28-58`)

- **Greedy carve-out fusion decomposition** — the board is partitioned into
  fusion groups by repeatedly carving the single best rectangle out of each
  machine-kind bitmap until nothing ≥2×2 remains, then every leftover cell
  becomes its own 1×1 group. Deliberately greedy, not globally optimal (see
  `FusionTest.greedyTakesTheBiggest`).
  (`src/main/java/substrate/Fusion.java:65-99`)

- **String concatenation as a composite map key** — machines are bucketed for
  fusion under keys like `m.name() + ":" + ore.name()` instead of a proper
  record/tuple key.
  (`src/main/java/substrate/Fusion.java:70-75`)

- **DFS-via-manual-stack for power reachability**, on a flattened 1-D grid
  with index arithmetic instead of 2-D coordinates for neighbor steps.
  (`src/main/java/substrate/Fusion.java:125-149`)

## The core economic hook, in disproportionately few lines

- **`fusionFactor(exponent) = Math.pow(area, exponent)`** — output scales
  with a non-integer, research-upgradable exponent (2.0 → 2.30 via `GEO1` /
  `GEO2`, +0.15 each). This one call is the entire game's premise.
  (`src/main/java/substrate/Group.java:32`, `src/main/java/substrate/Engine.java:36`)

- **Power draw stays deliberately linear** while output goes quadratic — an
  intentional asymmetry, called out in-line: `// draw stays linear in size:
  fusion is efficient`.
  (`src/main/java/substrate/Engine.java:134`)

- **Amplifiers compound their own fusion scaling** — an Overclock Node's
  boost to *other* groups is `boost * node.fusionFactor(expo)`, i.e. the
  amplifier amplifies proportional to its own area². The blurb says so
  directly: "Its boost scales with its own fusion."
  (`src/main/java/substrate/Engine.java:90-115`, `Machine.java:59`)

- **Exponential per-unit price inflation, computed fresh every query** —
  `priceOf` multiplies base cost by `1.14^(units already built)`, uncached.
  The same `1.14` literal is repeated independently in `demolish` rather than
  named once.
  (`src/main/java/substrate/Engine.java:63-68, 252`)

- **Demolition refund reconstructs price history** — refunding a fused block
  sums `1.14^(max(0, owned-1-k)) * cost * 0.5` for each cell in the block,
  re-deriving what each unit *would have cost* at its point in build order,
  rather than tracking actual paid cost per cell.
  (`src/main/java/substrate/Engine.java:247-258`)

- **Proportional fuel allocation across generators** — when demand exceeds
  supply, each burner's share is `made = take * (cap / capSum)`, with fuel
  consumption back-derived from the ratio — an ad hoc linear allocation done
  with plain arithmetic, using a private nested `Fuelled` record that exists
  solely for this one loop.
  (`src/main/java/substrate/Engine.java:158-173`)

- **Offline catch-up by literally re-ticking** — time away (capped at 2h) is
  replayed synchronously as `tick(0.5)` in a loop up to 14,400 times, on the
  main thread, before the window even shows. No closed-form fast-forward.
  (`src/main/java/substrate/Engine.java:286-293`)

## Persistence

- **Hand-rolled key=value save format**, no JSON, no schema — nested
  maps/arrays encoded with `,` and `:` (`res=MATTER:120.0,IRON:4.0,`), the
  whole 225-cell board as a flat comma-joined list of enum names or `-` for
  empty. Lives at `~/.substrate/site.txt`.
  (`src/main/java/substrate/Save.java`)

- **"Deliberately forgiving on read"** — the class comment says this
  outright. Every field falls back to a hardcoded default via
  `getOrDefault`, and the whole read is wrapped in a catch-all that discards
  the save and starts fresh on any exception, logged to `System.err`.
  (`src/main/java/substrate/Save.java:52-55`)

- **A `version=1` field that is written but never read** — vestigial
  forward-compat stub, currently inert.
  (`src/main/java/substrate/Save.java:17`)

- **Two independent save-on-exit paths** — both a JVM shutdown hook and a
  `windowClosing` listener call `Save.write`, redundantly, in case one
  doesn't fire.
  (`src/main/java/substrate/Main.java:32-35`)

- **`System.setProperty("sun.java2d.opengl", "false")`** as the literal first
  line of `main` — disables OpenGL-accelerated Java2D for the whole JVM, an
  environment workaround for consistent rendering.
  (`src/main/java/substrate/Main.java:10`)

## Art — all hand-drawn, no assets

- **No sprite pipeline at all.** Every one of the 20 machine types has its
  own private method (`rig`, `derrick`, `furnace`, `arm`, `assembler`,
  `reactor`, `tokamak`, …) built from raw `Path2D`/`Arc2D`/gradients with
  dozens of eyeballed magic-number proportions. ~1000 lines of procedural art
  embedded directly in application source.
  (`src/main/java/substrate/Art.java`)

- **Per-machine animation desync via a hash-based seed** —
  `seed = x*31 + y*17 + type.ordinal()*7`, fed into a noise function to
  offset each machine's clock, so identical machines never animate in
  lockstep. Recomputed every paint call.
  (`src/main/java/substrate/Art.java:22-23`)

- **Animation speed is literally coupled to simulation throughput** —
  `speed = powered ? max(0.25, grp.rate) : 0`, where `rate` is the actual
  fraction of nominal output the machine hit last tick. A browned-out site
  visibly slows its own machinery in real time.
  (`src/main/java/substrate/Art.java:24`, used e.g. line 36)

- **Deterministic hand-rolled noise instead of `java.util.Random`** —
  `Theme.noise` is a multiply-xorshift-multiply-xorshift integer hash using
  Murmur-style finalizer constants (`0x27d4eb2d`, `0x85ebca6b`), chosen so
  smoke/sparks/arcs look random but stay stable across repaints of the same
  frame.
  (`src/main/java/substrate/Theme.java`, used throughout `Art.java`)

- **A capacitor's arc effect uses a time-bucketed noise lookup** —
  `bucket = floor(t*3); if (noise(bucket*977) > 0.86)` fires the effect ~14%
  of the time per third-of-a-second, with `977` chosen arbitrarily just to
  decorrelate from other effects reusing the same noise function.
  (`src/main/java/substrate/Art.java:752-762`)

- **Runtime font-family probing with hardcoded fallback chains** — queries
  available system fonts once at class load and picks the first match from
  curated lists (`"JetBrains Mono", "IBM Plex Mono", …, "Monospaced"`) rather
  than bundling a font.
  (`src/main/java/substrate/Theme.java:38-50`)

## UI

- **Spreadsheet-style column letters via raw char arithmetic** —
  `(char) ('A' + x)` appears independently in both `Group.where()` and
  `BoardPanel`'s ruler code. Works only because the board is 15 wide (fits in
  A–O); silently breaks past 26 columns.
  (`src/main/java/substrate/Group.java:34`, `src/main/java/substrate/BoardPanel.java:284`)

- **`Board.margin()` hardcodes `15`** instead of referencing the `Board.W`
  constant sitting two lines above it — a duplicated magic number for the
  board size.
  (`src/main/java/substrate/Board.java:45`)

- **A bespoke tween system with no framework** — the core-tap "+N" floater
  and ripple are hand-timed against `System.nanoTime()`, with alpha/position
  interpolated via a manual `progress = (t - startedAt) / 0.9` fraction, all
  state stored as loose fields on `BoardPanel`.
  (`src/main/java/substrate/BoardPanel.java:50-55, 168-176, 243-251`)

- **Manual letter-by-letter text layout** — `Masthead`, `TabButton`,
  `SectionLabel`, `HintBox`, and `Manual` are bespoke `JComponent`s that
  `drawString` character-by-character for custom letter-spacing (e.g. the
  masthead spaces out "SUBSTRATE" one glyph at a time), instead of using
  Swing's built-in label/layout kerning.
  (`src/main/java/substrate/Game.java:443-643`)

- **Help text that regenerates on every repaint** — the manual's fusion
  explanation embeds `String.format("%.2f", engine.exponent())` computed
  fresh inside `sections()`, called from both `getPreferredSize()` and
  `paintComponent()` — self-updating tutorial text, recomputed far more often
  than it needs to be.
  (`src/main/java/substrate/Game.java:574-577`)

- **A static mutable field as poor-man's dependency injection** —
  `ItemRow.stock` is a package-visible `static Board` set once by `Game`'s
  constructor, purely so a static helper can check affordability without
  threading a `Board` reference through the `Model` interface.
  (`src/main/java/substrate/ItemRow.java:125`)

- **Abandoning a site copies array contents in place rather than replacing
  the Engine** — `Game.abandon()` builds a throwaway fresh `Engine`, then
  `System.arraycopy`s its board arrays onto the *live* board and resets every
  other mutable field one by one — presumably to avoid re-wiring every Swing
  listener bound to the original `engine` object.
  (`src/main/java/substrate/Game.java:260-286`)

## Testing

- **A PNG-dump CLI tool living in `src/test`** — `RenderTool` is not a JUnit
  test; it's a `main()`-based utility (compiled but never run by `mvn test`)
  for painting the board or window headlessly to a PNG for visual review. It
  sleeps through 25×40ms frames first "to let a second of animation go by."
  (`src/test/java/substrate/RenderTool.java`)

- **Animation-liveness tested via pixel-diff churn, not internal state** —
  `ArtTest.animationMovesAndStops` paints two frames 320ms apart (with a real
  `Thread.sleep(320)`), counts differing pixels, and asserts churn is nonzero
  while powered and drops below 1/4 of that once every `Group.powered` is
  forced false.
  (`src/test/java/substrate/ArtTest.java:57-81`)

- **Distinct-color count as a rendering smoke test** — asserts the rendered
  board has more than an arbitrary number of distinct sampled RGB values
  (`> 2000`, `> 1500`) as a proxy for "did the gradient code actually draw
  something," rather than golden-image comparison.
  (`src/test/java/substrate/ArtTest.java:41-46`)

- **A JUnit-free test-support class, shared with the CLI tool on purpose** —
  `TestSite`'s doc comment says it avoids JUnit dependencies "so the render
  tools in this source root can use them too," meaning `RenderTool.main()`
  and the JUnit suite share fixture code.
  (`src/test/java/substrate/TestSite.java:6-9`)

- **`user.home` is globally hot-swapped for the save-file tests** —
  `SessionTest` repoints the JVM-wide `user.home` system property to a
  `@TempDir` before each test and restores it after, since `Save` hardcodes
  `~/.substrate/site.txt` with no injectable path. As a result these tests
  aren't safe to run in parallel with anything else touching `user.home`.
  (`src/test/java/substrate/SessionTest.java:24-37`)

## Recurring unnamed magic numbers

| Value | Meaning | Where |
|---|---|---|
| `1.14` | per-unit price inflation | `Engine.priceOf`, `Engine.demolish` (repeated, not shared) |
| `31, 17, 7` | hash-mixing multipliers for animation desync | `Art.java` |
| `0.7 / 0.3` | EMA smoothing weights for ledger flow readouts | `Engine.java` |
| `15` | board width/height | `Board.W`/`H`, re-hardcoded in `Board.margin()` |
| `977` | noise decorrelation multiplier for capacitor arcs | `Art.java` |

## The bigger picture

No JSON library, no sprite assets, no animation framework, no dependency
injection, anywhere in this ~3,500-line codebase. Everything above is
hand-rolled on purpose, in a project whose entire premise (README:
"Notes on the port") is that a sealed-interface/exhaustive-switch simulation
core and a wall-clock-driven, throughput-desynced Java2D renderer are worth
building from scratch rather than reaching for a library.
