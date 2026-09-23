# Lunar Mod API for Weave

A Weave library mod (1.8.9, Lunar Client) that lets other Weave mods add their own tiles to
Lunar's mod menu. Each tile gets a custom icon, the "Original Authors" line, an on/off switch,
Lunar's toggle keybind and a settings panel built from Lunar's own controls. Settings are saved in
the Weave folder.

A drop-in library jar for stock Weave 1.x: put `lunar-mod-api-1.0.0.jar` in
`~/.weave/mods/1.8.9` next to the mods that use it. It only uses Weave's `ModInitializer`, so it
does not need a patched loader. It only does anything on Lunar Client.

The `lunar-specific` branch is the earlier version, built for the Lunar fork of Weave
([adoringlol/Weave-Loader](https://github.com/adoringlol/Weave-Loader)).

## Declaring tiles: `lcmods.json`

Put an `lcmods.json` at the root of your mod jar (`src/main/resources/lcmods.json`). One jar can
declare several tiles:

```json
{
    "mods": [
        {
            "id": "my_tile",
            "name": "My Tile",
            "description": "What the tile does.",
            "icon": "assets/mymod/icon.png",
            "creators": ["You", "Someone Else"],
            "categories": ["hud"],
            "enabledByDefault": false
        }
    ]
}
```

| Field | Required | Notes |
|---|---|---|
| `id` | yes | Letters, digits, `_` and `-`, up to 48 characters. Must be unique across all installed mods. |
| `name` | yes | The name shown on the tile. |
| `description` | no | Shown in the settings panel. |
| `icon` | no | Path inside your jar. PNG, any size (square works best); it is scaled to 52x52 for the grid and 20x20 for the list. |
| `creators` | no | Shown as Lunar's "Original Authors" line and searchable in the mod menu. Lunar only draws this line when `description` is not empty. |
| `categories` | no | Any of `new`, `hud`, `server`, `mechanic`. |
| `enabledByDefault` | no | Defaults to `false`. |

## Claiming tiles and adding settings

Declaring a tile is not enough: your code must claim every tile it implements, even when the jar
has only one. Unclaimed tiles are not added to the menu. Claim tiles and add settings from your
mod's `init()` so they exist before Lunar builds its mod list.

```java
public final class MyMod implements ModInitializer {
    @Override
    public void preInit(Instrumentation instrumentation) {
    }

    @Override
    public void init() {
        LunarMod tile = LunarMods.mod("my_tile");

        tile.header("General");
        Toggle sound = tile.toggle("sound", "Play Sound", true);
        Slider<Float> volume = tile.slider("volume", "Volume", 0.5f, 0f, 1f).visibleWhen(sound::get);
        ColorPicker color = tile.color("color", "Color", 0xFFFF5555);
        Keybind key = tile.keybind("trigger", "Trigger", Keyboard.KEY_NONE).onPress(() -> doThing());

        tile.onToggle(enabled -> System.out.println("My Tile is now " + (enabled ? "on" : "off")));
    }
}
```

Add `"lunar-mod-api"` to `dependencies` in your `weave.mod.json`, and compile against
`lunar-mod-api-1.0.0.jar`. Implement both `preInit` and `init`, even if `preInit` is empty: some
Weave builds (such as 1.0.0-b.2) declare both methods without default bodies, and a mod that
leaves one out fails to load there.

Setting ids are the keys your values are saved under, so keep them stable between versions.

## Settings

| Method | Returns | Lunar control |
|---|---|---|
| `toggle(id, name, default)` | `Toggle` (`Boolean`) | Checkbox / switch |
| `slider(id, name, default, min, max)` | `Slider<Integer>`, `Slider<Float>` or `Slider<Double>` (by argument types) | Slider |
| `color(id, name, argb)` | `ColorPicker` (`Integer`, `0xAARRGGBB`) | Color picker with opacity and chroma |
| `text(id, name, default)` | `TextInput` (`String`) | Text field |
| `dropdown(id, name, default, choices...)` | `Dropdown` (`String`) | Dropdown |
| `choice(id, name, enumDefault)` | `Choice<E>` | Dropdown of an enum's constants, labeled by `toString()` |
| `keybind(id, name, lwjglKey)` | `Keybind` (`Integer`) | Key recorder, the same one Lunar uses for its own keybinds |
| `button(id, name, action)` | `Button` | Button |
| `header(label)` | `Header` | Section heading |

Every setting has `get()`, `set(value)`, `reset()`, `defaultValue()`, `onChange(listener)` and
`visibleWhen(condition)`. `visibleWhen` shows the row only while the condition is true; Lunar
re-checks it while the panel is open. `Keybind` adds `onPress(action)`, `isDown()` and `keyName()`. Key codes
are LWJGL codes (`Keyboard.KEY_*`); mouse buttons are `button - 100`, and `Keyboard.KEY_NONE`
means unbound.

Lunar's full list of control types is: button, range, slider, checkbox, toggle, date, password,
radio, text, text area, players, color picker, dropdown, keybind, multi number, modifiable list,
packs, inventory slots, category, and three crosshair editors. This library exposes every control
a generic mod can use. Radio lists need Lunar's own enum interface, so enums use a dropdown
instead. The crosshair, pack and inventory-slot editors are tied to Lunar's own mods. Range, date,
password, text area and toggle have no option builder in the current Lunar build.

## HUD elements

A tile can have one element in Lunar's HUD editor, which players drag, snap and resize like
Lunar's own HUD mods. It belongs to the tile's on/off switch:

- It is drawn, and listed in the HUD editor, only while the tile is on.
- The editor's X (remove) button switches the tile off.
- The editor's settings button opens the tile's settings.
- Its position, anchor and size are saved with the tile's other settings.

There are two kinds. A text element uses Lunar's own text style, as in its FPS and CPS mods.
Lunar also adds its text color, shadow, brackets, background and border settings to the tile:

```java
tile.textHud(editing -> "Kills: " + kills).position(HudAnchor.TOP_LEFT, 2, 60);
```

Return `null` or an empty string to hide it. In the HUD editor, a hidden text element shows the
tile name so it can still be placed. The element's box always covers its text. Lunar's own text
elements size the box from the background width and height settings, so long text can spill out.
Here those settings are only a minimum, and the box grows to fit the text.

A custom element draws itself with the usual Minecraft calls:

```java
tile.hud(new HudRenderer() {
    public float width(boolean editing) { return 60; }
    public float height(boolean editing) { return 12; }
    public void render(float x, float y, boolean editing) {
        Gui.drawRect((int) x, (int) y, (int) x + 60, (int) y + 12, 0x80000000);
    }
});
```

Sizes are GUI pixels at scale 1. Lunar has already applied the element's position and size to the
GL matrix, so draw inside the box that starts at (`x`, `y`). The HUD editor outlines, drags and
snaps by this box, so report a size that covers everything you draw. That includes a text
shadow, which adds one pixel right and down. A width or height of 0 hides a custom
element. `editing` is true while the HUD editor is open. `position(anchor, x, y)` sets the
starting spot before the player moves it. HUD elements need Lunar; outside Lunar they are not
drawn.

## Behavior

- **Toggle keybind.** Lunar adds its toggle keybind to every tile automatically, like its own
  mods. It is the key button in the tile's settings header. It starts unbound, and it switches
  the tile on and off with Lunar's usual notification, which shows the tile's icon.
- **Storage.** Everything a tile saves (on/off state, every setting, the toggle keybind) is
  stored in `~/.weave/mod-data/1.8.9/lunar-mod-api/settings.json`, not in Lunar's profile. The
  file is written a few seconds after a change and when the game closes. Tiles of mods that are
  not installed right now are kept in the file.
- **`onToggle`** runs whenever the tile is switched on or off. It also runs once with `true` when a
  tile starts enabled, so a mod can do its setup there.
- **`Keybind.onPress`** runs only while the tile is enabled and no screen is open.
- **Outside Lunar**, tiles are not shown, and settings keep their
  defaults (or whatever the mod `set()`).

## How Lunar is found

Lunar is obfuscated and renames its classes on every update, so the library finds its mod system
by structure at runtime: the mod manager is identified by its `mods.json` file name, and each
setting control by the UI type its builder reports. The result is cached per Lunar build in
`~/.weave/cache/lunar/lunar-mod-api/<build>.properties`. The cache is keyed by a hash of Lunar's
build data and checks a fingerprint of every class involved.

- **After a Lunar update**, the lookup runs once on a background thread (about 0.3 s) and tiles
  appear a moment after the title screen.
- **On every other launch**, the cached members are restored directly.
- **To force a fresh lookup**, start the game with `-Dweave.lunar.cache=false`.

## Building

```powershell
.\build.ps1
```

Compiles with `javac --release 8`. No Weave jar is needed: Weave's `ModInitializer` is compiled
from a stub in `src/stubs` that is left out of the jar. The script needs:

| Parameter | Default |
|---|---|
| `-MinecraftJar` | Minecraft 1.8.9 with MCP names, from a Weave-Loader checkout next to this folder (`..\Weave-Loader\api\v1_8\.gradle\weave\client-mcp-named.jar`) |
| `-GsonJar`, `-GuavaJar`, `-LwjglJar` | Found in the Gradle cache; Guava falls back to Minecraft's own libraries |
| `-ModsDir` | `~/.weave/mods/1.8.9`; the built jar is copied there when the folder exists |

The script writes `build\lunar-mod-api-1.0.0.jar`.
