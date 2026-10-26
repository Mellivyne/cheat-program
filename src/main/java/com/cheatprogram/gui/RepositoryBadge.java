package com.cheatprogram.gui;

import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Icon;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.ProjectRepository;

/** The project attribution is part of the panel chrome, independent of profiles and themes. */
final class RepositoryBadge {
    private RepositoryBadge() { }

    static void draw(GuiBase panel, float minLeft, float right, float top, Runnable open, java.util.function.Consumer<String> tooltip) {
        String label = ProjectRepository.label();
        float available = right - minLeft;
        float size = 8.5f;
        // Keep the complete address readable, including with wider custom fonts.
        while (size > 5.5f && panel.c.textWidth(Fonts.Face.REGULAR, label, size) + 32 > available) size -= .25f;
        float width = panel.c.textWidth(Fonts.Face.REGULAR, label, size) + 32;
        float x = right - width;
        boolean hovered = panel.over(x, top, width, 22);
        float hover = panel.hoverAnim("hdr.repository", hovered);
        panel.c.lens(x, top, width, 22, 11, Theme.lift(.045f + hover * .055f), Theme.lensGlass(panel.c.scale));
        int color = Anim.lerpColor(Theme.TEXT_2, Theme.TEXT, hover);
        panel.c.icon(Icon.LINK, x + 12, top + 11, 10, color);
        panel.c.textC(Fonts.Face.REGULAR, label, x + 23, top + 11, size, color);
        if (hovered) tooltip.accept(ProjectRepository.uri().toASCIIString());
        if (!panel.hoverBlocked) panel.hit(x, top, width, 22).left(open);
    }
}
