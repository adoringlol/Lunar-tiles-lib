package com.weave.lunarmods;

/**
 * The screen point a HUD element's default position is measured from. Players can drag the
 * element anywhere in Lunar's HUD editor; the anchor it snaps to there is saved with the tile.
 */
public enum HudAnchor {
    TOP_LEFT("TOP_LEFT"),
    TOP_CENTER("TOP_CENTER"),
    TOP_RIGHT("TOP_RIGHT"),
    MIDDLE_LEFT("MIDDLE_LEFT"),
    MIDDLE_CENTER("MIDDLE_CENTER"),
    MIDDLE_RIGHT("MIDDLE_RIGHT"),
    BOTTOM_LEFT("BOTTOM_LEFT"),
    /** Just left of the hotbar. */
    BOTTOM_CENTER_LEFT("BOTTOM_CENTER_L"),
    /** Just right of the hotbar. */
    BOTTOM_CENTER_RIGHT("BOTTOM_CENTER_R"),
    BOTTOM_RIGHT("BOTTOM_RIGHT");

    final String lunarName;

    HudAnchor(String lunarName) {
        this.lunarName = lunarName;
    }
}
