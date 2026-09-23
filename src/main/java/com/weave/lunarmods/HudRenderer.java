package com.weave.lunarmods;

/**
 * Draws a custom HUD element. Lunar calls these every frame while the tile is on (and in the HUD
 * editor), on the render thread.
 *
 * <p>Sizes are in GUI pixels at scale 1. The GL matrix is already scaled to the element's size
 * setting, so draw with the usual Minecraft calls ({@code Gui.drawRect}, the font renderer, ...)
 * inside the box from ({@code x}, {@code y}) to ({@code x + width}, {@code y + height}).
 *
 * <p>That box is what the HUD editor outlines, drags and snaps, so report a size that covers
 * everything you draw, including text shadows (one pixel right and down) and outlines.
 */
public interface HudRenderer {
    /** @param editing whether Lunar's HUD editor is open (show sample content there if you have none) */
    float width(boolean editing);

    float height(boolean editing);

    void render(float x, float y, boolean editing);
}
