package substrate;

import javax.swing.JComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * One line under the board: whatever the pointer is over, or the last thing that happened.
 * Renders a sequence of colored {@link Ui.Seg} segments so a single line can mix, say, a dim
 * label with a highlighted value.
 */
public final class StatusBar extends JComponent {
    /** Segments currently displayed; replaced wholesale on every {@link #set} call. */
    private List<Ui.Seg> segs = new ArrayList<>();

    /** Transparent status strip using the theme's monospace font. */
    public StatusBar() {
        setOpaque(false);
        setFont(Theme.mono(11));
    }

    /** Replaces the displayed segments and repaints. */
    public void set(List<Ui.Seg> segments) {
        segs = segments;
        repaint();
    }

    /** Convenience for the common case of a single dim, uncolored message. */
    public void plain(String text) { set(List.of(Ui.Seg.of(text, Theme.DIM))); }

    /** Fixed-height preferred size; width is nominal since the bar stretches to fill its container. */
    @Override public Dimension getPreferredSize() { return new Dimension(300, 26); }

    /** Draws the top rule line, then the current segments left to right. */
    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Theme.LINE);
        g.drawLine(0, 0, getWidth(), 0);
        g.setFont(getFont());
        Ui.segments(g, segs, 8, 17);
    }
}
