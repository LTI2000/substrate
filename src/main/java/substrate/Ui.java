package substrate;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/** Small shared widgets: text wrapping, coloured line segments, a dark scrollbar. */
public final class Ui {
    private Ui() {}

    /** A run of text in a single colour, meant to be drawn inline with other segments via {@link #segments}. */
    public record Seg(String text, Color color) {
        /** Convenience factory, reads better at call sites than {@code new Seg(...)}. */
        public static Seg of(String t, Color c) { return new Seg(t, c); }
    }

    /**
     * Greedy word wrap: appends words to the current line while it still fits {@code width},
     * otherwise starts a new line. Splits purely on spaces, so a single word wider than
     * {@code width} is left to overflow rather than being broken mid-word.
     *
     * @return the wrapped lines, in order; empty if {@code text} is null or blank
     */
    public static List<String> wrap(String text, FontMetrics fm, int width) {
        var out = new ArrayList<String>();
        if (text == null || text.isBlank()) return out;
        var line = new StringBuilder();
        for (String word : text.split(" ")) {
            String probe = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(probe) > width && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(probe);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    /** Draws segments left to right, returns the x it finished at. */
    public static float segments(Graphics2D g, List<Seg> segs, float x, float y) {
        for (Seg s : segs) {
            g.setColor(s.color());
            g.drawString(s.text(), x, y);
            x += g.getFontMetrics().stringWidth(s.text());
        }
        return x;
    }

    /**
     * Wraps {@code inner} in a borderless, transparent {@link JScrollPane} with a thin,
     * flat, always-dark scrollbar (no arrow buttons, custom track/thumb painting) to match
     * the app's look instead of the platform's default scrollbar chrome.
     */
    public static JScrollPane scroll(JComponent inner) {
        var sp = new JScrollPane(inner);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
        sp.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            /** No-op: colours are supplied by the overridden paint methods below, not L&amp;F defaults. */
            @Override protected void configureScrollBarColors() { }
            /** Replaces the default decrease arrow button with a zero-size stub, hiding it. */
            @Override protected JButton createDecreaseButton(int o) { return zero(); }
            /** Replaces the default increase arrow button with a zero-size stub, hiding it. */
            @Override protected JButton createIncreaseButton(int o) { return zero(); }
            /** An invisible, zero-size button used to suppress {@link BasicScrollBarUI}'s arrow buttons. */
            private JButton zero() {
                var b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                b.setBorder(BorderFactory.createEmptyBorder());
                return b;
            }
            /** Fills the scrollbar track with the page background colour instead of the L&amp;F default. */
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
                g.setColor(Theme.PAPER);
                g.fillRect(r.x, r.y, r.width, r.height);
            }
            /** Draws the thumb as a flat, inset-by-2px rectangle in the theme's line colour. */
            @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                var g2 = (Graphics2D) g;
                g2.setColor(Theme.LINE2);
                g2.fill(new Rectangle2D.Double(r.x + 2, r.y, r.width - 4, r.height));
            }
        });
        return sp;
    }

    /** A flat label-ish button in the survey-chart style. */
    public static final class Chip extends JComponent {
        private final String text;
        private final Runnable action;
        /** Whether the mouse is currently over the chip; drives the hover colours. */
        private boolean hover;

        /** @param action invoked on click */
        public Chip(String text, Runnable action) {
            this.text = text;
            this.action = action;
            setFont(Theme.mono(10));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e)  { hover = false; repaint(); }
                @Override public void mouseClicked(java.awt.event.MouseEvent e) { action.run(); }
            });
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        /** Sized to the text plus fixed horizontal padding, with a fixed 22px height. */
        @Override public Dimension getPreferredSize() {
            var fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + 18, 22);
        }

        /** Draws the border and centred label, brighter when hovered. */
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(hover ? Theme.LINE2 : Theme.LINE);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g.setFont(getFont());
            g.setColor(hover ? Theme.CHALK : Theme.DIM);
            var fm = g.getFontMetrics();
            g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2f,
                    (getHeight() + fm.getAscent()) / 2f - 1);
        }
    }
}
