# SUBSTRATE

A grid foundry idle game. You start with one core and a hand. Everything else you build.

The rule the whole thing hangs on: **any solid rectangle of identical machines fuses into one
bigger machine, and its output scales with the square of the area.** Nine mining rigs in a 3×3
do not produce nine rigs' worth of ore, they produce eighty-one. Power draw only scales
linearly, so geometry is the real currency. Both sides of the rectangle must be at least 2, so
a long single-file line never fuses.

## Build and run

Requires JDK 21 or newer and Maven 3.9+.

```bash
mvn package
java -jar target/substrate.jar
```

Or straight from the sources, without packaging:

```bash
mvn exec:java
```

Tests only:

```bash
mvn test
```

Saves live in `~/.substrate/site.txt`. Time spent away is caught up when you return, up to two hours.

## Playing

- Click the core (or press space) to condense matter by hand.
- Build from the panel on the right; with the dismantle tool armed, click a placed machine to
  remove the whole fused block, or shift-click to take out only the single cell under the cursor.
  Either way the refund is half.
- Clicking an already-armed machine in the build panel keeps it armed — it does not deselect. Press
  Q to drop it, or Escape to clear every tool at once.
- Machines only run if they are connected to the core through a chain of touching machines. Pylons
  are the cheap way to reach a distant ore patch.
- Power Switch (or press P) toggles a whole fused block off — it stops drawing power and producing
  but stays built, so you can free up power for something else without paying to demolish and rebuild.
  An off block still conducts power through to whatever is fused or wired past it.
- Amber dashes mark the edge of your claim. Research widens it.
- Hover anything for readings. The MANUAL tab explains the rest.
- Victory: build a Fusion Reactor, the machine at the top of the research tree. The site marks
  it permanently in the masthead; nothing stops, so play on as long as you like afterward.
- Collapse: once you've won, COLLAPSE consumes every machine on the board and refuses them
  into a single Monolith across the northern half of your claim - the same fusion rule as any
  other block, applied to the whole site at once. Resources, research and the claim survive;
  the southern half stays free to build on, and collapsing again later folds a bigger claim
  into an even bigger Monolith.

## Layout

```
src/main/java/substrate/
  Engine, Fusion, Board, Group, OreGen   the simulation
  Machine, Spec, Role, Res, Tech, Cost   the catalogue, as data
  Art                                    every machine, drawn and animated
  Game, BoardPanel, LedgerPanel, ...     the Swing front end
  Save, Main                             persistence and the entry point
src/test/java/substrate/
  FusionTest, EngineTest, SessionTest    30 assertions over the rules
  ArtTest                                paints offscreen and checks things move
  TestSite, RenderTool                   fixtures, plus a PNG dump for headless review
```

## Notes on the port

`Role` is a sealed interface over records — `Mine`, `Converter`, `Generator`, `Buffer`,
`Amplifier` and friends — so the tick loop is one exhaustive pattern-matching switch with no
default branch. Adding a machine kind is a compile error until every switch handles it.
Machines, resources and research are enums carrying their own data, so there is no separate
config to drift out of sync.

The artwork is plain Java2D: a concrete pad, a bevelled metal body, and exactly one moving part
per machine — a turning drill head, a tilting crucible, a flickering furnace door, a nodding
walking beam, a pick-and-place gantry, a charge sliding along the cable ducts. Motion is driven
by wall-clock time and desynchronised per machine, and it throttles with the machine's actual
throughput, so a browned-out site visibly slows down. Unpowered blocks freeze and get a hazard
wash.

`RenderTool` paints the board or the whole window into a PNG with no display attached:

```bash
mvn -q test-compile
java -Djava.awt.headless=true -cp target/classes:target/test-classes substrate.RenderTool board 820 board.png
java -Djava.awt.headless=true -cp target/classes:target/test-classes substrate.RenderTool window 1180 860 ui.png
```
