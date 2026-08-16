package substrate;

import java.awt.*;
import java.awt.geom.*;

/**
 * Procedural Java2D artwork for every machine on the board. There is no sprite or asset
 * pipeline: each machine type gets its own hand-drawn private method built entirely from raw
 * {@link java.awt.geom.Path2D}, {@link java.awt.geom.Arc2D}, {@link java.awt.geom.Ellipse2D},
 * and gradient calls, with proportions tuned by eye as magic numbers relative to a per-cell
 * unit size. Every machine gets a concrete pad, a metal body with a bevel, and exactly one
 * thing that moves, so a working site reads as busy without turning into a fireworks display.
 * Unpowered machines are frozen and striped.
 *
 * <p>All motion is driven by the wall-clock seconds passed in from the caller ({@code t} in
 * {@link #paint}) rather than by any stored per-machine state: nothing here is animated
 * incrementally, everything is evaluated fresh from {@code t} on every repaint. Two knobs sit
 * on top of that raw clock before a machine-specific drawing method ever sees it &mdash; a
 * per-machine phase offset so identical machines never animate in lockstep, and a speed
 * multiplier tied to the group's actual simulation throughput, so a browned-out site visibly
 * slows its own machinery in real time. Both are computed in {@link #paint}; see there for
 * details. Where an effect needs to look random without changing between repaints of the same
 * frame, it is driven by {@link Theme#noise} rather than {@link java.util.Random}.
 */
public final class Art {
    /** Non-instantiable: every method here is a static, stateless drawing routine. */
    private Art() {}

    /**
     * Entry point: paints one machine group into {@code r}. Draws the shared shadow and pad,
     * dispatches to the machine-specific drawing method for {@code grp.type}, then layers on
     * the shared fusion seams, dead/unpowered overlay, and hover outline. All drawing is
     * clipped to the plate (padded by one pixel) so a machine method can never bleed paint
     * onto neighboring cells.
     *
     * <p>Two adjustments are made to the wall-clock time {@code t} before any machine-specific
     * method sees it, both recomputed from scratch on every call rather than cached:
     * <ul>
     *   <li>{@code seed}, derived from the group's grid position and type
     *       ({@code x*31 + y*17 + type.ordinal()*7}), is fed through {@link Theme#noise} to
     *       produce a per-machine phase offset added to {@code t} (giving {@code tt}). This is
     *       purely cosmetic: its only job is to stop a row of identical machines from animating
     *       in obviously-tiled lockstep. Because it is a deterministic function of position and
     *       type, a given cell always gets the same offset, and the whole thing is thrown away
     *       and recomputed on the next paint call.</li>
     *   <li>{@code speed} couples the renderer directly to simulation state: it is
     *       {@code grp.rate} (the actual fraction of nominal output the group achieved on the
     *       last simulation tick), floored at {@code 0.25} while powered, or {@code 0} while
     *       unpowered. Machine methods that model continuous work (miner, drill) multiply
     *       {@code tt} by {@code speed} before drawing, so a machine starved of power or input
     *       resources visibly grinds down in real time instead of always animating at full
     *       pace regardless of how little it is actually producing.</li>
     * </ul>
     *
     * @param g     destination graphics context
     * @param grp   the machine group being drawn, including its power/rate/fusion state
     * @param r     plate rectangle on the board, already sized to the fused block
     * @param t     seconds since launch
     * @param hover whether the pointer is over this group; draws a chalk outline if so
     * @param level 0..1 fill indicator, used by the capacitor bank
     */
    public static void paint(Graphics2D g, Group grp, Rectangle2D r, double t, boolean hover, double level) {
        double x = r.getX(), y = r.getY(), w = r.getWidth(), h = r.getHeight();
        double u = Math.min(w, h) / 34.0;                 // one unit ~ 1/34 of a cell
        int seed = grp.x * 31 + grp.y * 17 + grp.type.ordinal() * 7;
        double tt = grp.powered ? t + Theme.noise(seed) * 12 : 0;   // desynchronise the site
        double speed = grp.powered ? Math.max(0.25, grp.rate) : 0;

        Shape oldClip = g.getClip();
        g.clip(new Rectangle2D.Double(x - 1, y - 1, w + 2, h + 2));

        shadow(g, x, y, w, h);
        if (grp.type != Machine.CORE) pad(g, x, y, w, h);

        switch (grp.type) {
            case CORE    -> core(g, x, y, w, h, u, t);
            case PYLON   -> pylon(g, x, y, w, h, u, tt);
            case SOLAR   -> solar(g, x, y, w, h, u, tt, grp);
            case MINER   -> rig(g, x, y, w, h, u, tt * speed, grp);
            case DRILL   -> derrick(g, x, y, w, h, u, tt * speed, grp);
            case COND    -> condenser(g, x, y, w, h, u, tt);
            case FE      -> furnace(g, x, y, w, h, u, tt, seed, Theme.BRICK, Theme.EMBER);
            case CU      -> furnace(g, x, y, w, h, u, tt, seed, Theme.shade(Theme.BRICK, 1.1), new Color(0xFF, 0xB4, 0x6A));
            case BURNER  -> burner(g, x, y, w, h, u, tt, seed);
            case ARM     -> arm(g, x, y, w, h, u, tt);
            case ASM     -> assembler(g, x, y, w, h, u, tt);
            case STL     -> foundry(g, x, y, w, h, u, tt);
            case CAP     -> capacitors(g, x, y, w, h, u, tt, level);
            case LAB     -> lab(g, x, y, w, h, u, tt);
            case AMP     -> node(g, x, y, w, h, u, tt);
            case BLAST   -> blast(g, x, y, w, h, u, tt, seed);
            case INDUCT  -> induction(g, x, y, w, h, u, tt);
            case REFINE  -> refinery(g, x, y, w, h, u, tt, seed);
            case REACTOR -> reactor(g, x, y, w, h, u, tt, seed);
            case REP     -> replicator(g, x, y, w, h, u, tt);
            case TOKAMAK -> tokamak(g, x, y, w, h, u, tt);
        }

        if (grp.fused()) seams(g, grp, x, y, w, h, u);
        // Both an unpowered and a manually-switched-off group read as !grp.powered (see
        // Group#powered), but they mean different things to the player, so dead()'s tint is
        // colored by which one it is: HOT (alert red) for "something's wrong, this isn't
        // linked", AMBER (attention, not alarm) for "this is off because you turned it off".
        if (!grp.powered && grp.type != Machine.CORE) dead(g, x, y, w, h, u, grp.enabled ? Theme.HOT : Theme.AMBER);
        if (hover) {
            g.setColor(Theme.CHALK);
            g.setStroke(new BasicStroke((float) Math.max(1, u)));
            g.draw(new Rectangle2D.Double(x, y, w, h));
        }
        g.setClip(oldClip);
    }

    /* ------------------------------------------------------------------ */
    /* shared parts                                                        */
    /* ------------------------------------------------------------------ */

    /** Soft drop shadow cast down-right from the plate, painted first so every machine reads as sitting slightly above the board. */
    private static void shadow(Graphics2D g, double x, double y, double w, double h) {
        g.setColor(new Color(0, 0, 0, 80));
        g.fill(new RoundRectangle2D.Double(x + 1.5, y + 2.5, w, h, 4, 4));
    }

    /** Concrete mounting pad beneath the machine body: a vertical gradient plus a thin top highlight and a dark outline, faking a slight bevel. */
    private static void pad(Graphics2D g, double x, double y, double w, double h) {
        g.setPaint(new GradientPaint((float) x, (float) y, Theme.shade(Theme.CONCRETE, 0.62),
                (float) x, (float) (y + h), Theme.shade(Theme.CONCRETE, 0.38)));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 3, 3));
        g.setColor(new Color(255, 255, 255, 22));
        g.draw(new Line2D.Double(x + 1, y + 1, x + w - 1, y + 1));
        g.setColor(new Color(0, 0, 0, 110));
        g.draw(new RoundRectangle2D.Double(x, y, w - 1, h - 1, 3, 3));
    }

    /** Beveled metal box: light from the top-left. */
    private static void box(Graphics2D g, double x, double y, double w, double h, Color base, double round) {
        g.setPaint(new GradientPaint((float) x, (float) y, Theme.shade(base, 1.18),
                (float) x, (float) (y + h), Theme.shade(base, 0.68)));
        var s = new RoundRectangle2D.Double(x, y, w, h, round, round);
        g.fill(s);
        g.setColor(new Color(255, 255, 255, 55));
        g.draw(new Line2D.Double(x + 1, y + 1, x + w - 2, y + 1));
        g.setColor(new Color(0, 0, 0, 120));
        g.draw(new Line2D.Double(x + 1, y + h - 1, x + w - 1, y + h - 1));
        g.setColor(new Color(0, 0, 0, 150));
        g.draw(s);
    }

    /** Upright cylinder: horizontal gradient plus a specular band. */
    private static void cylinder(Graphics2D g, double x, double y, double w, double h, Color base) {
        g.setPaint(new LinearGradientPaint(new Point2D.Double(x, y), new Point2D.Double(x + w, y),
                new float[]{0f, 0.32f, 0.75f, 1f},
                new Color[]{Theme.shade(base, 0.55), Theme.shade(base, 1.25), Theme.shade(base, 0.8), Theme.shade(base, 0.45)}));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, w * 0.35, w * 0.35));
        g.setColor(new Color(0, 0, 0, 130));
        g.draw(new RoundRectangle2D.Double(x, y, w - 0.5, h - 0.5, w * 0.35, w * 0.35));
    }

    /** Four corner bolt heads, each with a small specular highlight, used to dress up flat machine bodies. */
    private static void bolts(Graphics2D g, double x, double y, double w, double h, double u) {
        double d = Math.max(1.4, 2.2 * u), in = Math.max(2, 3 * u);
        g.setColor(new Color(0, 0, 0, 120));
        for (double[] p : new double[][]{{x + in, y + in}, {x + w - in, y + in}, {x + in, y + h - in}, {x + w - in, y + h - in}}) {
            g.fill(new Ellipse2D.Double(p[0] - d / 2, p[1] - d / 2, d, d));
            g.setColor(new Color(255, 255, 255, 60));
            g.fill(new Ellipse2D.Double(p[0] - d / 2, p[1] - d / 2 - 0.4, d * 0.6, d * 0.6));
            g.setColor(new Color(0, 0, 0, 120));
        }
    }

    /**
     * Riveted ID plate reporting which ore a machine sits on: a dark plate with a border and
     * top sheen in the ore's color, and its short survey tag ({@code Fe}, {@code Cu}, {@code C},
     * {@code Ti}, {@code U}) in bold at the center.
     *
     * <p>Ore-dependent machines ({@link #rig} and {@link #derrick}) already carry a few small
     * ore-colored details &mdash; discharge-chute pips, a drill-collar sample, a mud-tank stripe
     * &mdash; but those are either tiny, mid-animation only, or both, so the ore type they sit on
     * is easy to miss at a glance. This plate is static, sized independently of the animation
     * cycle, and captioned, so the site reads correctly even from a paused or unpowered machine.
     * Placed by each caller wherever its own body leaves a clear rectangle, since the two ore
     * machines have unrelated layouts.
     *
     * @param px top-left corner of the plate
     * @param py top-left corner of the plate
     * @param ore the ore this machine sits on; drawing is skipped entirely if {@code null}
     */
    private static void orePlacard(Graphics2D g, double px, double py, double u, Res ore) {
        if (ore == null) return;
        double pw = 9.5 * u, ph = 6 * u;
        var plate = new RoundRectangle2D.Double(px, py, pw, ph, 1.5, 1.5);
        g.setColor(new Color(0x0E, 0x16, 0x20));
        g.fill(plate);
        g.setColor(Theme.alpha(ore.color, 70));
        g.fill(new RoundRectangle2D.Double(px + 0.8, py + 0.8, pw - 1.6, ph * 0.42, 1, 1));
        g.setColor(Theme.alpha(ore.color, 215));
        g.setStroke(new BasicStroke((float) Math.max(0.9, 1.1 * u)));
        g.draw(plate);
        g.setFont(Theme.monoBold((int) Math.max(7, ph * 0.56)));
        g.setColor(ore.color);
        var fm = g.getFontMetrics();
        float tx = (float) (px + (pw - fm.stringWidth(ore.tag)) / 2.0);
        float ty = (float) (py + (ph + fm.getAscent()) / 2.0 - fm.getDescent() * 0.3);
        g.drawString(ore.tag, tx, ty);
    }

    /**
     * Soft radial glow: {@code c} at alpha {@code a} in the center, fading to fully transparent
     * at {@code rad}. Bails out for a near-zero radius since {@link RadialGradientPaint}
     * requires a strictly positive radius and a degenerate glow would not be visible anyway.
     */
    private static void glow(Graphics2D g, double cx, double cy, double rad, Color c, int a) {
        if (rad <= 0.4) return;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) rad,
                new float[]{0f, 1f}, new Color[]{Theme.alpha(c, a), Theme.alpha(c, 0)}));
        g.fill(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
    }

    /**
     * Small indicator lamp: a wide {@link #glow} plus a solid dot, both blended between a
     * darkened "off" shade and full color by {@code on} ({@code 0} = dark, {@code 1} = fully
     * lit), so lamps can fade rather than only ever being hard on or off.
     */
    private static void led(Graphics2D g, double cx, double cy, double rad, Color c, double on) {
        glow(g, cx, cy, rad * 3.2, c, (int) (110 * on));
        g.setColor(Theme.mix(Theme.shade(c, 0.35), c, on));
        g.fill(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
    }

    /**
     * Rising puffs, deterministic per seed so they do not flicker between frames. Each puff's
     * timing offset and horizontal drift comes from {@link Theme#noise}, keyed on {@code seed}
     * plus the puff index, rather than {@link java.util.Random}: the puffs need to look
     * randomly scattered but stay pixel-identical if the same frame ({@code t}) is repainted,
     * which a stateful RNG could not guarantee without extra per-caller bookkeeping.
     *
     * @param cx    horizontal center of the stack the smoke rises from
     * @param top   y coordinate the puffs originate at and rise above
     * @param count number of puffs cycling through the loop
     * @param c     base smoke/steam color
     */
    private static void smoke(Graphics2D g, double cx, double top, double u, double t, int seed, int count, Color c) {
        for (int k = 0; k < count; k++) {
            double p = frac(t * 0.22 + k / (double) count + Theme.noise(seed + k));
            double drift = (Theme.noise(seed + k * 3) - 0.5) * 8 * u * p;
            double rad = (1.6 + p * 4.5) * u;
            double a = (1 - p) * 52;
            g.setColor(Theme.alpha(c, (int) a));
            g.fill(new Ellipse2D.Double(cx + drift - rad, top - p * 13 * u - rad, rad * 2, rad * 2));
        }
    }

    /**
     * Spinning spoked wheel: rotates the graphics context by {@code angle} about the hub,
     * draws {@code spokes} evenly-spaced radial lines, then restores the original transform.
     * Shared by every flywheel, fan, and centrifuge in the file.
     */
    private static void rotor(Graphics2D g, double cx, double cy, double rad, double angle, int spokes, Color c, double u) {
        var old = g.getTransform();
        g.rotate(angle, cx, cy);
        g.setColor(c);
        g.setStroke(new BasicStroke((float) Math.max(1, 1.6 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < spokes; i++) {
            double a = Math.PI * 2 * i / spokes;
            g.draw(new Line2D.Double(cx, cy, cx + Math.cos(a) * rad, cy + Math.sin(a) * rad));
        }
        g.setTransform(old);
    }

    /** Structural ribs and a fusion badge, so a merged block reads as one bigger machine. */
    private static void seams(Graphics2D g, Group grp, double x, double y, double w, double h, double u) {
        g.setColor(new Color(0, 0, 0, 55));
        g.setStroke(new BasicStroke((float) Math.max(0.8, 0.7 * u)));
        for (int i = 1; i < grp.w; i++) {
            double px = x + w * i / grp.w;
            g.draw(new Line2D.Double(px, y + 2, px, y + h - 2));
        }
        for (int i = 1; i < grp.h; i++) {
            double py = y + h * i / grp.h;
            g.draw(new Line2D.Double(x + 2, py, x + w - 2, py));
        }
        g.setColor(new Color(255, 255, 255, 26));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.2 * u)));
        g.draw(new Rectangle2D.Double(x + 1.5, y + 1.5, w - 3, h - 3));
    }


    /**
     * Overlay drawn on unpowered, non-core machines: a dark tint, diagonal hazard hatching
     * (clipped to the plate so the stripes don't spill past its corners), a hazard-colored
     * outline, and a lit warning LED &mdash; so a stalled machine reads as visibly dead rather
     * than merely un-animated.
     *
     * @param accent {@link Theme#HOT} for "not linked to the core" (an actual problem) or
     *               {@link Theme#AMBER} for "manually switched off" (a deliberate choice); see
     *               the call in {@link #paint} for which is which.
     */
    private static void dead(Graphics2D g, double x, double y, double w, double h, double u, Color accent) {
        var clip = g.getClip();
        g.clip(new Rectangle2D.Double(x, y, w, h));
        g.setColor(new Color(12, 20, 30, 62));
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setColor(Theme.alpha(accent, 26));
        g.setStroke(new BasicStroke((float) Math.max(1.5, 2.2 * u)));
        for (double d = -h; d < w + h; d += 8 * u)
            g.draw(new Line2D.Double(x + d, y + h, x + d + h, y));
        g.setClip(clip);
        g.setColor(Theme.alpha(accent, 150));
        g.setStroke(new BasicStroke((float) Math.max(1, u)));
        g.draw(new Rectangle2D.Double(x, y, w - 1, h - 1));
        led(g, x + w - 3.5 * u, y + 3.5 * u, Math.max(1, 1.3 * u), accent, 0.85);
    }
    /**
     * Fractional part of {@code v}, always landing in {@code [0, 1)} even for negative input
     * (unlike the {@code %} operator). Used throughout to turn a monotonically increasing time
     * value into a repeating 0..1 cycle position for looping animations.
     */
    private static double frac(double v) { return v - Math.floor(v); }

    /**
     * Irregular firelight brightness, roughly in {@code [0.44, 1.0]}: two sine waves at
     * incommensurate frequencies (7.3 and 17.1), phase-shifted by {@code seed} so different
     * furnaces/burners don't flicker in sync, summed to avoid the too-regular pulsing a single
     * sine would give.
     */
    private static double flicker(double t, int seed) {
        return 0.72 + 0.16 * Math.sin(t * 7.3 + seed) + 0.12 * Math.sin(t * 17.1 + seed * 0.7);
    }

    /* ------------------------------------------------------------------ */
    /* the machines                                                        */
    /* ------------------------------------------------------------------ */


    /**
     * The CORE machine: a sunken radial well with three containment rings rotating one way and
     * four smaller ones counter-rotating (achieved by rotating the whole graphics context
     * forward then further back, rather than transforming each arc individually) around a
     * pulsing white-hot center. Unlike every other machine, {@code core} is called with raw,
     * un-desynced {@code t} directly from {@link #paint}, since the core is never unpowered and
     * so needs no phase offset to avoid lockstep with an identical neighbor.
     */
    private static void core(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double cx = x + w / 2, cy = y + h / 2, rad = Math.min(w, h) / 2;
        // sunken well
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy - rad * 0.15), (float) rad,
                new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(0x4A, 0x33, 0x10), new Color(0x1B, 0x2D, 0x42), new Color(0x08, 0x14, 0x20)}));
        g.fill(new Ellipse2D.Double(x, y, w, h));
        g.setColor(new Color(0, 0, 0, 150));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.4 * u)));
        g.draw(new Ellipse2D.Double(x + 0.5, y + 0.5, w - 1, h - 1));

        // three counter-rotating containment rings
        var old = g.getTransform();
        g.rotate(t * 0.4, cx, cy);
        g.setColor(Theme.alpha(Theme.AMBER, 150));
        g.setStroke(new BasicStroke((float) Math.max(1.2, 2.0 * u), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        for (int i = 0; i < 3; i++)
            g.draw(new Arc2D.Double(x + 2.5 * u, y + 2.5 * u, w - 5 * u, h - 5 * u, i * 120 + 10, 76, Arc2D.OPEN));
        g.rotate(-t * 0.95, cx, cy);
        g.setColor(Theme.alpha(Theme.AMBER, 95));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.4 * u)));
        for (int i = 0; i < 4; i++)
            g.draw(new Arc2D.Double(x + 6 * u, y + 6 * u, w - 12 * u, h - 12 * u, i * 90 + 14, 52, Arc2D.OPEN));
        g.setTransform(old);

        double pulse = 0.6 + 0.4 * Math.sin(t * 1.9);
        glow(g, cx, cy, rad * (0.7 + 0.3 * pulse), Theme.AMBER, (int) (170 + 60 * pulse));
        double core = rad * (0.26 + 0.04 * pulse);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) (core * 1.6),
                new float[]{0f, 0.45f, 1f},
                new Color[]{Color.WHITE, Theme.mix(Theme.AMBER, Color.WHITE, 0.5), Theme.alpha(Theme.AMBER, 0)}));
        g.fill(new Ellipse2D.Double(cx - core * 1.6, cy - core * 1.6, core * 3.2, core * 3.2));
    }
    /**
     * A cable junction on a concrete pad. The ducts run right to the edge of the plate so a line
     * of pylons reads as one continuous run, and a charge slides along it.
     */
    private static void pylon(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double cx = x + w / 2, cy = y + h / 2;
        double half = Math.max(2.5, 6.5 * u);

        duct(g, x - 1, cy - half, w + 2, half * 2, true);
        duct(g, cx - half, y - 1, half * 2, h + 2, false);

        double m = Math.max(3.5, 7.5 * u);
        box(g, cx - m, cy - m, m * 2, m * 2, Theme.STEEL_DARK, 2);
        g.setColor(new Color(255, 255, 255, 30));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle2D.Double(cx - m + 2, cy - m + 2, m * 2 - 4, m * 2 - 4));
        bolts(g, cx - m, cy - m, m * 2, m * 2, u);

        double phase = x * 0.021 + y * 0.013;                    // neighbours pulse out of step
        double f = ((t * 0.42 + phase) % 1 + 1) % 1;
        double px = x - 2 + f * (w + 4);
        glow(g, px, cy, half * 1.8, Theme.AMBER, 70);
        g.setColor(Theme.alpha(Theme.AMBER, 205));
        g.fill(new RoundRectangle2D.Double(px - 2.2 * u, cy - 0.85 * u, 4.4 * u, 1.7 * u, 2, 2));

        led(g, cx, cy, Math.max(1.1, 1.5 * u), Theme.AMBER, Math.sin(t * 3.4) > 0.6 ? 1 : 0.14);
    }

    /** Steel cable tray with a copper strand down the middle. */
    private static void duct(Graphics2D g, double x, double y, double w, double h, boolean horiz) {
        g.setPaint(horiz
                ? new GradientPaint((float) x, (float) y, Theme.shade(Theme.STEEL_DARK, 1.35),
                (float) x, (float) (y + h), Theme.shade(Theme.STEEL_DARK, 0.55))
                : new GradientPaint((float) x, (float) y, Theme.shade(Theme.STEEL_DARK, 1.35),
                (float) (x + w), (float) y, Theme.shade(Theme.STEEL_DARK, 0.55)));
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(0, 0, 0, 120));
        if (horiz) {
            g.draw(new Line2D.Double(x, y, x + w, y));
            g.draw(new Line2D.Double(x, y + h - 1, x + w, y + h - 1));
            g.setColor(Theme.alpha(Theme.COPPER, 120));
            g.draw(new Line2D.Double(x, y + h / 2, x + w, y + h / 2));
        } else {
            g.draw(new Line2D.Double(x, y, x, y + h));
            g.draw(new Line2D.Double(x + w - 1, y, x + w - 1, y + h));
            g.setColor(Theme.alpha(Theme.COPPER, 120));
            g.draw(new Line2D.Double(x + w / 2, y, x + w / 2, y + h));
        }
    }

    /**
     * Solar array: a dark panel gridded into {@code max(2, grp.w*3)} by {@code max(2, grp.h*3)}
     * cells, so a fused block of panels subdivides finer rather than just stretching the cell
     * pattern, plus a diagonal specular sheen sweeping across the whole array on a slow
     * 7-second cycle.
     */
    private static void solar(Graphics2D g, double x, double y, double w, double h, double u, double t, Group grp) {
        double in = 2.5 * u;
        double px = x + in, py = y + in, pw = w - 2 * in, ph = h - 2 * in;
        g.setPaint(new GradientPaint((float) px, (float) py, new Color(0x1C, 0x3E, 0x60),
                (float) px, (float) (py + ph), new Color(0x0E, 0x22, 0x3A)));
        g.fill(new Rectangle2D.Double(px, py, pw, ph));

        int nx = Math.max(2, grp.w * 3), ny = Math.max(2, grp.h * 3);
        g.setColor(new Color(0xB8, 0xC8, 0xD8, 60));
        g.setStroke(new BasicStroke((float) Math.max(0.6, 0.5 * u)));
        for (int i = 1; i < nx; i++) g.draw(new Line2D.Double(px + pw * i / nx, py, px + pw * i / nx, py + ph));
        for (int i = 1; i < ny; i++) g.draw(new Line2D.Double(px, py + ph * i / ny, px + pw, py + ph * i / ny));

        // specular sheen crossing the array
        double p = frac(t / 7.0);
        double sx = px - pw + p * pw * 2.4;
        var clip = g.getClip();
        g.clip(new Rectangle2D.Double(px, py, pw, ph));
        g.setPaint(new LinearGradientPaint(new Point2D.Double(sx, py), new Point2D.Double(sx + pw * 0.5, py + ph),
                new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(255, 255, 255, 0), new Color(200, 228, 255, 42), new Color(255, 255, 255, 0)}));
        g.fill(new Rectangle2D.Double(px, py, pw, ph));
        g.setClip(clip);

        g.setColor(new Color(0, 0, 0, 140));
        g.draw(new Rectangle2D.Double(px, py, pw, ph));
        g.setColor(Theme.alpha(Theme.STEEL, 190));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.1 * u)));
        g.draw(new Line2D.Double(x + 1.5 * u, y + h - 1.5 * u, x + w - 1.5 * u, y + h - 1.5 * u));
    }


    /**
     * Mining rig: a raised deck with hazard striping along the top edge, a finned motor block,
     * an ore-colored discharge chute, and a rotating drill bit trailing a dust ring. {@code t}
     * arrives here already multiplied by {@link #paint}'s simulation-rate {@code speed}, so a
     * rig running below nominal throughput visibly grinds slower rather than just producing
     * less while still spinning at full speed.
     *
     * <p>The three drill teeth are bespoke vector geometry rather than a stock shape: each is a
     * closed {@link Path2D} built from two quadratic Bezier curves whose control points are
     * placed by hand via trig &mdash; offsetting the tooth's radial direction {@code (ax, ay) =
     * (cos a, sin a)} by its perpendicular {@code (-ay, ax)}, scaled by tuned constants &mdash;
     * rather than by composing an actual {@link java.awt.geom.AffineTransform} rotation.
     *
     * <p>The discharge chute's track is tinted toward the underlying ore's color (not just the
     * pips riding along it) and an {@link #orePlacard} sits in the deck's free bottom-right
     * corner, so the ore this rig sits on stays legible between pips and while unpowered.
     */
    private static void rig(Graphics2D g, double x, double y, double w, double h, double u, double t, Group grp) {
        Color oreColor = grp.ore != null ? grp.ore.color : Theme.DIM;
        double bx = x + 3 * u, by = y + 3 * u, bw = w - 6 * u, bh = h - 6 * u;
        box(g, bx, by, bw, bh, Theme.STEEL, 3);
        // hazard edge along the top of the deck
        var clip = g.getClip();
        g.clip(new Rectangle2D.Double(bx, by, bw, 2.6 * u));
        g.setColor(Theme.alpha(Theme.AMBER, 150));
        g.setStroke(new BasicStroke((float) Math.max(1.2, 1.6 * u)));
        for (double d = 0; d < bw + 4 * u; d += 4 * u)
            g.draw(new Line2D.Double(bx + d, by + 2.6 * u, bx + d + 2.6 * u, by));
        g.setClip(clip);
        // motor block with cooling fins
        double mw = bw * 0.2, mh = bh * 0.42;
        double mx = bx + u, my = by + bh - mh - u;
        box(g, mx, my, mw, mh, Theme.shade(Theme.STEEL, 0.8), 1);
        g.setColor(new Color(0, 0, 0, 90));
        g.setStroke(new BasicStroke((float) Math.max(0.6, 0.7 * u)));
        for (int i = 1; i < 4; i++)
            g.draw(new Line2D.Double(mx + 0.5, my + mh * i / 4.0, mx + mw - 0.5, my + mh * i / 4.0));
        // discharge chute with ore riding along it
        double chy = by + bh * 0.24, chh = Math.max(2.4 * u, bh * 0.16);
        double chx = bx + bw * 0.42, chw = bw - (bw * 0.42) - u;
        g.setColor(Theme.mix(Theme.shade(Theme.STEEL_DARK, 1.15), oreColor, 0.4));
        g.fill(new RoundRectangle2D.Double(chx, chy, chw, chh, 2, 2));
        g.setColor(new Color(0, 0, 0, 110));
        g.draw(new RoundRectangle2D.Double(chx, chy, chw, chh, 2, 2));
        g.setColor(oreColor);
        for (int k = 0; k < 4; k++) {
            double p = frac(t * 0.5 + k * 0.25);
            double r2 = Math.max(0.9, chh * 0.28);
            g.fill(new Ellipse2D.Double(chx + 1 + (chw - 2 - r2 * 2) * p, chy + chh / 2 - r2, r2 * 2, r2 * 2));
        }
        // drill collar and rotating bit
        double cx = bx + bw * 0.34, cy = by + bh * 0.62;
        double rad = Math.min(bw, bh) * 0.26;
        g.setColor(new Color(0, 0, 0, 140));
        g.fill(new Ellipse2D.Double(cx - rad * 1.2, cy - rad * 1.2, rad * 2.4, rad * 2.4));
        g.setColor(Theme.shade(Theme.STEEL_DARK, 1.3));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.2 * u)));
        g.draw(new Ellipse2D.Double(cx - rad * 1.2, cy - rad * 1.2, rad * 2.4, rad * 2.4));
        var old = g.getTransform();
        g.rotate(t * 2.4, cx, cy);
        for (int i = 0; i < 3; i++) {
            var tooth = new Path2D.Double();
            double a = Math.PI * 2 * i / 3;
            double ax = Math.cos(a), ay = Math.sin(a);
            tooth.moveTo(cx + ax * rad * 0.25, cy + ay * rad * 0.25);
            tooth.quadTo(cx + (ax - ay * 0.7) * rad, cy + (ay + ax * 0.7) * rad,
                    cx + ax * rad * 1.05, cy + ay * rad * 1.05);
            tooth.quadTo(cx + (ax * 0.6 - ay * 0.2) * rad, cy + (ay * 0.6 + ax * 0.2) * rad,
                    cx + ax * rad * 0.25, cy + ay * rad * 0.25);
            tooth.closePath();
            g.setPaint(new GradientPaint((float) (cx - rad), (float) (cy - rad), Theme.shade(Theme.CHALK, 0.95),
                    (float) (cx + rad), (float) (cy + rad), Theme.shade(Theme.STEEL, 0.7)));
            g.fill(tooth);
        }
        g.setColor(Theme.shade(Theme.STEEL_DARK, 1.4));
        g.fill(new Ellipse2D.Double(cx - rad * 0.28, cy - rad * 0.28, rad * 0.56, rad * 0.56));
        g.setTransform(old);
        // dust ring
        double p = frac(t * 0.55);
        g.setColor(Theme.alpha(oreColor, (int) (52 * (1 - p))));
        double dr = rad * (1.25 + p * 0.8);
        g.setStroke(new BasicStroke((float) Math.max(0.9, 1.1 * u)));
        g.draw(new Ellipse2D.Double(cx - dr, cy - dr * 0.6, dr * 2, dr * 1.2));
        bolts(g, bx, by, bw, bh, u);
        orePlacard(g, bx + bw - 9.5 * u - 1.5 * u, by + bh - 6 * u - 1.5 * u, u, grp.ore);
    }
    /**
     * Oil derrick: a four-legged tower seen from above with diagonal cross-bracing, a rotary
     * table and drill bit at the apex, a nodding walking-beam pump beside the tower, and a mud
     * tank. Like {@link #rig}, {@code t} arrives already scaled by simulation throughput, so a
     * starved derrick visibly slows down rather than only reducing its output number.
     *
     * <p>The rotary-table sample and mud-tank stripe are both ore-colored but small and low-alpha,
     * so an {@link #orePlacard} sits in the tower's clear top-right corner (the legs, bracing,
     * beam pump, and mud tank all leave that quadrant empty) to keep the ore legible at a glance.
     */
    private static void derrick(Graphics2D g, double x, double y, double w, double h, double u, double t, Group grp) {
        double cx = x + w / 2, cy = y + h / 2;
        box(g, x + 2.5 * u, y + 2.5 * u, w - 5 * u, h - 5 * u, Theme.shade(Theme.STEEL, 0.72), 3);
        double in = 5.5 * u;
        double topHalf = Math.min(w, h) * 0.12;
        double[][] feet = {{x + in, y + in}, {x + w - in, y + in}, {x + w - in, y + h - in}, {x + in, y + h - in}};
        double[][] head = {{cx - topHalf, cy - topHalf}, {cx + topHalf, cy - topHalf},
                           {cx + topHalf, cy + topHalf}, {cx - topHalf, cy + topHalf}};
        // legs
        g.setColor(Theme.alpha(Theme.STEEL, 235));
        g.setStroke(new BasicStroke((float) Math.max(1.1, 1.5 * u)));
        for (int i = 0; i < 4; i++) g.draw(new Line2D.Double(feet[i][0], feet[i][1], head[i][0], head[i][1]));
        // cross bracing between the legs, seen from above
        g.setColor(Theme.alpha(Theme.STEEL, 130));
        g.setStroke(new BasicStroke((float) Math.max(0.8, 0.9 * u)));
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            g.draw(new Line2D.Double(feet[i][0], feet[i][1], head[j][0], head[j][1]));
            g.draw(new Line2D.Double(head[i][0], head[i][1], feet[j][0], feet[j][1]));
        }
        g.setColor(Theme.alpha(Theme.STEEL, 190));
        g.setStroke(new BasicStroke((float) Math.max(1, u)));
        g.draw(new Rectangle2D.Double(cx - topHalf, cy - topHalf, topHalf * 2, topHalf * 2));
        // rotary table and bit
        double rad = topHalf * 0.95;
        g.setColor(new Color(0, 0, 0, 130));
        g.fill(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
        rotor(g, cx, cy, rad * 0.9, t * 3.4, 6, Theme.shade(Theme.CHALK, 0.8), u);
        g.setColor(Theme.alpha(grp.ore != null ? grp.ore.color : Theme.DIM, 235));
        g.fill(new Ellipse2D.Double(cx - rad * 0.42, cy - rad * 0.42, rad * 0.84, rad * 0.84));
        // walking beam nodding beside the tower
        double a = Math.sin(t * 1.15) * 0.32;
        double px = x + 8 * u, py = y + h - 5.5 * u;
        g.setColor(Theme.shade(Theme.STEEL_DARK, 1.2));
        g.fill(new Rectangle2D.Double(px - 1.2 * u, py - 3 * u, 2.4 * u, 5 * u));
        var old = g.getTransform();
        g.rotate(a, px, py - 3 * u);
        g.setColor(Theme.shade(Theme.STEEL, 1.1));
        g.fill(new RoundRectangle2D.Double(px - 7 * u, py - 4 * u, 14 * u, 2 * u, 1, 1));
        g.setColor(Theme.shade(Theme.STEEL_DARK, 1.4));
        g.fill(new Rectangle2D.Double(px + 4.5 * u, py - 5.5 * u, 3 * u, 3.5 * u));
        g.setTransform(old);
        // mud tank
        g.setColor(Theme.alpha(Theme.STEEL_DARK, 220));
        g.fill(new RoundRectangle2D.Double(x + w - 9 * u, y + h - 7 * u, 6 * u, 4 * u, 1, 1));
        g.setColor(Theme.alpha(grp.ore != null ? grp.ore.color : Theme.DIM, 200));
        g.fill(new RoundRectangle2D.Double(x + w - 8.5 * u, y + h - 6.5 * u, 5 * u, 2 * u, 1, 1));
        orePlacard(g, x + w - 9.5 * u - 2 * u, y + 2 * u, u, grp.ore);
    }

    /**
     * Condenser: a domed cylindrical vessel with horizontal rib lines and two inlet pipes, a
     * circular viewport where concentric rings shrink toward a bright central point to suggest
     * vapor collapsing to a focus, and a small vacuum pump with a spinning wheel at the base.
     */
    private static void condenser(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double cx = x + w / 2;
        double vw = w - 7 * u, vh = h - 9 * u;
        double vx = cx - vw / 2, vy = y + 5 * u;
        // domed top
        g.setColor(Theme.shade(Theme.STEEL, 1.15));
        g.fill(new Arc2D.Double(vx, vy - vw * 0.34, vw, vw * 0.68, 0, 180, Arc2D.PIE));
        cylinder(g, vx, vy, vw, vh, Theme.shade(Theme.STEEL, 0.98));
        // ribs
        g.setColor(new Color(0, 0, 0, 65));
        g.setStroke(new BasicStroke((float) Math.max(0.7, 0.8 * u)));
        for (int i = 1; i < 5; i++)
            g.draw(new Line2D.Double(vx + 1, vy + vh * i / 5.0, vx + vw - 1, vy + vh * i / 5.0));
        // inlet pipes
        g.setColor(Theme.alpha(Theme.STEEL_DARK, 230));
        g.setStroke(new BasicStroke((float) Math.max(1.2, 1.6 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(vx, vy + vh * 0.25, x + 1.5 * u, vy + vh * 0.25));
        g.draw(new Line2D.Double(vx + vw, vy + vh * 0.7, x + w - 1.5 * u, vy + vh * 0.7));
        // viewport with rings collapsing toward a bright point
        double cy = vy + vh * 0.46;
        double rad = Math.min(vw, vh) * 0.3;
        g.setColor(new Color(0x07, 0x12, 0x1E));
        g.fill(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
        g.setStroke(new BasicStroke((float) Math.max(0.9, 1.1 * u)));
        for (int k = 0; k < 3; k++) {
            double p = frac(t * 0.6 + k / 3.0);
            double rr = rad * (1 - p) * 0.95;
            g.setColor(Theme.alpha(Theme.ICE, (int) (150 * p)));
            g.draw(new Ellipse2D.Double(cx - rr, cy - rr, rr * 2, rr * 2));
        }
        glow(g, cx, cy, rad * 0.85, Theme.ICE, 130);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(cx - rad * 0.14, cy - rad * 0.14, rad * 0.28, rad * 0.28));
        g.setColor(Theme.alpha(Theme.CHALK, 90));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.2 * u)));
        g.draw(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
        // vacuum pump at the base with a turning wheel
        double pw = vw * 0.5, ph = 4.5 * u;
        double px = cx - pw / 2, py = y + h - ph - 2 * u;
        box(g, px, py, pw, ph, Theme.shade(Theme.STEEL_DARK, 1.2), 1);
        rotor(g, px + pw * 0.28, py + ph / 2, ph * 0.32, t * 5, 4, Theme.shade(Theme.CHALK, 0.75), u);
        led(g, px + pw * 0.78, py + ph / 2, Math.max(0.9, u), Theme.GOOD, 0.6 + 0.4 * Math.sin(t * 2.5));
    }
    /**
     * Iron/copper furnace, shared by both metal-processing machine types via caller-supplied
     * brick and ember colors. A tapered brick shell with offset-coursed brickwork (clipped to
     * the shell's own {@link Path2D} so bricks never draw past its silhouette), a charging
     * hopper, an arched door with a {@link #flicker}ing fire and two Bezier flame licks behind
     * it, and a smoking chimney.
     *
     * @param seed  per-group seed, passed through to {@link #flicker} and {@link #smoke} so
     *              this furnace's fire and smoke are out of phase with an identical neighbor
     * @param brick base color of the refractory shell
     * @param ember color of the fire glow, door light, and flame licks
     */
    private static void furnace(Graphics2D g, double x, double y, double w, double h, double u, double t, int seed,
                               Color brick, Color ember) {
        double bx = x + 3 * u, bw = w - 6 * u;
        double top = y + 5.5 * u, bot = y + h - 3 * u;
        // refractory shell, slightly tapered
        var shell = new Path2D.Double();
        double taper = bw * 0.09;
        shell.moveTo(bx + taper, top);
        shell.lineTo(bx + bw - taper, top);
        shell.lineTo(bx + bw, bot);
        shell.lineTo(bx, bot);
        shell.closePath();
        g.setPaint(new GradientPaint((float) bx, (float) top, Theme.shade(brick, 1.2),
                (float) (bx + bw), (float) bot, Theme.shade(brick, 0.6)));
        g.fill(shell);
        // brick courses, offset every other row
        var clip = g.getClip();
        g.clip(shell);
        g.setColor(new Color(0, 0, 0, 58));
        g.setStroke(new BasicStroke((float) Math.max(0.7, 0.7 * u)));
        int rows = Math.max(3, (int) ((bot - top) / (3.6 * u)));
        for (int i = 1; i < rows; i++) {
            double ly = top + (bot - top) * i / rows;
            g.draw(new Line2D.Double(bx, ly, bx + bw, ly));
            double off = (i % 2 == 0) ? 0 : bw / 8;
            for (int k = 0; k < 4; k++)
                g.draw(new Line2D.Double(bx + off + bw * k / 4.0, ly, bx + off + bw * k / 4.0, ly + (bot - top) / rows));
        }
        g.setClip(clip);
        // steel bands
        g.setColor(Theme.alpha(Theme.STEEL_DARK, 220));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.3 * u)));
        for (double f : new double[]{0.34, 0.68}) {
            double ly = top + (bot - top) * f;
            g.draw(new Line2D.Double(bx + taper * (1 - f), ly, bx + bw - taper * (1 - f), ly));
        }
        // charging hopper
        var hop = new Path2D.Double();
        hop.moveTo(bx + bw * 0.08, top);
        hop.lineTo(bx + bw * 0.42, top);
        hop.lineTo(bx + bw * 0.34, top - 3.5 * u);
        hop.lineTo(bx + bw * 0.14, top - 3.5 * u);
        hop.closePath();
        g.setColor(Theme.shade(Theme.STEEL, 0.85));
        g.fill(hop);
        g.setColor(new Color(0, 0, 0, 120));
        g.draw(hop);
        // arched door with fire behind it
        double dw = bw * 0.4, dh = (bot - top) * 0.42;
        double dx = bx + bw / 2 - dw / 2, dy = bot - dh - 1.5 * u;
        var door = new RoundRectangle2D.Double(dx, dy, dw, dh, dw * 0.9, dw * 0.9);
        g.setColor(new Color(0x12, 0x0A, 0x08));
        g.fill(door);
        double f = flicker(t, seed);
        glow(g, dx + dw / 2, dy + dh * 0.62, dh * (1.0 + 0.3 * f), ember, (int) (200 * f));
        g.clip(door);
        g.setPaint(new GradientPaint((float) dx, (float) (dy + dh), Theme.mix(ember, Color.WHITE, 0.45 * f),
                (float) dx, (float) dy, Theme.alpha(ember, 30)));
        g.fill(door);
        // two flame licks
        g.setColor(Theme.alpha(Theme.mix(ember, Color.WHITE, 0.6), (int) (190 * f)));
        for (int k = 0; k < 2; k++) {
            double fx = dx + dw * (0.34 + k * 0.32);
            double fh = dh * (0.4 + 0.28 * Math.abs(Math.sin(t * 6 + k * 2 + seed)));
            var lick = new Path2D.Double();
            lick.moveTo(fx - dw * 0.09, dy + dh);
            lick.quadTo(fx, dy + dh - fh, fx + dw * 0.09, dy + dh);
            lick.closePath();
            g.fill(lick);
        }
        g.setClip(clip);
        g.setColor(Theme.alpha(Theme.STEEL_DARK, 200));
        g.setStroke(new BasicStroke((float) Math.max(1, u)));
        g.draw(door);
        // chimney
        double sx = bx + bw * 0.72, sw = 4 * u;
        box(g, sx, y + 1.5 * u, sw, top - y - 1.5 * u + u, Theme.STEEL_DARK, 1);
        smoke(g, sx + sw / 2, y + 1.5 * u, u, t, seed, 3, new Color(190, 200, 210));
    }
    /**
     * Steam burner: a horizontal riveted boiler drum sitting over a flickering firebox glow, a
     * flywheel spinning at the drum's left end, and a smoking stack.
     *
     * @param seed per-group seed, passed to {@link #flicker} and {@link #smoke} for desync
     */
    private static void burner(Graphics2D g, double x, double y, double w, double h, double u, double t, int seed) {
        double bx = x + 2.5 * u, by = y + 7 * u, bw = w - 5 * u, bh = h - 10 * u;
        // horizontal boiler drum
        g.setPaint(new GradientPaint((float) bx, (float) by, Theme.shade(Theme.STEEL, 1.2),
                (float) bx, (float) (by + bh), Theme.shade(Theme.STEEL, 0.55)));
        g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, bh * 0.6, bh * 0.6));
        g.setColor(new Color(0, 0, 0, 140));
        g.draw(new RoundRectangle2D.Double(bx, by, bw - 0.5, bh - 0.5, bh * 0.6, bh * 0.6));
        g.setColor(new Color(0, 0, 0, 55));
        g.setStroke(new BasicStroke((float) Math.max(0.7, 0.8 * u)));
        for (int i = 1; i < 4; i++) {
            double lx = bx + bw * i / 4.0;
            g.draw(new Line2D.Double(lx, by + 1, lx, by + bh - 1));
        }
        // firebox glow under the drum
        double f = flicker(t, seed);
        glow(g, bx + bw / 2, by + bh + 1.5 * u, bw * 0.35 * (0.9 + 0.2 * f), Theme.EMBER, (int) (150 * f));
        g.setColor(Theme.alpha(Theme.EMBER, (int) (200 * f)));
        g.fill(new Rectangle2D.Double(bx + bw * 0.2, by + bh, bw * 0.6, 2 * u));
        // flywheel
        double fcx = bx + bw * 0.22, fcy = by + bh * 0.5, rad = bh * 0.34;
        g.setColor(Theme.shade(Theme.STEEL_DARK, 0.9));
        g.fill(new Ellipse2D.Double(fcx - rad, fcy - rad, rad * 2, rad * 2));
        rotor(g, fcx, fcy, rad * 0.85, t * 4.5, 4, Theme.shade(Theme.CHALK, 0.85), u);
        // stack
        double sx = bx + bw * 0.72, sw = 5 * u;
        box(g, sx, y + 2 * u, sw, by - y - u, Theme.STEEL_DARK, 1);
        smoke(g, sx + sw / 2, y + 2 * u, u, t, seed + 5, 4, new Color(150, 158, 168));
    }


    /**
     * Robotic pick-and-place arm: a control cabinet with blinking status LEDs and a feed tray,
     * and a two-segment arm swinging between a resting and a reaching pose over a fixed cycle.
     * The cycle position ({@code cycle = frac(t * 0.32)}) also decides whether the arm is
     * "carrying" (true past the cycle's midpoint), which closes the gripper and draws a
     * glowing item held at its tip.
     */
    private static void arm(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double bx = x + 2.5 * u, by = y + 2.5 * u, bw = w - 5 * u, bh = h - 5 * u;
        box(g, bx, by, bw, bh, Theme.shade(Theme.STEEL, 0.85), 2);
        // control cabinet along the bottom
        double cabH = bh * 0.26;
        box(g, bx + u, by + bh - cabH - u, bw - 2 * u, cabH, Theme.shade(Theme.STEEL_DARK, 1.15), 1);
        g.setColor(new Color(0, 0, 0, 90));
        g.setStroke(new BasicStroke((float) Math.max(0.6, 0.7 * u)));
        for (int i = 1; i < 6; i++) {
            double vx = bx + u + (bw - 2 * u) * i / 8.0;
            g.draw(new Line2D.Double(vx, by + bh - cabH, vx, by + bh - 1.5 * u));
        }
        for (int i = 0; i < 3; i++)
            led(g, bx + bw - (2.5 + i * 2.4) * u, by + bh - cabH / 2 - u, Math.max(0.9, u),
                    i == 0 ? Theme.GOOD : Theme.AMBER, Math.sin(t * (3 + i)) > 0 ? 1 : 0.2);
        // feed tray with matter motes
        double trx = bx + bw * 0.62, trw = bw * 0.32, trh = bh * 0.18, tryy = by + bh * 0.5;
        g.setColor(new Color(0x10, 0x1C, 0x28));
        g.fill(new RoundRectangle2D.Double(trx, tryy, trw, trh, 2, 2));
        g.setColor(Theme.alpha(Theme.ICE, 140));
        g.setStroke(new BasicStroke((float) Math.max(0.7, 0.8 * u)));
        g.draw(new RoundRectangle2D.Double(trx, tryy, trw, trh, 2, 2));
        // shoulder
        double sx = bx + bw * 0.26, sy = by + bh * 0.58;
        g.setColor(Theme.shade(Theme.STEEL_DARK, 1.25));
        g.fill(new Ellipse2D.Double(sx - 4.5 * u, sy - 4.5 * u, 9 * u, 9 * u));
        g.setColor(Theme.alpha(Theme.CHALK, 60));
        g.fill(new Ellipse2D.Double(sx - 2 * u, sy - 2.6 * u, 4 * u, 4 * u));
        double cycle = frac(t * 0.32);
        boolean carrying = cycle > 0.5;
        double swing = Math.sin(cycle * Math.PI * 2);
        double a1 = -1.15 + swing * 0.5;
        double len1 = Math.min(bw, bh) * 0.36, len2 = Math.min(bw, bh) * 0.3;
        double ex = sx + Math.cos(a1) * len1, ey = sy + Math.sin(a1) * len1;
        double a2 = a1 + 1.0 - swing * 0.45;
        double gx = ex + Math.cos(a2) * len2, gy = ey + Math.sin(a2) * len2;
        // hoses
        g.setColor(Theme.alpha(new Color(0x2A, 0x30, 0x38), 220));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.2 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        var hose = new Path2D.Double();
        hose.moveTo(sx, sy + 3 * u);
        hose.quadTo((sx + ex) / 2, ey + 5 * u, ex, ey + 1.5 * u);
        g.draw(hose);
        // arm segments
        g.setStroke(new BasicStroke((float) Math.max(1.8, 3 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.shade(Theme.STEEL, 1.2));
        g.draw(new Line2D.Double(sx, sy, ex, ey));
        g.setStroke(new BasicStroke((float) Math.max(1.4, 2.2 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.shade(Theme.STEEL, 0.95));
        g.draw(new Line2D.Double(ex, ey, gx, gy));
        g.setColor(Theme.shade(Theme.STEEL_DARK, 1.3));
        g.fill(new Ellipse2D.Double(ex - 2 * u, ey - 2 * u, 4 * u, 4 * u));
        // gripper, closed while carrying
        double open = (carrying ? 0.5 : 1.6) * u;
        g.setStroke(new BasicStroke((float) Math.max(1, 1.3 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.AMBER);
        double nx = Math.cos(a2), ny = Math.sin(a2);
        g.draw(new Line2D.Double(gx - ny * open, gy + nx * open, gx + nx * 2.2 * u - ny * open * 0.4, gy + ny * 2.2 * u + nx * open * 0.4));
        g.draw(new Line2D.Double(gx + ny * open, gy - nx * open, gx + nx * 2.2 * u + ny * open * 0.4, gy + ny * 2.2 * u - nx * open * 0.4));
        if (carrying) {
            double mx = gx + nx * 2.6 * u, my = gy + ny * 2.6 * u;
            glow(g, mx, my, 3.2 * u, Theme.AMBER, 150);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(mx - u, my - u, 2 * u, 2 * u));
        }
    }
    /**
     * Circuit assembler: a PCB-colored body with copper trace lines and via pads, three
     * already-placed chips, and a pick-and-place gantry head that sweeps side to side along a
     * cosine path while bobbing down twice per pass (a {@code sin(p * 4pi)} term) to mime a
     * placement dip at each stop.
     */
    private static void assembler(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double bx = x + 2.5 * u, by = y + 2.5 * u, bw = w - 5 * u, bh = h - 5 * u;
        box(g, bx, by, bw, bh, Theme.PCB, 2);
        // traces
        g.setColor(new Color(0xC8, 0x92, 0x3C, 150));
        g.setStroke(new BasicStroke((float) Math.max(0.6, 0.6 * u)));
        int lanes = Math.max(3, (int) (bh / (5 * u)));
        for (int i = 1; i < lanes; i++) {
            double ly = by + bh * i / lanes;
            g.draw(new Line2D.Double(bx + 2 * u, ly, bx + bw - 2 * u, ly));
            g.fill(new Ellipse2D.Double(bx + 2 * u - u, ly - u, 2 * u, 2 * u));
            g.fill(new Ellipse2D.Double(bx + bw - 2 * u - u, ly - u, 2 * u, 2 * u));
        }
        // chips already placed
        g.setColor(new Color(0x1A, 0x1E, 0x22));
        for (int i = 0; i < 3; i++)
            g.fill(new RoundRectangle2D.Double(bx + bw * (0.2 + i * 0.26), by + bh * 0.62, 5 * u, 3.4 * u, 1, 1));
        // pick-and-place gantry
        double p = frac(t * 0.42);
        double gxp = bx + 3 * u + (bw - 6 * u) * (0.5 - 0.5 * Math.cos(p * Math.PI * 2));
        double dip = Math.max(0, Math.sin(p * Math.PI * 4)) * 2.5 * u;
        g.setColor(Theme.alpha(Theme.STEEL, 210));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.2 * u)));
        g.draw(new Line2D.Double(bx + 1.5 * u, by + bh * 0.3, bx + bw - 1.5 * u, by + bh * 0.3));
        g.setColor(Theme.shade(Theme.STEEL, 1.2));
        g.fill(new Rectangle2D.Double(gxp - 2 * u, by + bh * 0.3 - 2 * u, 4 * u, 4 * u + dip));
        g.setColor(Theme.GOOD);
        g.fill(new Rectangle2D.Double(gxp - u, by + bh * 0.3 + 2 * u + dip, 2 * u, 1.6 * u));
    }


    /**
     * Steel foundry: an overhead ladle rail, a mould bed of three ingots that individually heat
     * and cool out of phase (staggered sine terms), and a trunnion-mounted crucible that sits
     * still for most of a slow cycle then tips to pour, drawing a molten stream and scattering
     * spark motes whose horizontal jitter comes from {@link Theme#noise} keyed on spark index.
     */
    private static void foundry(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double bx = x + 2.5 * u, by = y + 3 * u, bw = w - 5 * u, bh = h - 6 * u;
        box(g, bx, by, bw, bh, Theme.shade(Theme.STEEL, 0.78), 2);
        // overhead ladle rail
        g.setColor(Theme.alpha(Theme.STEEL_DARK, 230));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.4 * u)));
        g.draw(new Line2D.Double(bx + 2 * u, by + bh * 0.16, bx + bw - 2 * u, by + bh * 0.16));
        // mould bed with three cooling ingots
        double mx = bx + bw * 0.44, my = by + bh * 0.56, mw = bw * 0.48, mh = bh * 0.34;
        g.setColor(new Color(0x18, 0x1D, 0x23));
        g.fill(new Rectangle2D.Double(mx, my, mw, mh));
        for (int k = 0; k < 3; k++) {
            double heat = Math.max(0, Math.sin(t * 0.5 - k * 0.9));
            g.setColor(Theme.mix(new Color(0x4A, 0x50, 0x58), Theme.EMBER, heat));
            g.fill(new RoundRectangle2D.Double(mx + mw * 0.08, my + mh * (0.12 + k * 0.3), mw * 0.84, mh * 0.2, 2, 2));
        }
        // crucible on a trunnion, tipping on a slow cycle
        double cyc = frac(t * 0.16);
        double tip = cyc < 0.6 ? 0 : Math.sin((cyc - 0.6) / 0.4 * Math.PI) * 0.9;
        double pcx = bx + bw * 0.26, pcy = by + bh * 0.46, rad = Math.min(bw, bh) * 0.2;
        g.setColor(Theme.shade(Theme.STEEL_DARK, 0.8));
        g.fill(new Rectangle2D.Double(pcx - rad * 1.15, pcy - rad * 0.2, rad * 0.35, rad * 1.9));
        g.fill(new Rectangle2D.Double(pcx + rad * 0.8, pcy - rad * 0.2, rad * 0.35, rad * 1.9));
        var old = g.getTransform();
        g.rotate(tip, pcx, pcy);
        g.setPaint(new GradientPaint((float) (pcx - rad), 0, Theme.shade(Theme.STEEL_DARK, 1.35),
                (float) (pcx + rad), 0, Theme.shade(Theme.STEEL_DARK, 0.7)));
        g.fill(new RoundRectangle2D.Double(pcx - rad, pcy - rad * 0.9, rad * 2, rad * 2, rad * 0.7, rad * 0.7));
        g.setColor(Theme.mix(Theme.EMBER, Color.WHITE, 0.4));
        g.fill(new Ellipse2D.Double(pcx - rad * 0.78, pcy - rad * 0.95, rad * 1.56, rad * 0.6));
        g.setTransform(old);
        if (tip > 0.2) {
            g.setColor(Theme.mix(Theme.EMBER, Color.WHITE, 0.5));
            g.setStroke(new BasicStroke((float) Math.max(1.2, 1.6 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(pcx + rad * 0.9, pcy - rad * 0.2, mx + mw * 0.3, my + mh * 0.2));
            glow(g, mx + mw * 0.3, my + mh * 0.2, rad * 1.5, Theme.EMBER, 170);
            for (int k = 0; k < 4; k++) {
                double p = frac(t * 2.4 + k * 0.25);
                double sxp = mx + mw * 0.3 + (Theme.noise(k * 31) - 0.5) * rad * 2 * p;
                g.setColor(Theme.alpha(Theme.mix(Theme.EMBER, Color.WHITE, 0.7), (int) (200 * (1 - p))));
                g.fill(new Ellipse2D.Double(sxp, my + mh * 0.2 - p * rad * 1.6, u, u));
            }
        }
        glow(g, mx + mw * 0.5, my + mh * 0.5, rad * (1 + 0.15 * Math.sin(t * 2)), Theme.EMBER, 60);
        bolts(g, bx, by, bw, bh, u);
    }
    /**
     * Capacitor bank: a grid of cylindrical cells sized to fit the plate, a charge-level bar
     * driven by {@code level}, and an occasional electrical arc jumping across the terminals.
     *
     * <p>The arc is gated by a time-bucketed noise check: {@code bucket = floor(t * 3)} slices
     * time into third-of-a-second windows, and the arc fires only when
     * {@code Theme.noise((int) bucket * 977) > 0.86} &mdash; roughly a 14% chance per bucket,
     * held fixed for that whole third-of-a-second rather than re-rolled every frame (which
     * would make it flicker rather than snap on and off). The {@code 977} multiplier has no
     * meaning beyond decorrelation: it exists purely so this bank's arc-seed sequence doesn't
     * happen to line up with the smaller seeds ({@code seed}, group id, loop index) that other
     * {@code Theme.noise} lookups elsewhere in the file use, so unrelated effects don't
     * conspicuously fire in sync.
     *
     * @param level 0..1 charge fill fraction shown by the indicator bar
     */
    private static void capacitors(Graphics2D g, double x, double y, double w, double h, double u, double t, double level) {
        double bx = x + 2.5 * u, by = y + 2.5 * u, bw = w - 5 * u, bh = h - 5 * u;
        box(g, bx, by, bw, bh, Theme.shade(Theme.STEEL, 0.75), 2);
        int nx = Math.max(2, (int) Math.round(bw / (7 * u)));
        int ny = Math.max(1, (int) Math.round(bh / (9 * u)));
        double cw = bw / nx, ch = bh / ny;
        for (int iy = 0; iy < ny; iy++) {
            for (int ixx = 0; ixx < nx; ixx++) {
                double ccx = bx + cw * (ixx + 0.5), ccy = by + ch * (iy + 0.5);
                double rw = cw * 0.56, rh = ch * 0.66;
                cylinder(g, ccx - rw / 2, ccy - rh / 2, rw, rh, new Color(0x3E, 0x4A, 0x58));
                g.setColor(Theme.alpha(Theme.CHALK, 90));
                g.setStroke(new BasicStroke((float) Math.max(0.7, 0.8 * u)));
                g.draw(new Line2D.Double(ccx - rw * 0.3, ccy - rh * 0.28, ccx + rw * 0.3, ccy - rh * 0.28));
            }
        }
        // charge indicator
        double gx = bx + 1.5 * u, gy = by + bh - 2.6 * u, gw = bw - 3 * u;
        g.setColor(new Color(0, 0, 0, 130));
        g.fill(new Rectangle2D.Double(gx, gy, gw, 1.8 * u));
        g.setColor(Theme.mix(Theme.AMBER, Theme.GOOD, level));
        g.fill(new Rectangle2D.Double(gx, gy, gw * Math.max(0, Math.min(1, level)), 1.8 * u));
        // occasional arc across the terminals
        double bucket = Math.floor(t * 3);
        if (Theme.noise((int) bucket * 977) > 0.86) {
            g.setColor(Theme.alpha(Theme.CHERENKOV, 190));
            g.setStroke(new BasicStroke((float) Math.max(0.8, u)));
            double ay = by + ch * 0.5;
            var p = new Path2D.Double();
            p.moveTo(bx + cw * 0.5, ay);
            for (int k = 1; k <= 4; k++)
                p.lineTo(bx + cw * (0.5 + k * 0.25), ay + (Theme.noise((int) bucket + k) - 0.5) * 3 * u);
            g.draw(p);
        }
    }

    /**
     * Research lab: a cabinet with a screen showing a scrolling two-frequency sine trace (a
     * decorative oscilloscope readout, not wired to any real simulation signal), a spinning
     * centrifuge, and three independently blinking status lamps.
     */
    private static void lab(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double bx = x + 2.5 * u, by = y + 2.5 * u, bw = w - 5 * u, bh = h - 5 * u;
        box(g, bx, by, bw, bh, Theme.CABINET, 2);
        // screen with a scrolling trace
        double sx = bx + 2 * u, sy = by + 2 * u, sw = bw - 4 * u, sh = bh * 0.44;
        g.setColor(new Color(0x0A, 0x18, 0x24));
        g.fill(new RoundRectangle2D.Double(sx, sy, sw, sh, 2, 2));
        var clip = g.getClip();
        g.clip(new Rectangle2D.Double(sx, sy, sw, sh));
        g.setColor(Theme.alpha(Theme.CHERENKOV, 200));
        g.setStroke(new BasicStroke((float) Math.max(0.8, 0.9 * u)));
        var path = new Path2D.Double();
        for (int i = 0; i <= 40; i++) {
            double px = sx + sw * i / 40.0;
            double ph = Math.sin(i * 0.55 + t * 3.2) * 0.32 + Math.sin(i * 0.19 - t * 1.7) * 0.16;
            double py = sy + sh / 2 + ph * sh;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        g.draw(path);
        g.setClip(clip);
        // centrifuge
        double rad = Math.min(bw, bh) * 0.15;
        double ccx = bx + bw * 0.3, ccy = by + bh * 0.76;
        g.setColor(Theme.shade(Theme.CABINET, 0.7));
        g.fill(new Ellipse2D.Double(ccx - rad, ccy - rad, rad * 2, rad * 2));
        rotor(g, ccx, ccy, rad * 0.8, t * 6.5, 3, Theme.shade(Theme.STEEL_DARK, 1.1), u);
        // status lamps
        for (int i = 0; i < 3; i++) {
            double on = Math.sin(t * (2 + i) + i) > 0.2 ? 1 : 0.15;
            led(g, bx + bw * (0.62 + i * 0.13), by + bh * 0.78, Math.max(1, 1.2 * u), i == 2 ? Theme.GOOD : Theme.AMBER, on);
        }
    }

    /**
     * Amplifier node: a violet coil housing with horizontal winding lines and a pulsing central
     * glow, plus arcs of current occasionally jumping around the housing.
     *
     * <p>Like {@link #capacitors}, the arcs use a time-bucketed noise gate:
     * {@code bucket = floor(t * 7)} slices time into 1/7-second windows, and each of two
     * candidate arcs draws only when {@code Theme.noise(bucket * 131 + k * 37) >= 0.35}. The
     * {@code 131}/{@code 37} multipliers just spread the two arcs' seeds apart (and away from
     * other noise lookups in the file) so they don't happen to fire in sync with each other or
     * with unrelated effects. Each arc that does fire is a jagged polyline whose intermediate
     * radii are perturbed by further {@code Theme.noise} lookups, not a fixed shape.
     */
    private static void node(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double bx = x + 2.5 * u, by = y + 2.5 * u, bw = w - 5 * u, bh = h - 5 * u;
        box(g, bx, by, bw, bh, Theme.VIOLET, 3);
        double cx = bx + bw / 2, cy = by + bh / 2;
        // coil windings
        g.setColor(Theme.alpha(Theme.COPPER, 210));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.3 * u)));
        int turns = Math.max(3, (int) (bh / (5 * u)));
        for (int i = 0; i < turns; i++) {
            double ly = by + bh * (i + 0.7) / (turns + 0.6);
            g.draw(new Line2D.Double(bx + 2.5 * u, ly, bx + bw - 2.5 * u, ly));
        }
        double pulse = 0.55 + 0.45 * Math.sin(t * 3.1);
        glow(g, cx, cy, Math.min(bw, bh) * 0.42 * (0.8 + 0.3 * pulse), Theme.mix(Theme.VIOLET, Color.WHITE, 0.5), (int) (120 * pulse));
        // arcs jumping around the housing
        int bucket = (int) Math.floor(t * 7);
        g.setStroke(new BasicStroke((float) Math.max(0.8, u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int k = 0; k < 2; k++) {
            double n = Theme.noise(bucket * 131 + k * 37);
            if (n < 0.35) continue;
            double a0 = n * Math.PI * 2;
            var p = new Path2D.Double();
            double rad = Math.min(bw, bh) * 0.42;
            p.moveTo(cx + Math.cos(a0) * rad, cy + Math.sin(a0) * rad);
            for (int s = 1; s <= 3; s++) {
                double a = a0 + s * 0.22;
                double rr = rad * (0.75 + Theme.noise(bucket + s * 17 + k) * 0.4);
                p.lineTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr);
            }
            g.setColor(Theme.alpha(Theme.mix(Theme.CHERENKOV, Color.WHITE, 0.4), 200));
            g.draw(p);
        }
    }

    /**
     * Blast furnace: a tapered bosh shape (narrow throat, wide belly) with tuyere ports down
     * both sides, a flickering hot glow at the base, a charging skip and smoking stack at the
     * top, and an occasional tap-hole drip that grows across a slow cycle before resetting.
     *
     * @param seed per-group seed, passed to {@link #flicker} and {@link #smoke} for desync
     */
    private static void blast(Graphics2D g, double x, double y, double w, double h, double u, double t, int seed) {
        double cx = x + w / 2;
        double bTop = y + 5 * u, bBot = y + h - 4 * u;
        double topW = w * 0.42, botW = w * 0.72;
        var bosh = new Path2D.Double();
        bosh.moveTo(cx - topW / 2, bTop);
        bosh.lineTo(cx + topW / 2, bTop);
        bosh.lineTo(cx + botW / 2, bBot);
        bosh.lineTo(cx - botW / 2, bBot);
        bosh.closePath();
        g.setPaint(new GradientPaint((float) (cx - botW / 2), 0, Theme.shade(Theme.STEEL, 1.15),
                (float) (cx + botW / 2), 0, Theme.shade(Theme.STEEL, 0.55)));
        g.fill(bosh);
        g.setColor(new Color(0, 0, 0, 150));
        g.draw(bosh);
        // tuyeres
        g.setColor(Theme.shade(Theme.STEEL_DARK, 1.2));
        for (int i = 0; i < 3; i++) {
            double ty = bBot - (i + 1) * (bBot - bTop) * 0.16;
            g.fill(new Rectangle2D.Double(cx - botW / 2 - 2.5 * u, ty, 3 * u, 1.8 * u));
            g.fill(new Rectangle2D.Double(cx + botW / 2 - 0.5 * u, ty, 3 * u, 1.8 * u));
        }
        double f = flicker(t, seed);
        glow(g, cx, bBot - 2 * u, botW * 0.5 * (0.9 + 0.2 * f), Theme.EMBER, (int) (190 * f));
        g.setColor(Theme.mix(Theme.EMBER, Color.WHITE, 0.3 * f));
        g.fill(new Rectangle2D.Double(cx - botW * 0.3, bBot - 3 * u, botW * 0.6, 2.4 * u));
        // charging skip and stack
        box(g, cx - topW / 2 - u, y + 2 * u, topW + 2 * u, 3.5 * u, Theme.STEEL_DARK, 1);
        smoke(g, cx, y + 2 * u, u, t, seed, 4, new Color(170, 178, 188));
        double p = frac(t * 0.3);
        g.setColor(Theme.alpha(Theme.EMBER, (int) (150 * (1 - p))));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.4 * u)));
        g.draw(new Line2D.Double(cx + botW / 2, bBot - 2 * u, cx + botW / 2 + 4 * u * p, bBot + u * p));
    }

    /**
     * Induction furnace: a glowing molten pool inside a dark crucible, encircled by four copper
     * coil loops drawn at different flattenings (each scaled by {@code cos} of a rotating
     * phase) so they appear to orbit the pool in 3D despite being drawn on a flat 2D canvas.
     */
    private static void induction(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double cx = x + w / 2, cy = y + h / 2;
        double cw = w * 0.5, ch = h * 0.5;
        box(g, x + 2.5 * u, y + 2.5 * u, w - 5 * u, h - 5 * u, Theme.shade(Theme.STEEL, 0.7), 2);
        // crucible
        g.setColor(new Color(0x20, 0x1A, 0x16));
        g.fill(new Ellipse2D.Double(cx - cw / 2, cy - ch / 2, cw, ch));
        double pulse = 0.6 + 0.4 * Math.sin(t * 2.4);
        glow(g, cx, cy, cw * 0.55 * (0.9 + 0.2 * pulse), new Color(0xFF, 0xA8, 0x55), (int) (190 * pulse));
        g.setColor(Theme.mix(new Color(0xFF, 0xA8, 0x55), Color.WHITE, 0.3 * pulse));
        g.fill(new Ellipse2D.Double(cx - cw * 0.34, cy - ch * 0.34, cw * 0.68, ch * 0.68));
        // copper coils rotating around it
        g.setStroke(new BasicStroke((float) Math.max(1.2, 1.6 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 4; i++) {
            double a = t * 1.1 + i * Math.PI / 2;
            double sw = Math.abs(Math.cos(a));
            g.setColor(Theme.alpha(Theme.COPPER, (int) (120 + 120 * sw)));
            double rw = cw * 0.75, rh = ch * (0.2 + 0.55 * sw);
            g.draw(new Ellipse2D.Double(cx - rw, cy - rh, rw * 2, rh * 2));
        }
    }


    /**
     * Oil refinery: a row of fractionating columns (count derived from the plate width) at
     * staggered heights, each with tray lines, a condenser coil, and bubbles climbing at times
     * offset per column and per bubble via {@link Theme#noise}, plus a flickering flare stack.
     *
     * @param seed per-group seed, mixed with column and bubble index so bubble timing stays
     *             deterministic but decorrelated between columns
     */
    private static void refinery(Graphics2D g, double x, double y, double w, double h, double u, double t, int seed) {
        box(g, x + 2 * u, y + h - 7.5 * u, w - 4 * u, 5.5 * u, Theme.shade(Theme.STEEL, 0.68), 2);
        int cols = Math.max(2, (int) Math.round(w / (13 * u)));
        double bot = y + h - 6.5 * u;
        for (int i = 0; i < cols; i++) {
            double slot = (w - 6 * u) / cols;
            double cw = slot * 0.52;
            double cxp = x + 3 * u + slot * (i + 0.5) - cw / 2;
            double top = y + (i % 2 == 0 ? 3.5 * u : 6 * u);
            cylinder(g, cxp, top, cw, bot - top, new Color(0x82, 0x8C, 0x99));
            // dome
            g.setColor(Theme.shade(new Color(0x82, 0x8C, 0x99), 1.15));
            g.fill(new Arc2D.Double(cxp, top - cw * 0.4, cw, cw * 0.8, 0, 180, Arc2D.PIE));
            // trays
            g.setColor(new Color(0, 0, 0, 75));
            g.setStroke(new BasicStroke((float) Math.max(0.6, 0.7 * u)));
            for (int k = 1; k < 6; k++) {
                double ly = top + (bot - top) * k / 6.0;
                g.draw(new Line2D.Double(cxp + 0.5, ly, cxp + cw - 0.5, ly));
            }
            // condenser coil down one side
            g.setColor(Theme.alpha(Theme.COPPER, 200));
            g.setStroke(new BasicStroke((float) Math.max(0.8, 0.9 * u)));
            for (int k = 0; k < 4; k++) {
                double ly = top + (bot - top) * (0.2 + k * 0.2);
                g.draw(new Arc2D.Double(cxp + cw - u, ly, 3 * u, 2.4 * u, 270, 180, Arc2D.OPEN));
            }
            // bubbles climbing the column
            for (int k = 0; k < 3; k++) {
                double p = frac(t * 0.4 + k / 3.0 + Theme.noise(seed + i * 5 + k));
                double by2 = bot - (bot - top) * p;
                g.setColor(Theme.alpha(Res.TITANIUM_ORE.color, (int) (170 * (1 - p))));
                double rad = Math.max(0.7, 1.2 * u);
                g.fill(new Ellipse2D.Double(cxp + cw / 2 - rad, by2 - rad, rad * 2, rad * 2));
            }
        }
        // flare stack
        double fx = x + w - 5 * u;
        g.setColor(Theme.shade(Theme.STEEL_DARK, 1.1));
        g.fill(new Rectangle2D.Double(fx - u, y + 4 * u, 2 * u, h - 11 * u));
        double fl = 0.6 + 0.4 * Math.abs(Math.sin(t * 5.5 + seed));
        glow(g, fx, y + 3.5 * u, 3.4 * u * fl, Theme.EMBER, 170);
        g.setColor(Theme.alpha(Theme.mix(Theme.EMBER, Color.WHITE, 0.5), 220));
        var flame = new Path2D.Double();
        flame.moveTo(fx - 1.2 * u, y + 4 * u);
        flame.quadTo(fx, y + 4 * u - 4.5 * u * fl, fx + 1.2 * u, y + 4 * u);
        flame.closePath();
        g.fill(flame);
        g.setColor(Theme.alpha(Theme.STEEL, 210));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.4 * u)));
        g.draw(new Line2D.Double(x + 3 * u, y + h - 5 * u, x + w - 3 * u, y + h - 5 * u));
    }
    /**
     * Fission reactor: a concrete housing around a glowing containment well with a hexagonal
     * fuel-pin lattice, three control rods drifting up and down on independently phased sine
     * curves, and two smoking cooling towers built as trapezoid silhouettes.
     *
     * @param seed per-group seed, offset per cooling tower and passed to {@link #smoke}
     */
    private static void reactor(Graphics2D g, double x, double y, double w, double h, double u, double t, int seed) {
        double bx = x + 2.5 * u, by = y + 2.5 * u, bw = w - 5 * u, bh = h - 5 * u;
        box(g, bx, by, bw, bh, Theme.shade(Theme.CONCRETE, 1.1), 3);
        double cx = bx + bw * 0.42, cy = by + bh * 0.52;
        double rad = Math.min(bw, bh) * 0.3;
        // containment well
        g.setColor(new Color(0x0A, 0x18, 0x22));
        g.fill(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
        double pulse = 0.6 + 0.4 * Math.sin(t * 1.5);
        glow(g, cx, cy, rad * (1.05 + 0.15 * pulse), Theme.CHERENKOV, (int) (170 * pulse));
        // hexagonal fuel lattice
        g.setColor(Theme.alpha(Theme.mix(Theme.CHERENKOV, Color.WHITE, 0.4), 190));
        double step = rad * 0.46;
        for (int iy = -1; iy <= 1; iy++) {
            for (int ixx = -1; ixx <= 1; ixx++) {
                double px = cx + ixx * step + (iy != 0 ? step * 0.5 * iy : 0);
                double py = cy + iy * step * 0.9;
                if (Math.hypot(px - cx, py - cy) > rad * 0.8) continue;
                g.fill(new Ellipse2D.Double(px - step * 0.16, py - step * 0.16, step * 0.32, step * 0.32));
            }
        }
        // control rods, drifting
        g.setColor(Theme.alpha(Theme.CHALK, 150));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.2 * u)));
        for (int i = 0; i < 3; i++) {
            double px = cx - rad * 0.5 + i * rad * 0.5;
            double drop = (0.5 + 0.5 * Math.sin(t * 0.7 + i)) * rad * 0.3;
            g.draw(new Line2D.Double(px, by + 1.5 * u, px, cy - rad * 0.6 + drop));
        }
        // cooling towers
        for (int i = 0; i < 2; i++) {
            double tx = bx + bw * (0.76 + i * 0.14), tw = bw * 0.11;
            var tower = new Path2D.Double();
            tower.moveTo(tx - tw * 0.6, by + bh - 2 * u);
            tower.lineTo(tx - tw * 0.35, by + bh * 0.42);
            tower.lineTo(tx + tw * 0.35, by + bh * 0.42);
            tower.lineTo(tx + tw * 0.6, by + bh - 2 * u);
            tower.closePath();
            g.setColor(Theme.shade(Theme.CONCRETE, 1.25));
            g.fill(tower);
            g.setColor(new Color(0, 0, 0, 110));
            g.draw(tower);
            smoke(g, tx, by + bh * 0.42, u * 0.8, t, seed + i * 11, 3, new Color(220, 232, 240));
        }
    }

    /**
     * Matter replicator: a dark containment sphere with particles orbiting on flattened,
     * perspective-style ellipses at varying radii, a faint equatorial ring, and a pulsing
     * white glow at the center.
     */
    private static void replicator(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double bx = x + 2.5 * u, by = y + 2.5 * u, bw = w - 5 * u, bh = h - 5 * u;
        box(g, bx, by, bw, bh, new Color(0x2A, 0x2E, 0x40), 4);
        double cx = bx + bw / 2, cy = by + bh / 2;
        double rad = Math.min(bw, bh) * 0.36;
        g.setColor(new Color(0x06, 0x0A, 0x14));
        g.fill(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
        double pulse = 0.5 + 0.5 * Math.sin(t * 2.2);
        // orbiting particles
        for (int i = 0; i < 7; i++) {
            double a = t * 1.6 + i * Math.PI * 2 / 7;
            double rr = rad * (0.55 + 0.3 * Math.sin(t * 1.1 + i));
            double px = cx + Math.cos(a) * rr, py = cy + Math.sin(a) * rr * 0.55;
            double sz = Math.max(0.8, 1.4 * u);
            g.setColor(Theme.alpha(Theme.mix(Theme.ICE, Color.WHITE, 0.5), 200));
            g.fill(new Ellipse2D.Double(px - sz, py - sz, sz * 2, sz * 2));
        }
        g.setColor(Theme.alpha(Theme.ICE, 90));
        g.setStroke(new BasicStroke((float) Math.max(0.8, u)));
        g.draw(new Ellipse2D.Double(cx - rad * 0.85, cy - rad * 0.47, rad * 1.7, rad * 0.94));
        glow(g, cx, cy, rad * (0.5 + 0.25 * pulse), Color.WHITE, (int) (180 * pulse));
    }

    /**
     * Fusion tokamak: radial field coils around a torus-shaped containment channel, with three
     * plasma arcs sweeping around the ring at staggered phases and fading outward toward the
     * back of the torus.
     */
    private static void tokamak(Graphics2D g, double x, double y, double w, double h, double u, double t) {
        double bx = x + 2.5 * u, by = y + 2.5 * u, bw = w - 5 * u, bh = h - 5 * u;
        box(g, bx, by, bw, bh, Theme.shade(Theme.STEEL, 0.72), 4);
        double cx = bx + bw / 2, cy = by + bh / 2;
        double outer = Math.min(bw, bh) * 0.42, inner = outer * 0.45;
        // field coils
        g.setColor(Theme.alpha(Theme.COPPER, 220));
        g.setStroke(new BasicStroke((float) Math.max(1.2, 1.8 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            g.draw(new Line2D.Double(cx + Math.cos(a) * inner, cy + Math.sin(a) * inner,
                    cx + Math.cos(a) * (outer + 1.5 * u), cy + Math.sin(a) * (outer + 1.5 * u)));
        }
        g.setColor(new Color(0x08, 0x10, 0x1A));
        g.setStroke(new BasicStroke((float) Math.max(2, (outer - inner))));
        g.draw(new Ellipse2D.Double(cx - (outer + inner) / 2, cy - (outer + inner) / 2, outer + inner, outer + inner));
        // plasma sweeping round the torus
        double ringR = (outer + inner) / 2, thick = Math.max(1.5, (outer - inner) * 0.55);
        g.setStroke(new BasicStroke((float) thick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int k = 0; k < 3; k++) {
            double a0 = Math.toDegrees(t * 2.4) + k * 120;
            g.setColor(Theme.alpha(Theme.mix(Theme.CHERENKOV, Color.WHITE, 0.55), 90 - k * 20));
            g.draw(new Arc2D.Double(cx - ringR, cy - ringR, ringR * 2, ringR * 2, a0, 70, Arc2D.OPEN));
        }
        glow(g, cx, cy, outer * (1.05 + 0.1 * Math.sin(t * 3)), Theme.CHERENKOV, 120);
    }
}
