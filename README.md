# Blockified engine (fnfmod)

A feature-full Friday Night Funkin' engine inside of Minecraft — **NeoForge 1.21.1**.

Requires: [playerAnimator](https://modrinth.com/mod/playeranimator) (2.0.0+ for 1.21.1) on the client.

## What it does

- **Funkin' Machine block** (Functional Blocks creative tab). Right-click it to open the song menu.
- **Lightweight songs or complete mods**: use `config/fnfmod/songs/<song-name>/`
  for basic chart/audio entries, or `config/fnfmod/mods/<mod>/` for full creations.
- **4 keybinds** (default `D F J K`) — rebindable in *Options → Controls → Funkin' Machine*.
- **Multiplayer**: the first player to click the machine picks the song ("Play VS"), the second player to click joins as the opponent side. On servers, only songs installed **on the server** are playable — the server streams the chart + audio to players who don't have them (cached in `config/fnfmod/cache/`).
- **Chart editor**: `/fnf editor [song]` or the button in the song menu. Saves Psych Engine format.
- `/fnf reload` reloads all FNF content without restarting. Targeted forms:
  `/fnf reload songs|skins|splashes|animations|icons|hitsounds|fonts|options|scores`.

After selecting a song, the **Look** button chooses its presentation profile:

- **FNF** uses a fixed 1280x720 game canvas, Psych character JSON/Sparrow
  animations, Lua stage foreground/background insertion, the FNF HUD, Psych-style
  section focus, camera follow, separate game/HUD zoom, and measure bumps. Psych
  stage JSON positions and camera offsets are applied to boyfriend, opponent, and
  girlfriend/speakers; the chart's `gfVersion` selects the third performer. Static
  stage JSON objects and stage Lua from Psych's `stages/`, global `scripts/`, and
  song data/audio folders are loaded in stage order.
- **Minecraft** uses the Minecraft presentation. Complete packs installed in
  `config/fnfmod/mods/` may use their own rich resources. Packs selected through
  external **Directories** remain restricted to charts, audio, events, and Lua/config
  code in this mode. Lightweight `config/fnfmod/songs/` entries never expose rich
  resources in any mode.
- **Legacy** preserves the original Blockified Engine presentation and loading.

The host's choice is authoritative in multiplayer and is sent with the song manifest.
Downloaded cache entries are isolated by exact song id, difficulty, presentation mode,
and asset policy, so files left by another chart or mode cannot affect chart selection.

Psych resource lookup uses the active song/mod first. If a character, image, stage,
font, icon, or auxiliary sound is missing, the first path in **Directories** is used
as the one shared-engine fallback. Lower paths are never searched for fallback assets,
preventing unrelated mods from supplying a same-named file accidentally.

## Lightweight song folder format

`config/fnfmod/songs/<song>/` deliberately supports only OGG audio, chart JSON,
`events.json`, and V-Slice metadata JSON. Lua, images, icons, character definitions,
animations, custom events/notes, fonts, stages, and other assets placed here are
ignored. Use a complete pack under `mods/` for those features.

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

Psych chart-level `arrowSkin` and `splashSkin` are supported. Their matching
PNG/XML Sparrow atlases are resolved from a complete active pack's `images/` folder.
They affect receptors, note heads, sustains, and hit splashes. The chart editor's
Data tab exposes these as **Note Texture** and **Note Splash Texture** and previews
the selected note atlas on its grid.

## Complete engine-style mod packs

Complete Psych/V-Slice/Codename/Blockified packs are installed as one folder under:

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
  animations/              <- Blockified playerAnimator definitions
  characters/
  images/
  sounds/
  music/
  fonts/
  weeks/
```

The `mods` folder is created automatically. Rich resources—including Lua, custom
events and notes, stages, images, sounds, fonts, characters, and animations—are
loaded only from complete packs (plus explicitly global config folders). Songs in
`config/fnfmod/songs/` have priority over installed packs with the same song id;
installed packs have priority
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

`config/fnfmod/scripts/*.lua` contains global scripts that run with complete mod
packs. Pack-scoped song scripts belong in `mods/<pack>/data/<song>/` or
`mods/<pack>/songs/<song>/`. Lightweight `songs/<song>` entries do not run Lua.

Charts saved from another directory keep `original_directory.txt` only to find
the original audio and unsaved difficulty charts. Events and every rich resource
must be present locally in a complete `config/fnfmod/mods/<pack>/` pack.

Gameplay callbacks include create/update, countdown/song start, step/beat/section,
note hits/misses, events, pause/resume, song end, and destroy. Supported APIs cover
gameplay properties/groups, score/health, strum transforms, timers, tweens, input,
variables, random/string/color and controlled file helpers, event/camera calls,
Lua text, static PNG sprites, and animated Sparrow PNG/XML sprites from `images/`.
In the FNF HUD style, Psych's native object names are scriptable: `healthBar`,
`healthBarBG`, and `scoreTxt` expose position, dimensions, alpha, visibility,
colors, score text, and score-text transforms. `healthBar.percent` reads 0–100
and can also set Blockified health; `score`/`songScore` expose the read-only numeric
score. `setScore`, `addScore`, and writes to those properties are safe no-ops.
`setHealthBarColors(left, right)` is supported.
Lua tweens support `Sine`, `Cubic`/`Cube`, `Quint`, `Circ`, `Elastic`, `Quad`,
`Quart`, `Expo`, `Back`, and `Bounce`, each with `In`, `Out`, and `InOut`
variants (for example `sineIn`, `bounceOut`, or `elasticInOut`). Names are
case-insensitive; `linear` remains the default.
Psych `playSound`, `playMusic`, tagged sound controls, sound properties, precaching,
looping, and `onSoundFinished` load OGG files from `sounds/` or `music/`.
FNF-mode characters honor Psych `sing_duration`, dance-left/right beat frequency,
animation offsets, `-loop` animations, miss/special completion, `Hey!` notes, and
the common `characterPlayAnim`, `characterDance`, `getCharacterX/Y`, and
`setCharacterX/Y` Lua calls. Character aliases `boyfriend`/`bf`, `dad`, and
`gf`/`girlfriend`/`speakers` are accepted by character properties and animation calls.
Automatic beat impulses can be controlled without snapping an active bop using
`setCameraBopEnabled('game'|'hud'|'both', enabled)` (alias
`setDefaultCameraBop`) or `camGame.bopEnabled` / `camHUD.bopEnabled`.
`setProperty('character.antialiasing', boolean)` and
`setObjectAntialiasing(tag, boolean)` affect character and Lua sprite filtering.

Blockified Lua can run Minecraft commands with the same placeholders and
camera-relative expressions used by chart command events:

```lua
runMinecraftCommand('tp <player> <forward:2,left:1>', 'player')
runMinecraftCommand('execute at <opponent> run summon minecraft:pig <right:2>', 'server')
```

`runCommand` is a short alias. Runner defaults to `player`. `player` sends the
command with normal player permissions. `server` runs at the Funkin' Machine;
it is host-only, runs once in duet, and requires operator permission on dedicated
servers. Integrated-world owner is trusted. `<player>`, `<opponent>`, `<speakers>`,
`<forward>`, `<backward>`, `<left>`, `<right>`, `<up>`, `<down>`, combined offsets,
`<camera_rotation>`, and `<character_rotation:degrees>` are supported.

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
Lua object alpha is continuous from `0` through `1`, including generated graphics,
text, static sprites, animated sprites, and the scriptable FNF HUD objects.
`makeGraphic` accepts `#RRGGBB`, `0xRRGGBB`, `AARRGGBB`, and basic named colors:
red, green, blue, cyan, magenta, yellow, black, white, gray/grey, orange, purple,
pink, brown, lime, navy, and teal.

In the chart editor's **File** menu, enter a mod folder name and use **Import Song
to Mod**. It creates `config/fnfmod/mods/<name>/` with a Psych-style template plus
Blockified's `animations/` folder. Only OGG stems, charts, `events.json`, and
metadata JSON are imported. The edited chart and other known difficulties are
written as playable Psych JSON; icons, images, scripts, custom note/event files,
characters, stages, and other resources are never copied.

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
The FNF HUD also stays on that fixed canvas. Default, Abbreviated, Numbers, and
Vanilla HUD styles instead use Minecraft GUI coordinates, so their size and
placement respond normally to window resolution and vanilla GUI scale.
Vanilla style uses Minecraft's actual player-health and food GUI layers. During
the song, the server pins real health to Blockified health and real food to
Blockified accuracy after every tick, then restores health, food, saturation,
and exhaustion when the session closes. Natural regeneration and hunger loss
therefore cannot make the displayed values drift during gameplay.
Lua objects on the `game` camera render behind the native note/HUD camera. The
`hud`/`camHUD` camera shares an ordered stack with native gameplay layers, so
`setObjectOrder` can place a Lua object below, between, or above them. Native
anchors are receptors `100`, notes `200`, hit effects `300`, and HUD `400`;
new Lua objects default to `1000` (top), matching Psych's append behavior.
`other`/`camOther` remains a separate top camera.

### Lua world camera

Static and animated Lua sprites, graphics, and text can be placed in the Minecraft
level by assigning the `world` camera. The Funkin' Machine/speakers center is `(0, 0, 0)`;
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

World text uses Psych Engine's normal `makeLuaText(tag, text, width, x, y)`
signature and also accepts an optional sixth `z` coordinate:

```lua
makeLuaText('worldLabel', 'Hello!', 240, 0, -96, 32)
setObjectCamera('worldLabel', 'world')
setTextSize('worldLabel', 24)
addLuaText('worldLabel')
```

The position is the text block's center in world-camera pixels. `width` wraps
the text; use `0` for no width limit. `setTextFont`, `setTextString`, text color,
alpha, angle, scale, `setObjectOrder`, `doTweenX/Y/Z`, billboard mode, and world
lighting work the same way as they do for world sprites.

World objects also support local three-axis rotation. X is pitch, Y is yaw, and
Z is roll. Psych's existing `angle` and `doTweenAngle` remain aliases for Z:

```lua
setObjectRotation('worldSign', 25, 45, 0)
setProperty('worldSign.rotation.x', 25)
setProperty('worldSign.rotation.y', 45)
setProperty('worldSign.rotation.z', 0)

doTweenAngleX('pitchSign', 'worldSign', 360, 2, 'sineInOut')
doTweenAngleY('yawSign', 'worldSign', 360, 2, 'linear')
doTweenAngleZ('rollSign', 'worldSign', 360, 2, 'linear')
```

`rotationX`/`angleX`, `rotationY`/`angleY`, and `rotationZ`/`angleZ` are property
aliases. `doTweenRotationX/Y/Z` are aliases for the three new tween functions.
Rotation is applied after billboard or fixed-stage orientation, so these values
act as local offsets instead of replacing the object's camera-facing behavior.

World objects billboard toward the camera by default, like vanilla name tags.
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

The selector also has two built-in choices:

- **Default (chart)** uses the active chart's `arrowSkin` and `splashSkin` from
  its complete mod pack. Missing chart assets fall back to procedural notes.
- **None (procedural)** ignores chart note/splash textures and always uses
  Blockified Engine's generated arrows.

The Adobe Animate / Sparrow `<TextureAtlas><SubTexture .../>` XML is parsed,
including `frameX/frameY` trim offsets. Base-game and Psych naming conventions
are recognized (`purple0000`, `arrowLEFT`, `left confirm`, `purple hold piece`, ...).
Named skin folders override the chart's default note and splash textures.

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

The animation setting has two built-in choices:

- **None** disables playerAnimator character animations.
- **Default (song)** uses the active complete mod's animation files. If the mod
  does not define any, loose files in `config/fnfmod/animations/` are the fallback.

Pack-local Emotecraft/Blockbench animation `.json` files can be placed directly
in `config/fnfmod/mods/<pack>/`, or in its `animations/` folder. Actions are
`idle, idle2, left, down, up, right, miss`.
`idle2` is optional — when present, idle and idle2 alternate every beat
(FNF danceLeft/danceRight; those two names also work as aliases).

**Animation sets:** each subfolder of global `config/fnfmod/animations/` is a
selectable character. A complete pack can provide named character sets in either
`mods/<pack>/animations/<character>/` or `mods/<pack>/characters/<character>/`.
The former has priority when both exist. These names are also used by the
**Change Character** event, which switches the playerAnimator set and icon.
Pick yours with the **"Anims:"** button in the Funkin' Machine menu — in VS mode
each player uses their own set, and your partner sees it too (if they have the
same set installed). Selecting **None** remains an explicit opt-out, so Change
Character does not enable playerAnimator after the user disables it.

A set can include a Blockified `character.json` that defines its icon, additive
rotation, animation mappings, and camera centers:

```json
{
  "icon": "bf",
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

To give the opponent different data, put `character-opp.json` beside
`character.json` and use the same schema. Its animation mappings, icon,
`rotation`, and camera offsets override only the opponent side. Missing fields
fall back to `character.json`. Opponent animations are no longer automatically
mirrored.

`icon` is the icon key without `icon-` or `.png`. For example, `"icon": "bf"`
uses `icon-bf.png` from the song/mod icon folders or from
`config/fnfmod/icons/`. It is applied when that animation set is active and also
when Change Character selects the set.

`rotation` is a degree offset added to the normal character rotation, so `0`
preserves the default and negative values rotate the other way.
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
Legacy and Minecraft modes use the **Camera Behavior** event to change Must-Hit
camera movement. Value 1 is a speed multiplier; Value 2 is `smooth`, `expo`,
`linear`, or `constant`. Constant snaps directly to the current target. An event
with both values empty restores normal speed and smooth easing. This event does
not alter FNF mode. The old per-section `fnfmodCamEase` extension is no longer
read or written. First-person view is switched to third-person for the song and
restored afterwards.

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
- **Camera Behavior** events change Legacy/Minecraft Must-Hit camera speed and
  easing. Leave both values empty to reset the behavior.
- Psych built-ins are available in the dropdown: **Hey!**, **Set GF Speed**,
  **Add Camera Zoom**, **Play Animation**, **Camera Follow Pos**,
  **Alt Idle Animation**, **Screen Shake**, **Change Character**,
  **Change Scroll Speed**, **Set Property**, and **Play Sound**. `Hey!` and
  `Set GF Speed` are FNF-profile-only, matching Psych. Other events use Psych
  semantics in FNF mode and Minecraft-aware camera/body variants in Minecraft
  and Legacy modes. **Camera Zoom** remains Blockified Engine's persistent,
  eased zoom; **Add Camera Zoom** is Psych's temporary game/HUD impulse.
- Snap: 4th–64th. Save writes `config/fnfmod/songs/<file>/<file>.json` (Psych format).

## Gameplay options

In the song select screen: **Play as** (Player / Opponent / Both — solo only;
Both puts you center stage playing every note, and one keypress hits overlapping
notes on both strumlines), Downscroll, Ghost Tapping. More in
`config/fnfmod/options.json` (`offsetMs` for audio calibration, `scrollSpeedMult`).
Player and opponent icon choices apply globally to every song. **Default (song)**
uses each chart character's health icon; **None** hides that side's icon.

## Building

```
gradlew build
```

Output jar: `build/libs/Blockified-engine-NeoForge-<minecraft-version>-<mod-version>.jar`.

## Source extension points

The source keeps reusable behavior outside GUI screens where possible:

- `chart/ChartEventTypes.java` is the canonical list of engine-owned events and
  their editor defaults. Psych event parsing lives in
  `client/gameplay/PsychBuiltinEventHandler.java`; Minecraft Command and
  Blockified-specific camera events live in `GameplayEventDispatcher.java`.
  Custom Lua event names remain data-driven.
- `song/SongImportService.java` owns basic-file importing and can be reused by
  another screen or command. `song/ModTemplateService.java` owns the generated
  complete-pack folder layout.
- `song/ExternalDirectoryConfig.java` owns the ordered directory list and its
  per-directory resource filters.
- `song/SongCache.java` owns downloaded-song cache size, pruning, touching, and
  deletion. `SongLibrary` retains compatibility methods that delegate to it.
- `client/render/NoteSkinConfig.java` is the immutable, validated representation
  of `skin.json`; `NoteStyle` only consumes the resulting values.
- `client/render/PsychHudState.java` owns mutable Psych-compatible native HUD
  properties; `client/lua/PsychColor.java` owns shared Lua/HUD color parsing.
- `client/render/LuaWorldObjectRenderer.java` owns common world positioning and
  ordering. Immutable records in `LuaWorldObject.java` are delegated to the
  separate sprite and text renderers, so another world-object type can be added
  without exposing live script objects or growing `PsychLuaRuntime`.
- `client/gameplay/PsychAssetResolver.java` owns ordered Psych asset lookup;
  `client/audio/PsychSoundPlayer.java` owns per-song Lua/event sound lifetime.

These classes are intended as stable starting points for contributors. Keep file
I/O, parsing, and rendering out of screens when adding comparable features.
