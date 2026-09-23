package com.weave.lunarmods;

import com.weave.lunarmods.internal.Log;
import com.weave.lunarmods.internal.lunar.LunarBridge;
import net.minecraft.client.renderer.GlStateManager;

import java.util.Objects;
import java.util.function.Function;

/**
 * A tile's element in Lunar's HUD editor, created with {@link LunarMod#hud} or
 * {@link LunarMod#textHud}. It belongs to the tile's on/off switch, like Lunar's own HUD mods:
 *
 * <ul>
 *     <li>it is drawn, and listed in the HUD editor, only while the tile is on;</li>
 *     <li>the editor's X (remove) button switches the tile off;</li>
 *     <li>the editor's settings button opens the tile's settings;</li>
 *     <li>its position, anchor and size are saved with the tile's other settings.</li>
 * </ul>
 */
public final class Hud {
    private final LunarMod mod;
    private final HudRenderer renderer;
    private final HudText text;
    private volatile HudAnchor anchor = HudAnchor.TOP_LEFT;
    private volatile float x;
    private volatile float y;
    private volatile boolean created;
    private boolean failed;

    Hud(LunarMod mod, HudRenderer renderer, HudText text) {
        this.mod = mod;
        this.renderer = renderer;
        this.text = text;
    }

    /**
     * Sets where the element starts before the player moves it: {@code x}, {@code y} are GUI pixels
     * from the anchor point (use negative values to move left of or above a right or bottom anchor).
     * Call it from your mod's {@code init()}.
     */
    public Hud position(HudAnchor anchor, float x, float y) {
        if (created) {
            Log.warn("The HUD position of tile '" + mod.id() + "' was set after the tile was registered; set it from your mod's init().");
        }
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.x = x;
        this.y = y;
        return this;
    }

    public HudAnchor anchor() {
        return anchor;
    }

    /** Whether this is Lunar's styled text element rather than a custom-drawn one. */
    public boolean isText() {
        return text != null;
    }

    /** Builds the Lunar HUD object, or returns {@code null} when this Lunar build has no HUD editor. */
    Object create(LunarBridge lunar) throws Exception {
        created = true;
        if (!lunar.supportsHud()) {
            Log.warn("The HUD element of tile '" + mod.id() + "' is not shown: this Lunar version's HUD editor was not found.");
            return null;
        }
        if (text != null) {
            return lunar.createTextHud(mod.id(), anchor.lunarName, x, y, new Function<Boolean, String>() {
                @Override
                public String apply(Boolean editing) {
                    return line(editing);
                }
            });
        }
        return lunar.createCustomHud(anchor.lunarName, x, y, new LunarBridge.HudCallbacks() {
            @Override
            public float width(boolean editing) {
                return size(true, editing);
            }

            @Override
            public float height(boolean editing) {
                return size(false, editing);
            }

            @Override
            public void draw(float drawX, float drawY, boolean editing) {
                if (failed) {
                    return;
                }
                GlStateManager.pushMatrix();
                try {
                    GlStateManager.color(1f, 1f, 1f, 1f);
                    renderer.render(drawX, drawY, editing);
                } catch (RuntimeException exception) {
                    fail(exception);
                } finally {
                    GlStateManager.popMatrix();
                    GlStateManager.color(1f, 1f, 1f, 1f);
                }
            }
        });
    }

    private String line(boolean editing) {
        String line = null;
        if (!failed) {
            try {
                line = text.text(editing);
            } catch (RuntimeException exception) {
                fail(exception);
            }
        }
        if (line == null || line.isEmpty()) {
            return editing ? mod.name() : null;
        }
        return line;
    }

    private float size(boolean width, boolean editing) {
        if (failed) {
            return 0f;
        }
        try {
            float size = width ? renderer.width(editing) : renderer.height(editing);
            return size > 0f ? size : 0f;
        } catch (RuntimeException exception) {
            fail(exception);
            return 0f;
        }
    }

    /** A broken renderer is switched off after its first error instead of failing every frame. */
    private void fail(RuntimeException exception) {
        failed = true;
        Log.error("The HUD element of tile '" + mod.id() + "' failed and is hidden until restart", exception);
    }
}
