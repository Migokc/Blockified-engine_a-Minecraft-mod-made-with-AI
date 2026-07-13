# Blockified engine (fnfmod)

A feature-full Friday Night Funkin' engine inside of Minecraft — **NeoForge 1.21.1**.

Requires: [playerAnimator](https://modrinth.com/mod/playeranimator) (2.0.0+ for 1.21.1) on the client.

## What it does

- **Funkin' Machine block** (Functional Blocks creative tab). Right-click it to open the song menu.
- **Songs come from FNF mods**: drop charts + audio into `config/fnfmod/songs/<song-name>/`.
- **4 keybinds** (default `D F J K`) — rebindable in *Options → Controls → Funkin' Machine*.
- **Multiplayer**: the first player to click the machine picks the song ("Play VS"), the second player to click joins as the opponent side. On servers, only songs installed **on the server** are playable — the server streams the chart + audio to players who don't have them (cached in `config/fnfmod/cache/`).
- **Chart editor**: `/fnf editor [song]` or the button in the song menu. Saves Psych Engine format.
- `/fnf reload` reloads all FNF content without restarting. Targeted forms:
  `/fnf reload songs|skins|splashes|animations|icons|hitsounds|fonts|options|scores`.

## Song folder format

```
config/fnfmod/songs/
  my-song/
    my-song.json            <- legacy FNF or Psych Engine chart ("normal")
    my-song-hard.json       <- extra difficulties (optional)
    Inst.ogg                <- instrumental (required)
    Voices.ogg              <- single vocal track (optional)
    Voices-Player.ogg       <- OR split vocals (optional)
    Voices-Opponent.ogg
```

V-Slice (FNF 0.3+) is also supported — put the pair in the folder instead:

```
  my-song/
    my-song-chart.json
    my-song-metadata.json
    Inst.ogg
    Voices-Player.ogg / Voices-Opponent.ogg (or Voices.ogg)
```

All difficulties inside a V-Slice chart are selectable. Audio must be **OGG** (that's
what FNF mods ship anyway). `inst.ogg` / `voices.ogg` lower-case also works;
vocal files containing `player`/`bf` or `opponent`/`dad` in the name are split stems.

## Complete engine-style mod packs

Existing standalone song folders remain supported. A complete Psych/V-Slice/Codename
style mod can also be installed as one folder under:

```
config/fnfmod/mods/My-Mod/
```

For a Psych-style pack, keep its original layout unchanged:

```
My-Mod/
  pack.json
  data/<song>/<song>.json
  songs/<song>/Inst.ogg
  songs/<song>/Voices.ogg
  scripts/*.lua
  stages/
  custom_events/
  custom_notetypes/
  characters/
  images/
  sounds/
  music/
  fonts/
  weeks/
```

The `mods` folder is created automatically. Songs in `config/fnfmod/songs/` have
priority over installed packs with the same song id; installed packs have priority
over later paths in the Settings **Directories** list. `/fnf reload songs` rescans
both standalone songs and complete packs.

Each external path in **Settings > Directories** has its own checklist for Charts,
Song Audio, Events, Lua, Images, Icons, Characters, and Fonts. Existing paths start
with every category enabled. The choices are stored in
`config/fnfmod/external_folder_filters.json`; disabling Charts or Song Audio removes
that path's songs from the playable library.

Supported chart features: notes, sustains, BPM changes, `mustHitSection`,
`altAnim`, `gfSection`, Psych note types (string or numeric — `Hurt Note` damages you),
scroll speed, embedded events, and separate `events.json` files.

Psych custom note types are loaded from `custom_notetypes/<Note Type>.lua` and
`custom_notetypes/<Note Type>.txt`. Their runtime settings include custom Sparrow
textures and splashes, health gain/loss, hit-causes-miss and ignored notes,
blocked/low-priority notes, note/miss animation flags, rating exclusion, alpha,
scroll-speed multipliers, transform offsets/rotation/scale, hit windows, and
note-specific OGG hitsounds. The directory checklist's **Lua** option controls
custom-note scripts/configs and **Images** controls their visual resources.

## Psych Engine Lua

Psych Engine 1.0.x Lua scripts are discovered in the common Psych locations:

```
scripts/*.lua
stages/<stage>.lua
custom_notetypes/<note type>.lua
custom_events/<event>.lua
data/<song>/*.lua
<song folder>/*.lua
```

`config/fnfmod/scripts/*.lua` contains global scripts that run for every song.
Lua files placed directly in `config/fnfmod/songs/<song folder>/` run only for
that song. Song-local scripts are included when a server transfers the song to
a client.

Charts saved from another directory keep `original_directory.txt` to find the
original audio, unsaved difficulty charts, character definitions, and health
icons. Other images, stages, scripts, custom events, custom note scripts, and
other runtime resources are not inherited from that origin. Put any additional
resources wanted by the edited chart inside its new
`config/fnfmod/songs/<song folder>/` folder instead.

Gameplay callbacks include create/update, countdown/song start, step/beat/section,
note hits/misses, events, pause/resume, song end, and destroy. Supported APIs cover
gameplay properties/groups, score/health, strum transforms, timers, tweens, input,
variables, random/string/color and controlled file helpers, event/camera calls,
Lua text, static PNG sprites, and animated Sparrow PNG/XML sprites from `images/`.

Animated sprites use the same common calls as Psych Engine. Keep the PNG and XML
beside each other with the same base filename:

```lua
function onCreate()
    makeAnimatedLuaSprite('character', 'characters/MyCharacter', 100, 100)
    addAnimationByPrefix('character', 'idle', 'Idle', 24, true)
    addAnimationByIndices('character', 'blink', 'Blink', '0,1,2,1,0', 24, false)
    addOffset('character', 'idle', 4, 8)
    addLuaSprite('character', false)
    playAnim('character', 'idle', true)
end
```

`addAnimation`, `addAnimationByPrefix`, `addAnimationByIndices`, `playAnim`,
`addOffset`, and their legacy `luaSprite...` aliases are supported. Sparrow frame
trimming, rotated atlas frames, frame rate, looping, reverse playback, and start
frames are preserved. Animated frames keep one atlas-wide scale, so differing
Sparrow trim boxes do not make sprites wobble or resize between frames.

In the chart editor's **File** menu, **Import Complete Song** first saves the current
chart as the active local override. It then copies the song audio, every other
difficulty chart, and song icon into `config/fnfmod/songs/<song>/`. It does not
copy custom-note scripts or their related resources. Imported local audio, charts,
and icons take priority over the source directory.

Press **Enter** to playtest from the beginning, or **Shift+Enter** to playtest from
the conductor's current time. Mid-song playtests reconstruct the event and camera
state up to that point; F12 remains the quick preview shortcut.

Lua runs client-side in a sandbox. Direct Java access, process execution, and
unrestricted filesystem access are disabled. Psych features that require its actual
HaxeFlixel runtime cannot exist unchanged in Minecraft: HScript/Haxe reflection,
Flixel shaders, video/dialogue, custom substates, Psych sound objects, and
FlxAnimate texture atlases currently return safely without crashing. Sparrow XML
animation is supported.

### Lua custom fonts

`setTextFont(tag, "FontName.ttf")` supports multiple TTF and OTF files. Fonts are
searched in this order:

```
config/fnfmod/songs/<song>/fonts/
config/fnfmod/mods/<pack>/fonts/
config/fnfmod/fonts/                 <- global fonts
```

Pack/song fonts are transferred with songs in multiplayer. Each Lua text object
can select a different font. Missing or invalid files fall back to Minecraft's
default font and are reported in the log. `setTextWidth` also wraps Lua text at
the requested width. Custom fonts use 8x glyph oversampling so large FNF text
has more raster detail while retaining Minecraft's normal texture filtering.
Lua sprites and text use Psych Engine's fixed 1280x720 canvas. The complete
canvas scales uniformly and stays centered when the window resolution or
Minecraft GUI scale changes, so scripted positions remain stable.
Lua `strumLineNotes`, `playerStrums`, and `opponentStrums` X/Y values use that
same 1280x720 coordinate space; per-strum `downScroll` is also supported.
Lua objects on the `game` camera render behind native notes and receptors;
objects on the `hud` camera render above the built-in HUD. `setObjectOrder`
changes order only among Lua objects on the same camera.

### Lua world camera

Static and animated Lua sprites can be placed in the Minecraft level by assigning
the new `world` camera. The Funkin' Machine/speakers center is `(0, 0, 0)`;
64 Lua pixels equal one Minecraft block. `x` points to stage-right, `y` points
down like normal Psych sprite coordinates, and `z` points toward the stage camera.
Normal property changes, `doTweenX`, `doTweenY`, `doTweenZ`, scale, angle, alpha,
color, animation, offsets, and `setObjectOrder` continue to work.

```lua
function onCreate()
    makeAnimatedLuaSprite('worldSign', 'signs/animated', 0, -96)
    addAnimationByPrefix('worldSign', 'idle', 'idle', 24, true)
    setObjectCamera('worldSign', 'world')
    setProperty('worldSign.z', 32)
    setProperty('worldSign.billboard', true)
    setProperty('worldSign.lighting', false)
    addLuaSprite('worldSign', false)
    doTweenZ('bringSignForward', 'worldSign', 64, 1.0, 'quadOut')
end
```

World sprites billboard toward the camera by default, like vanilla name tags.
Use `setWorldSpriteBillboard(tag, false)` or set `tag.billboard` to `false` to
lock the sprite to the stage direction. They use Minecraft world lighting by
default; use `setWorldSpriteLighting(tag, false)`, `setWorldSpriteShadows(tag,
false)`, or set `tag.lighting`/`tag.shadows` to `false` for a full-bright sprite.

## Note skins (Sparrow XML!)

Drop a Friday Night Funkin' spritesheet straight from any FNF mod into:

```
config/fnfmod/skins/<skin-name>/NOTE_assets.png
config/fnfmod/skins/<skin-name>/NOTE_assets.xml
```

Each subfolder is a selectable skin — pick one in Settings → Visuals and UI.

The Adobe Animate / Sparrow `<TextureAtlas><SubTexture .../>` XML is parsed,
including `frameX/frameY` trim offsets. Base-game and Psych naming conventions
are recognized (`purple0000`, `arrowLEFT`, `left confirm`, `purple hold piece`, ...).
Without a skin, built-in procedurally drawn arrows are used.

Optional `skin.json` values can adjust each part's scale, opacity, and position.
Position values are offsets in the part's source-art pixels. They scale with the
part, resolution, and vanilla GUI scale, preserving the same relative alignment.
Negative X moves left and negative Y moves up.

```json
{
  "noteScale": 1.0,
  "noteAlpha": 1.0,
  "noteX": 0,
  "noteY": 0,

  "receptorScale": 1.0,
  "receptorAlpha": 1.0,
  "receptorX": 0,
  "receptorY": 0,

  "holdWidthScale": 1.0,
  "sustainAlpha": 1.0,
  "sustainX": 0,
  "sustainY": 0,

  "splashScale": 1.0,
  "splashAlpha": 1.0,
  "splashX": 0,
  "splashY": 0,

  "holdCoverScale": 1.0,
  "holdCoverAlpha": 1.0,
  "holdCoverX": 0,
  "holdCoverY": 0
}
```

## Character animations (playerAnimator) + animation sets

Put Emotecraft/Blockbench-exported animation `.json` files into
`config/fnfmod/animations/`. Actions: `idle, idle2, left, down, up, right, miss`.
`idle2` is optional — when present, idle and idle2 alternate every beat
(FNF danceLeft/danceRight; those two names also work as aliases).

**Animation sets:** each subfolder of `animations/` is a selectable character.
Pick yours with the **"Anims:"** button in the Funkin' Machine menu — in VS mode
each player uses their own set, and your partner sees it too (if they have the
same set installed). Loose files in `animations/` itself form the `default` set.

A set can include a `character.json` that also defines its **camera centers**:

```json
{
  "pos": "<forward:0,left:0,up:0>",
  "rotation": 0,
  "cameraOffset": [0.0, 0.5],
  "animations": {
    "idle":  "my_idle",
    "left":  { "anim": "my_left",  "cameraOffset": [-1.0, 0.0] },
    "down":  { "anim": "my_down",  "cameraOffset": [0.0, -1.0] },
    "up":    { "anim": "my_up",    "cameraOffset": [0.0,  1.0] },
    "right": { "anim": "my_right", "cameraOffset": [1.0,  0.0] },
    "miss":  "my_miss"
  }
}
```

`pos` offsets the mod's normal stage position using the same camera-relative syntax as
Minecraft Command events. Directions can be combined, for example
`"<forward:2,left:0.5,up:1>"`. `rotation` is a degree offset added to the normal
character rotation, so `0` preserves the default and negative values rotate the other way.
It rotates only the character; the gameplay camera keeps the stage's normal direction.
Command events can use `<character_rotation:degrees>` for the same additive angle,
for example `execute as <player> at @s run tp @s ~ ~ ~ <character_rotation:90>`.

`cameraOffset` values are in blocks: x = screen right, y = screen up. The top-level
one is the character's camera center; per-animation ones nudge the camera while
that animation plays (like FNF's sing offsets). Without `character.json`,
animations named `fnf_idle`, `fnf_left`, ... (or just `idle`, `left`, ...) are
picked up automatically. The legacy role-based `mapping.json`
(`"player"`/`"opponent"`) still works for the default set.

Animations play on the actual player entities during gameplay (both players in VS mode).

## Camera

During a song the camera follows the focused character in full 3D while retaining
FNF-style screen-space animation nudges. Focus follows the chart:
`mustHitSection` = camera on the player side,
otherwise the opponent side (in solo the machine block stands in for the opponent).
Each focus change eases with the curve set per section in the chart editor
(**Cam Ease**: smooth / expo / linear / snap — stored as `fnfmodCamEase` in the
chart json, which other engines ignore). First-person view is switched to
third-person for the song and restored afterwards.

## Chart editor

`/fnf editor [song]` — Psych-style grid, left 4 columns = opponent, right 4 = player.

- Click: place/remove note. Right-click: remove. Shift+click: select.
- `E` / `Q`: lengthen / shorten selected note's sustain. `Del`: delete selected.
- `A` / `D` (or arrows / mouse wheel): previous / next section. `Space`: play/pause audio.
- Per-section: Must Hit, Alt Anim, GF Section, Change BPM (+ value), section beats,
  copy/paste/clear/swap section. Note types via the Type field.
- Events can use **Trigger: Timeline** or **Trigger: Before Song**. Before-song events
  run once after gameplay and command targets load, while the countdown is still active.
- Event `+`/`-` buttons add or remove events at one timestamp. `<`/`>` select which
  stacked event is being edited. Saves use Psych's grouped event-point format.
- The event dropdown includes `.lua`/`.txt` definitions from the active pack's
  `custom_events/` folder and imported event names already present in the chart.
  Scroll the dropdown when the list is taller than the current GUI resolution.
- **Camera Zoom** events use Value 1 as a persistent zoom offset (`0` normal,
  positive in, negative out) and Value 2 as a 500 ms easing preset.
- **Camera Focus** events override Must Hit focus using Value 1 (`player` or
  `opponent`) and Value 2 easing. A later Camera Focus with both values empty
  restores normal Must Hit section tracking.
- Snap: 4th–64th. Save writes `config/fnfmod/songs/<file>/<file>.json` (Psych format).

## Gameplay options

In the song select screen: **Play as** (Player / Opponent / Both — solo only;
Both puts you center stage playing every note, and one keypress hits overlapping
notes on both strumlines), Downscroll, Ghost Tapping. More in
`config/fnfmod/options.json` (`offsetMs` for audio calibration, `scrollSpeedMult`).

## Building

```
gradlew build
```

Output jar: `build/libs/Blockified-engine-NeoForge-<minecraft-version>-<mod-version>.jar`.

## Source extension points

The source keeps reusable behavior outside GUI screens where possible:

- `chart/ChartEventTypes.java` is the canonical list of engine-owned events and
  their editor defaults. Add a built-in event there, then implement its playback
  behavior in `client/gameplay/GameplayEventDispatcher.java`; custom Lua event
  names remain data-driven.
- `song/SongImportService.java` owns complete-song importing and can
  be reused by another screen or command without constructing the chart editor.
- `song/ExternalDirectoryConfig.java` owns the ordered directory list and its
  per-directory resource filters.
- `song/SongCache.java` owns downloaded-song cache size, pruning, touching, and
  deletion. `SongLibrary` retains compatibility methods that delegate to it.
- `client/render/NoteSkinConfig.java` is the immutable, validated representation
  of `skin.json`; `NoteStyle` only consumes the resulting values.
- `client/render/LuaWorldSpriteRenderer.java` owns Minecraft world rendering for
  Lua sprites. `PsychLuaRuntime` supplies immutable snapshots rather than exposing
  live script objects to the renderer.

These classes are intended as stable starting points for contributors. Keep file
I/O, parsing, and rendering out of screens when adding comparable features.
