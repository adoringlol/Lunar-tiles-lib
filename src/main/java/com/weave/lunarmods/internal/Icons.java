package com.weave.lunarmods.internal;

import com.weave.lunarmods.internal.lunar.LunarBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.Locale;

/**
 * Turns a tile's icon into the two textures Lunar draws: 20x20 for the list view and 52x52 for the
 * grid (drawn at 26x26 GUI pixels, so it stays sharp at GUI scale 2). Must run on the render thread.
 */
public final class Icons {
    private static final String DOMAIN = "lunarmodapi";
    private static final int LIST_SIZE = 20;
    private static final int GRID_SIZE = 52;

    private Icons() {
    }

    /** Lunar resource locations for {list, grid}, or {@code null} when the tile has no usable icon. */
    public static Object[] register(LunarBridge bridge, TileDefinition tile) {
        if (tile.icon == null) {
            return null;
        }
        BufferedImage source;
        try (InputStream stream = tile.icon.openStream()) {
            source = ImageIO.read(stream);
        } catch (Exception exception) {
            Log.error("Could not read icon " + tile.icon, exception);
            return null;
        }
        if (source == null) {
            Log.warn("Icon " + tile.icon + " is not a readable image (use PNG).");
            return null;
        }
        Object list = texture(bridge, tile, fit(source, LIST_SIZE));
        Object grid = texture(bridge, tile, fit(source, GRID_SIZE));
        return list == null || grid == null ? null : new Object[]{list, grid};
    }

    /**
     * Registers the image under a resource location whose path is the image itself as a PNG data
     * URI. Lunar draws icons in two ways: in-game through the texture manager (which finds the
     * texture registered here by that key), and in its web UI, such as the toggle notifications,
     * as {@code <img src=path>}, which can only load our icon when the path carries the image.
     */
    private static Object texture(LunarBridge bridge, TileDefinition tile, BufferedImage image) {
        String path;
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            path = "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
        } catch (Exception exception) {
            Log.warn("Could not encode the icon of tile " + tile.id + " for Lunar's web UI: " + exception);
            path = "tiles/" + tile.lunarId.toLowerCase(Locale.ROOT) + "_" + image.getWidth();
        }
        ResourceLocation location = new ResourceLocation(DOMAIN, path);
        Minecraft.getMinecraft().getTextureManager().loadTexture(location, new DynamicTexture(image));
        // Lunar patches its resource-location interface onto the game's class; if that ever stops
        // holding, fall back to Lunar's own factory (the texture lookup then goes through the same key).
        if (bridge.resourceLocationType().isInstance(location)) {
            return location;
        }
        try {
            return bridge.resourceLocation(DOMAIN, path);
        } catch (Exception exception) {
            Log.error("Could not create a Lunar resource location for the icon of tile " + tile.id, exception);
            return null;
        }
    }

    /**
     * Scales the image to fit a transparent {@code size}x{@code size} square, keeping its aspect
     * ratio. Downscaling halves repeatedly before the final step, which keeps thin details that a
     * single bilinear pass would drop.
     */
    static BufferedImage fit(BufferedImage source, int size) {
        BufferedImage current = toArgb(source);
        int targetWidth = source.getWidth() >= source.getHeight() ? size
                : Math.max(1, Math.round(size * source.getWidth() / (float) source.getHeight()));
        int targetHeight = source.getHeight() >= source.getWidth() ? size
                : Math.max(1, Math.round(size * source.getHeight() / (float) source.getWidth()));
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        if (current.getWidth() != targetWidth || current.getHeight() != targetHeight) {
            current = draw(current, targetWidth, targetHeight);
        }
        BufferedImage square = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = square.createGraphics();
        graphics.drawImage(current, (size - targetWidth) / 2, (size - targetHeight) / 2, null);
        graphics.dispose();
        return square;
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    private static BufferedImage toArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return copy;
    }
}
