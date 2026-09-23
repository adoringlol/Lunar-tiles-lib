package com.weave.lunarmods;

/** The line a text HUD element shows. Called every frame while it is visible, so keep it cheap. */
public interface HudText {
    /**
     * @param editing whether Lunar's HUD editor is open
     * @return the text (color codes allowed), or {@code null} or empty to hide the element; in the
     *     HUD editor a hidden element shows the tile name instead so it can still be placed
     */
    String text(boolean editing);
}
