# Blockified engine (fnfmod)

A feature-full Friday Night Funkin' engine inside of Minecraft — **NeoForge 1.21.1**.

Current release: **2.2.0bbs**.

Documentation: **[Blockified Engine Docs](https://migokc.github.io/Blockified-engine_a-Minecraft-mod-made-with-AI/)**.

### 2.2.0bbs highlights

- Global content folders now live inside `config/fnfmod/mods/` — `mods/animations`,
  `mods/scripts`, `mods/fonts`, and health icons at `mods/images/icons` — so the
  `mods/` folder is both the naked global mod and the named-pack container, matching
  Psych's layout. Existing installs auto-migrate on first launch; `songs`, `skins`,
  `splashes`, and `hitsounds` stay at the config root.
- A bundled world can force the player's settings while it is played: a
  `blockified-options.json` in the world folder shadows any subset of settings keys
  and locks them (grayed out) in-game, then restores the player's own values on exit.
  The forced values are never written back to `options.json`.
- The solo opponent bot now renders a real BBS character (spawned like Add Character
  performers) instead of a bare armor stand, with sing/miss/idle animations.
- Note skins support Psych-style flat files named after the skin
  (`images/noteSkins/<skin>.png/.xml/.json`), and a chart's `arrowSkin` renders
  through the same RGB/sustain pipeline as selectable skins.
- A song's `/time` command is undone on exit: world time is snapshotted at song
  start and restored afterward, while natural day progression during the song is
  preserved.

### 2.1.7bbs highlights

- Song Lua can switch the HUD style per song (`setHudStyle`/`getHudStyle`), hide
  Blockified's built-in rating popups and magenta time bar
  (`setProperty('rating.visible'/'timeBar.visible'/'timeTxt.visible', ...)`,
  `showTimeBar`), and react to judgements through a new `onRatingPopup(name, combo)`
  callback, so a script can draw its own HUD from the existing rating/combo/time
  globals.
- New `fullbright` property (aliases `flatShading`/`unlit`) renders performers,
  extra characters, and world sprites/text unlit for a flat look; `setFlatShading`
  toggles all main performers and world objects at once.
- Machine menu Lua gains Psych-style `runTimer`/`cancelTimer` timers, hover
  callbacks (`onHover`/`onHoverExit`), and click/press callbacks (`onClick` on any
  widget, `onMouseDown`/`onMouseUp`, and their globals).
- Machine menu asset paths fall back from the machine folder to the mod root, with
  `mod:`/`machine:` prefixes to force one, so sounds, images, and fonts can be
  shared across machines.

### 2.1.6bbs highlights

- Custom machine menus preload their assets before opening: heavy image, atlas,
  and sound decoding runs on a background thread so the game no longer freezes,
  and only the quick final upload happens on the main thread. Player input is
  held during the silent gate and Esc cancels it. Every sound named by a literal
  in `playSound`/`precacheSound` anywhere in the script is preloaded, so a sound
  played from a click handler no longer lags on first play.
- Machine menu Lua can control Minecraft's background blur for its own screen —
  `setMenuBlur`, `enableMenuBlur`, `disableMenuBlur`, `getMenuBlur`,
  `resetMenuBlur`, and `doTweenMenuBlur` — while the dark in-world menu tint is
  always removed so custom scenes stay clean.
- New cursor API for menus: the live `cursor` table (`x`, `y`, `down`, `overId`),
  `getMouseX`/`getMouseY`, `isMouseDown`, `mouseOver`, `mouseInside`,
  `getHoveredObject`, and a live `hovered` boolean on every widget.

### 2.1.5bbs highlights

- Custom machine menus no longer fail to load the first time one is opened after
  Minecraft launches. The runaway-script guard now counts executed Lua
  instructions instead of wall-clock time, so a cold JVM's first-open startup
  work (Lua compilation, class loading, font parsing) can no longer trip it.
- Machine menu `playSound` now takes the tag and path first —
  `playSound(tag, name, [volume], [loop])` — matching the widget-creation
  argument order. Pass an empty tag for a fire-and-forget sound.

### 2.1.4bbs highlights

- Machine Lua menus can now play OGG sound effects and music through
  `playSound`, with `stopSound`, `pauseSound`/`resumeSound`, `setSoundVolume`,
  `precacheSound`, and an optional `onSoundFinished` callback. Sounds resolve
  from the machine or mod folder like every other asset, the `.ogg` extension is
  optional, and volume follows the master sound slider.

### 2.1.3bbs highlights

- Custom machines now support mod-owned Lua menus, direct song launching,
  settings/editor navigation, static sprites, and animated Sparrow XML atlases.
- Virtual machine interaction volumes are non-colliding entities, support
  fractional selection, show tool-only previews, and require confirmation for
  deletion.
- Temporary machine anchors are inventory-safe: they disappear after placement
  and invalidate when dropped or moved into storage.
- Free camera editing now includes Blender-style orbit/pan/dolly, exact numeric
  transforms, axis/plane constraints, transform resets, origin framing, Lua
  object copy/paste, and editable existing world objects.
- Long-note arrow-skin animations advance every two atlas frames while held.
- Normal gameplay restarts now use the same clean rebuild as editor playtests,
  including server-side world/player rollback and command-event reset.
- Machine Lua menus now support generated graphics, layers, custom fonts,
  optional text shadows, tagged tweens, fixed 1280x720 scaling, and direct-song
  exit routing. Mixed pixel/normalized tweens interpolate without snapping.
- Virtual machine hitboxes retain their custom dimensions through client
  synchronization instead of briefly blinking back to a 1x1 box.
- New searchable, responsive GitHub Pages documentation covers Blockified-only
  songs, packs, machines, events, note skins, Lua, multiplayer, and tools.

### 2.0.7bbs highlights

- World-aware mod content: a bundled world activates only its owning mod, while
  ordinary worlds retain all installed packs and configured external directories.
- Mod-contained Funkin' Machine profiles with per-face textures, persistent
  placed-machine selection, and Lua-built menus.
- **Funkin' Designer** item: live preview, Save/Save As/Reload, profile copy/apply.
- Designer-built invisible menu hitboxes with full-block or precise selection,
  separate virtual stage origin/facing, and light-block-style tool visualization.
- Bundled Minecraft worlds under each mod's `worlds/` folder, selectable through
  a new **Mod Worlds** button and played in place so progress stays with the pack.
- Reliable synchronous loading for bundled BBS models, including cached forms
  and character changes.
- Corrected editor free-camera shot coordinates, loaded-area limits, restart
  positioning, and selectable attached/override plus machine/camera frames.
- Psych Lua control for non-FNF health bars and score text, plus fading
  `debugPrint` overlays.
- Full-bright Lua world text and improved bundled-mod character selection.

### 2.0.5bbs highlights

- Frame-rate-independent Windows note input, timestamped hit judging, sub-frame
  hitsounds, and audio-clock editor ticks, with safe fallbacks on other systems.
- Chart-editor free camera with pose readouts, axis gizmo, camera-shot
  copy/paste, adjustable shot duration, and playtest camera controls.
- Song-controlled field of view, render distance, stage chunk loading, stage
  orientation, and performer pinning, all restored or released after play.
- Configurable pre-song warnings for expensive render-distance changes and
  author-provided warning files.
- Background, cancellable server song-file streaming to avoid freezing the
  world tick while transferring large packs.

### 2.0.2bbs highlights

- Broad Psych Engine `TemplateScript.lua` compatibility: expanded callbacks,
  live script variables, cancelable hooks, rating controls, and botplay.
- Persistent Lua save slots and achievements with sandboxed paths under
  `config/fnfmod/saves/`.
- Functional camera flash/fade/shake effects, custom substates, multi-property
  tweens, colour tweens, and tagged sound/music fades.
- Shared Psych rating and full-combo classification across gameplay, results,
  editor playtests, and Lua.
- Per-performer collision and shadow controls, plus expanded BBS form handling
  and chart-editor support.

### 2.0.0bbs highlights

- BBS FS character forms, named animation states, bundled character assets, and
  visual character editor.
- Custom Blockified performers controlled by chart events and Lua.
- Expanded Psych Engine compatibility for stages, characters, events, Lua,
  keyboard input, cameras, notes, and HUD behavior.
- Per-song rollback restores participant position, inventory, XP, effects,
  abilities, health, food, and other saved player state on finish, quit, loss,
  or disconnect.
- Chart and Lua commands use a copy-on-write world journal. Blocks and block
  entities changed synchronously by those commands return to their original
  state when play ends, without cloning entire chunks or overwriting unrelated
  world changes. Delayed mutations such as later TNT or creeper explosions are
  not currently part of this command transaction.

Optional BBS character-animation stack for Minecraft 1.21.1:

- [BBS FS mod](https://modrinth.com/mod/bbs-mod/) 2.3.1+
- [Sinytra Connector](https://github.com/Sinytra/Connector) 2.0.0-beta.15+
- [Forgified Fabric API](https://github.com/Sinytra/ForgifiedFabricAPI) 0.116.7+

BBS FS remains a Fabric jar; Connector and Forgified Fabric API translate it at
runtime for NeoForge. Keep all three as separate jars beside Blockified Engine
when using BBS forms. Blockified Engine still loads without them; BBS animation
control is disabled until the complete stack is installed.

## What it does

- **Funkin' Machine block** (Functional Blocks creative tab). Right-click it to open the song menu.
- **Funkin' Designer item** (Functional Blocks creative tab). Right-click a
  machine to edit. Shift-right-click copies a profile, then shift-right-click
  another machine to apply it. Right-click air opens virtual hitbox builder.
  Shift-use in air clears copied profile and unfinished hitbox selection.
- **Lightweight songs or complete mods**: use `config/fnfmod/songs/<song-name>/`
  for basic chart/audio entries, or `config/fnfmod/mods/<mod>/` for full creations.
- **Play as Both** merges both chart sides into the existing centered four-lane
  layout, while stage placement, performers, camera focus, and animation routing
  remain visually identical to **Play as Player**.
- **4 keybinds** (default `D F J K`) — rebindable in *Options → Controls → Funkin' Machine*.
- **Multiplayer**: the first player to click the machine picks the song ("Play VS"), the second player to click joins as the opponent side. On servers, only songs installed **on the server** are playable — the server streams the chart + audio to players who don't have them (cached in `config/fnfmod/cache/`).
- **Chart editor**: `/fnf editor [song]` or the button in the song menu. Saves Psych Engine format.
- **Character editor**: open it from the song menu to create/edit named character
  JSONs such as `bf.json` and optional `bf-opp.json`, choose BBS forms and states, preview animations, and
  preview base/per-animation camera offsets without writing JSON manually. Its
  animation list accepts custom names in addition to the built-in FNF action slots.
- **Emergency song exit**: `Ctrl+Shift+Enter` always exits the active song. This
  recovery shortcut is intentionally fixed and cannot be rebound or disabled.
- `/fnf reload` reloads all FNF content without restarting. Targeted forms:
  `/fnf reload songs|skins|splashes|animations|icons|hitsounds|fonts|options|scores`.

After selecting a song, the **Look** button chooses its presentation profile:

- **Minecraft** is the default and uses the Minecraft presentation. Complete
  packs installed in `config/fnfmod/mods/` may use rich resources from their own
  mod folder. It does not use the shared engine-assets fallback. Packs selected
  through external **Directories** remain restricted to charts, audio, events,
  and Lua/config code in this mode. Any chart whose selected file is under
  `config/fnfmod/songs/` exposes no rich song assets in Minecraft mode, even if
  `original_directory.txt` points to a complete pack.
- **FNF** uses a fixed 1280x720 game canvas, Psych character JSON/Sparrow
  animations, Lua stage foreground/background insertion, the FNF HUD, Psych-style
  section focus, camera follow, separate game/HUD zoom, and measure bumps. Psych
  stage JSON positions and camera offsets are applied to boyfriend, opponent, and
  girlfriend/speakers; the chart's `gfVersion` selects the third performer. Static
  stage JSON objects and stage Lua from Psych's `stages/`, global `scripts/`, and
  song data/audio folders are loaded in stage order.
- **Legacy** preserves the original Blockified Engine presentation and loading.

The host's choice is authoritative in multiplayer and is sent with the song manifest.
Downloaded cache entries are isolated by exact song id, difficulty, presentation mode,
and asset policy, so files left by another chart or mode cannot affect chart selection.

Psych resource lookup uses active song/mod first. A bundled mod world exposes only
its owning pack. Ordinary singleplayer/LAN worlds and dedicated servers expose the
full lightweight-song library, every installed pack, and configured external
**Directories**. Custom machine editing and Lua menus remain disabled on dedicated
servers.

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
  animations/              <- Blockified mappings for BBS forms/states
  characters/
  images/
  sounds/
  music/
  machines/
  fonts/
  weeks/
  worlds/<world>/level.dat
```

The `mods` folder is created automatically. Inside a bundled mod world, only its
owning pack is exposed: other installed packs, external directories, and lightweight
`config/fnfmod/songs/` entries are excluded. In an ordinary world, all lightweight
songs, installed packs, and configured external directories are available together.
`/fnf reload songs` rescans the current scope.

### Custom Funkin' Machines

Machine profiles live inside owning mod, never global config:

```
config/fnfmod/mods/My-Mod/
  pack.json
  machines/neon/
    machine.json
    menu.lua
    textures/machine.png
```

`machine.json`:

```json
{
  "id": "neon",
  "displayName": "Neon Machine",
  "menu": "menu.lua",
  "texture": "textures/machine.png",
  "textures": {
    "front": "textures/front.png",
    "top": "textures/top.png"
  },
  "behavior": {}
}
```

Runtime ID becomes `my-mod:neon` (folder/ID characters normalize lowercase).
`texture` applies every face. Optional `textures` keys: `all`, `side`, `front`,
`back`, `left`, `right`, `top`, `bottom`. Paths must stay inside profile/active
mod. Absolute paths, `..` traversal, symlink escape rejected.

Use **Funkin' Designer** inside mod world. Selecting profile previews texture live.
**Save** persists profile on placed block. **Save As** creates starter
`machine.json`, `menu.lua`, `textures/`. **Reload** hot-reloads files/errors.

`menu.lua` runs sandboxed: no filesystem, OS, Java, package loading, dynamic code
loading. Each callback has execution budget; excessive loops fail to built-in
song-menu fallback instead of freezing game.

```lua
local background = ui.panel('background', '', 0.5, 0.5, 320, 190)
local title = ui.label('title', 'Neon Machine', 0.5, 0.25)
local logo = ui.image('logo', 'textures/logo.png', 0.5, 0.36, 96, 48)
local play = ui.button('play', 'Choose Song', 0.5, 0.55, 180, 24)
local hard = ui.toggle('hard', 'Hard Mode', 0.5, 0.64, 180, 24)
local volume = ui.slider('volume', 'Volume', 0.5, 0.72, 180, 18)

volume.min = 0
volume.max = 1
volume.value = machineData.volume or 0.8

function volume:onChange(value)
  machineData.volume = value
end

function play:onClick()
  machineData.opens = (machineData.opens or 0) + 1
  machine.openSongSelect()
end
```

Normal Lua variables, tables, functions work. Widgets expose mutable `text`, `x`,
`y`, `width`, `height`, `visible`, `color`, `alpha`, plus type fields.
`ui.get(id)`, `ui.remove(id)`, `ui.clear()`, `onOpen()`, `onUpdate(dt)`, `onClose()`
supported. `machineData` persists per placed machine/world save.

Machine menus use a fixed 1280x720 canvas, scaled uniformly and centered. Window
resolution and Minecraft GUI scale therefore do not change widget layout;
GUI scale also does not change physical widget size. Normalized X/Y values (`-1` through `1`) use canvas dimensions;
larger values are canvas pixels. Lua globals `screenWidth` and `screenHeight` are
always `1280` and `720`. Non-16:9 screens letterbox the canvas.

Machine widgets expose mutable `order`. Higher values render in front and receive
clicks first. Creation order is used by default. Psych-style
`setObjectOrder('widgetId', order)` is also supported:

```lua
local title = ui.label('title', 'Behind cover', 0.5, 0.7)
local cover = ui.image('cover', 'images/cover.png', 0.5, 0.5, 180, 180)
setObjectOrder('title', 0)
setObjectOrder('cover', 1)
```

Generated graphics need no texture file. `ui.graph` (alias `ui.graphic`) supports
`rectangle`, `circle`/`ellipse`, `line`, `triangle`, and `polygon`:

```lua
local card = ui.graph('card', 'rectangle', 0.5, 0.5, 320, 180, '28003F')
card.borderSize = 4
card.borderColor = 0xFF00FF

local slash = ui.graph('slash', 'line', 0.5, 0.5, 180, 60, 'FFFFFF')
slash.thickness = 5
slash.angle = -15

local badge = ui.graph('badge', 'polygon', 0.5, 0.3, 140, 120, 'E600FF')
badge.points = {{0, -60}, {70, 45}, {0, 60}, {-70, 45}}
```

Signature: `ui.graph(id, shape, x, y, width, height, color)`. Omit `shape` to
create a rectangle. Polygon points are pixel offsets from the graph center.
Graphics support normal `x`, `y`, `width`, `height`, `color`, `alpha`, `angle`,
`visible`, `order`, and compatible widget tweens. `borderSize`, `borderColor`,
and line `thickness` are mutable.

Text widgets (`label`, `button`, `toggle`, and `slider`) support custom TTF/OTF
fonts through mutable `font` and `fontScale` fields. Set mutable `shadow` to
`false` to disable Minecraft's default text shadow:

```lua
local title = ui.label('title', 'Earrings Machine', 0.5, 0.15)
title.font = 'VCR_OSD_MONO.ttf'
title.fontScale = 2.0
title.shadow = false
```

Fonts resolve from the machine profile's `fonts/`, then the owning mod's `fonts/`,
then `config/fnfmod/mods/fonts/`. Missing or invalid fonts log a warning and use the
Minecraft font.

Machine widgets support Psych-style tweens:

```lua
local cover = ui.image('cover', 'images/cover.png', -0.2, 0.5, 100, 100)

function onOpen()
  doTweenX('cover-enter', 'cover', 0.5, 1, 'sineOut')
end

function onTweenCompleted(tag)
  if tag == 'cover-enter' then
    doTweenAlpha('cover-fade', 'cover', 0.5, 0.4, 'linear')
  end
end
```

Available calls: `doTweenX`, `doTweenY`, `doTweenAlpha`, `doTweenAngle`,
`doTweenWidth`, `doTweenHeight`, `doTweenFontScale`, `doTweenColor`, and
`cancelTween`. Starting another tween with the same tag replaces it. Completion
calls global `onTweenCompleted(tag)` and optional widget method
`function cover:onTweenCompleted(tag)`.

X/Y tweens may cross between pixel and normalized coordinates. Blockified
converts both endpoints to canvas pixels while interpolating, then stores the
requested final value, preventing an end-of-tween position snap.

Static sprites and FNF/Psych Sparrow XML animations are supported inside machine
menus. Files must stay inside active machine profile/mod:

```lua
local logo = ui.sprite('logo', 'textures/logo.png', 0.25, 0.3, 96, 48)
logo.angle = -5

local dancer = ui.animatedSprite('dancer',
  'textures/dancer.png', 'textures/dancer.xml', 0.72, 0.45, 150, 150)

dancer:addAnimation('idle', 'BF idle dance', 24, true)
dancer:addAnimation('hey', 'BF HEY', 24, false)
dancer:play('idle')

function dancer:onComplete(animation)
  if animation == 'hey' then self:play('idle') end
end
```

`addAnimation()` / `addAnimationByPrefix()` use XML frame-name prefixes.
`play()` / `playAnimation()`, `pause()`, `resume()`, `stop()`, `setFrame()` and
`getAnimations()` available. Runtime state: `animation`, `frame`, `playing`,
`finished`; `fps` and `loop` can change during playback. Sprite fields: `angle`, `flipX`, `flipY`,
`antialiasing`, plus normal position/size/color/alpha/visibility fields. Without
explicit `play()`, first XML animation starts automatically. Atlases are cached
per PNG/XML pair and released/rescanned with machine content.

Custom Lua can fully replace built-in song selector. Song list is authoritative
from server; array indices start at 1:

```lua
for _, song in ipairs(machine.getSongs()) do
  -- song.id, song.name, song.opponentIcon, song.difficulties
end

local song = machine.getSong('earrings') -- nil when unavailable
if machine.hasSong('earrings') then
  machine.playSong('earrings', 'normal', {
    duet = false,
    playAs = 'player', -- player, opponent, both
    look = 'minecraft' -- minecraft, fnf, legacy
  })
end
```

`machine.playSong(id, difficulty)` also works with solo/player/Minecraft defaults.
Positional form is `machine.playSong(id, difficulty, duet, playAs, look)`.
Invalid songs/difficulties stay in menu and show error. Server revalidates every
launch. `machine.openSongDetails(id)` opens built-in options for one song.

Navigation/API calls:

- `machine.openSongSelect([returnTo])` — existing complete selector; optional
  `menu`, `world`, or `selector` controls where its next solo song exits.
- `machine.openSettings()` / `machine.openOptions()` — settings; Back returns to Lua menu.
- `machine.openCharacterEditor()` — character editor; Back returns to Lua menu.
- `machine.openChartEditor([songId], [difficulty])` — chart editor.
- `machine.join()` — join waiting LAN session; alias of selector/session interaction.
- `machine.saveData()` — persist `machineData` immediately.
- `machine.close()` — close menu and release machine session.
- `machine.setSongExitTarget('menu'|'world'|'selector')` — choose where solo
  gameplay returns after finish, quit, or loss. Direct custom-menu songs default
  to `menu`; `world` closes every menu. The target also survives a handoff through
  `machine.openSongSelect()`.

Existing APIs remain compatible. Escape also releases chooser ownership. Lua menu
owns normal machine session rules: one host, optional LAN guest, busy-state checks.

Custom machines: singleplayer/LAN only. LAN host edits; guests use menus. Guest
must have same mod folder/machine content. Put `"version"` in `pack.json`;
Blockified also fingerprints machine files, rejects mismatch, offers built-in
song-menu fallback. Assets are not streamed.

#### Virtual machine hitboxes

Funkin' Designer can create clickable menu regions without visible Funkin'
Machine block:

1. Right-click air using Designer.
2. Choose machine profile.
3. Choose **Full Blocks** or **Precise**, then **Start Selection**.
4. Right-click two opposite corners using Designer.
5. Game gives temporary **Machine Anchor** item.
6. Place anchor outside selected region where song stage origin should be.
   Anchor faces player at placement time; that direction controls stage/camera.

Hitbox is one persistent, non-colliding entity, not world blocks. It can overlap
slabs, stairs, fences, models, solid blocks, fluids, and other non-full geometry
without replacing any world state. **Full Blocks** snaps entity bounds to block
grid. **Precise** keeps exact clicked points. Maximum is 64 blocks per axis.

Hitbox region opens selected machine profile/menu for every player. Invisible
anchor supplies same position/facing expected from real machine, so gameplay,
commands, camera, and Lua use it normally. Anchor and hitbox survive world saves.

Selection preview is yellow while choosing corners and green after completion
while temporary anchor is held. Hold Funkin' Designer after placement to reveal
virtual machine: orange box/line is anchor/facing; cyan wireframe is exact entity
hitbox. Right-click cyan hitbox using Designer to edit profile; shift-right-click
copies/applies profile, matching physical machine controls. Left-click cyan hitbox,
or right-click orange anchor, opens removal confirmation. Confirming removes whole
virtual machine and cancels active song session. Without Designer, anchor cannot be
targeted or broken. Recreating it requires new selection and anchor placement.

Only singleplayer/LAN host can create or remove virtual machines. LAN guests can
use hitbox menus. Temporary anchor disappears immediately after placement. Dropping
it, moving it into any chest/vessel/container, losing it from player inventory,
disconnecting, changing dimension, cancelling, or restarting server deletes token
and invalidates selection. Stale/copied anchor items are automatically removed.

### Blockified BBS character animations

`animations/<character>.json` and optional `<character>-opp.json` may define any
number of named animation mappings. The conventional `idle`, `left`, `down`, `up`,
`right`, `miss`, and `hey` names continue to drive normal gameplay, but they are
not a fixed whitelist. Custom names work with the chart **Play Animation** event
and Lua `characterPlayAnim` in every presentation mode:

```json
{
  "bbsForm": "My BF form",
  "animations": {
    "idle": "idle",
    "left": "singLEFT",
    "attack": "sword-swing",
    "victory": {
      "state": "victory-pose",
      "cameraOffset": [1.5, -0.5]
    }
  }
}
```

The JSON key (`attack`) is the name used by events and Lua. The string value, or
an object's `state`, is the animation-state ID defined on the selected BBS form.
An object may additionally specify `cameraOffset`. If `state` is omitted from an
object, Blockified uses the JSON key as the BBS state ID. In the character editor,
use **+** and **-** beside the animation camera fields to add or remove custom
entries; custom names, BBS state IDs, offsets, and previews are editable there.

Songs can also create any number of named, client-side BBS performers. The chart
editor's **Add Character** event accepts a tag, character definition, local
`X,Y,Z` position, yaw offset, and initial `animation,side`; **Remove Character**
removes that tag. Stage-local X points camera-right, Y points up, and Z points
camera-forward. These performers never create server entities or armor stands.

Lua exposes the same roster:

```lua
addBlockifiedCharacter('backup', 'backupSinger', -3, 0, 1, 0, 'idle', 'opponent')
characterPlayAnim('backup', 'hey', true)
doTweenX('backupMove', 'backup', 2, 1.0, 'sineInOut')
setProperty('backup.z', -1)
setProperty('backup.angle', 45)
removeBlockifiedCharacter('backup')
```

`makeBlockifiedCharacter` aliases the add call. Dedicated helpers include
`blockifiedCharacterExists`, `setBlockifiedCharacterPosition`,
`setBlockifiedCharacterRotation`, and `changeBlockifiedCharacter`. Standard
`characterPlayAnim`, `characterDance`, `get/setCharacterX/Y`, generic properties,
and X/Y/Z/angle tweens recognize the custom tag.
Definition names use the active mod by default; prefix one with `global:` to use
a user definition from `config/fnfmod/mods/animations` explicitly.

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

`config/fnfmod/mods/scripts/*.lua` contains global scripts that run with complete mod
packs. Pack-scoped song scripts belong in `mods/<pack>/data/<song>/` or
`mods/<pack>/songs/<song>/`. Lightweight `songs/<song>` entries do not run Lua.

Charts saved from another directory keep `original_directory.txt` and use that
exact source pack for unsaved difficulties, audio, events, Lua, images, icons,
characters, stages, sounds, fonts, and animations. The locally saved chart takes
priority, and a local `events.json` replaces the source event file. This exception
applies to Legacy and FNF presentation. Minecraft presentation treats every chart
stored under `config/fnfmod/songs/<song>` as asset-restricted; ordinary lightweight
entries remain limited to their basic files in every mode.

Gameplay callbacks follow Psych's `TemplateScript.lua`: create/update, countdown
start and tick, song start, step/beat/section, note spawn, note hit/miss pre and
post, key press/release pre and post, ghost tap, events (including `onEventPushed`
and `eventEarlyTrigger`), camera moves, rating and score updates, pause/resume,
game over, custom substates, tween/timer/sound completion, song end, and destroy.
Callbacks that Psych lets a script cancel honor `Function_Stop` here too: returning
it from `onGameOver` cancels the death, from `onKeyPressPre` swallows the input,
and from `onRecalculateRating` skips the score update.

All 106 documented Psych script variables are exposed, and the live ones —
`curBeat`, `curStep`, `curSection`, `mustHitSection`, `altAnim`, `gfSection`,
`score`, `misses`, `hits`, `combo`, `rating`, `ratingName`, `ratingFC`,
`totalPlayed`, `totalNotesHit`, `playbackRate`, `inGameOver`, and the rest — are
refreshed every frame. `week` and `weekRaw` are empty because Blockified has no
story mode, and `shadersEnabled` reports `false`, so scripts branching on them
skip the paths Blockified cannot run.

Supported APIs cover gameplay properties/groups, score/health/rating, strum
transforms, timers, tweens, input, save data, achievements, variables,
random/string/color and controlled file helpers, event/camera calls, Lua text,
static PNG sprites, and animated Sparrow PNG/XML sprites from `images/`.
In the FNF HUD style, Psych's native object names are scriptable: `healthBar`,
`healthBarBG`, and `scoreTxt` expose position, dimensions, alpha, visibility,
colors, score text, and score-text transforms. `healthBar.percent` reads 0–100
and can also set Blockified health. `setScore`, `addScore`, `setHits`, `addHits`,
`setRatingPercent`, `setRatingName`, and `setRatingFC` all take effect, but a run
a script changed is never saved as a personal best — the same rule botplay follows.
`setHealthBarColors(left, right)` is supported.

Lua tweens support `Sine`, `Cubic`/`Cube`, `Quint`, `Circ`, `Elastic`, `Quad`,
`Quart`, `Expo`, `Back`, `Bounce`, `Smooth`, and `SmootherStep`, each with `In`,
`Out`, and `InOut` variants (for example `sineIn`, `bounceOut`, or `elasticInOut`).
Names are case-insensitive; `linear` remains the default. `startTween` drives every
key of a values table at once and accepts `ease` and `startDelay` options;
`doTweenColor` blends per colour channel rather than across the packed value.
`soundFadeIn`, `soundFadeOut`, `soundFadeCancel`, `musicFadeIn`, and `musicFadeOut`
ramp tagged sounds; a blank tag addresses the music channel.

`cameraShake`, `cameraFlash`, and `cameraFade` are real screen effects. Each of
`game`, `hud`, and `other` keeps its own overlay, and a running effect is not
replaced unless the call passes `forceReset`, matching Flixel.

`openCustomSubstate`, `insertToCustomSubstate`, and `closeCustomSubstate` work.
Substate members draw above every other layer, and a substate opened with
`pauseGame` holds the song, notes, and events until it closes.

`getPropertyFromClass` and `setPropertyFromClass` map Psych's common engine paths
(`ClientPrefs.data.*`, `PlayState.instance.*`, `FlxG.width`/`height`) onto the
matching Blockified property. Other class paths return `nil` instead of guessing.

Save data is stored as JSON under `config/fnfmod/saves/`. `initSaveData`,
`setDataFromSave`, `getDataFromSave`, `flushSaveData`, and `eraseSaveData` behave
like Psych's FlxSave slots: values stay in memory until the script flushes them.
Achievements keep their own slot and are written as they change. Save and folder
names are reduced to plain file-name characters, so a script cannot write outside
that folder. `deleteFile` is limited to the song folder, like `saveFile`.

`getModSetting` reads `mods/<pack>/data/settings.json` and returns the declared
default value. `getTranslationPhrase` returns the caller's default phrase with
Psych's `{1}`/`{2}` argument substitution; `getFileTranslation` returns its input.
The Discord Rich Presence calls are accepted and ignored.
Psych `playSound`, `playMusic`, tagged sound controls, sound properties, precaching,
looping, and `onSoundFinished` load OGG files from `sounds/` or `music/`.
FNF-mode characters honor Psych `sing_duration`, dance-left/right beat frequency,
animation offsets, `-loop` animations, miss/special completion, `Hey!` notes, and
the common `characterPlayAnim`, `characterDance`, `getCharacterX/Y`, and
`setCharacterX/Y` Lua calls. Character aliases `boyfriend`/`bf`, `dad`, and
`gf`/`girlfriend`/`speakers` are accepted by character properties and animation calls.
Psych character JSON `no_antialiasing` is honored in the FNF look: `true` uses
nearest/pixel filtering and `false` uses smooth filtering. The direct
`antialiasing` field remains accepted as a Blockified compatibility alias.
FNF character properties can be read, written, and tweened through `boyfriend`,
`dad`, and `gf`, plus Psych's `boyfriendGroup`, `dadGroup`, and `gfGroup` aliases.
Supported visual fields include `x`, `y`, `alpha`, `angle`, `visible`, `flipX`,
`color`, `scale.x`, `scale.y`, and `antialiasing`. Fractional character alpha is
preserved; `doTweenX/Y/Alpha/Angle` target the same live character properties.
`scaleObject`, `setGraphicSize`, and the sprite-antialiasing helpers also recognize
character and character-group names rather than creating dummy Lua sprites.
Automatic beat impulses can be controlled without snapping an active bop using
`setCameraBopEnabled('game'|'hud'|'both', enabled)` (alias
`setDefaultCameraBop`) or `camGame.bopEnabled` / `camHUD.bopEnabled`.
`setProperty('character.antialiasing', boolean)` and
`setObjectAntialiasing(tag, boolean)` affect character and Lua sprite filtering.

Psych keyboard polling is supported. `keyPressed`, `keyJustPressed`, and
`keyReleased` read Psych control names (including the four note directions), while
`keyboardPressed`, `keyboardJustPressed`, and `keyboardReleased` accept physical
key names such as `A`, `SPACE`, `LEFT`, `F1`, `NUMPAD_ENTER`, or `RIGHT_SHIFT`.
Generic `SHIFT`, `CONTROL`, `ALT`, and `SUPER` names recognize either side.

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
HaxeFlixel runtime cannot exist unchanged in Minecraft. These accept their normal
arguments and return safely without crashing, so a shared script keeps running:

- HScript (`addHScript`, `runHaxeFunction`, `addHaxeLibrary`, `callOnHScript`,
  `setOnHScript`) and Haxe class instantiation/method calls (`createInstance`,
  `callMethod`, `instanceArg`)
- Flixel GLSL shaders. The `setShader*` calls do nothing; the `getShader*` calls
  return a typed empty value rather than `nil`, so reading one back cannot cause
  an arithmetic error inside the script
- FlxAnimate texture atlases (`loadAnimateAtlas`, `addAnimationBySymbol`)
- Video and dialogue cutscenes (`startVideo`, `startDialogue`)
- Flixel groups (`addToGroup`, `removeFromGroup`, `updateHitboxFromGroup`)
- Gamepad polling, the time bar, and story-mode `loadSong`

Sparrow XML animation is supported.

### Lua custom fonts

`setTextFont(tag, "FontName.ttf")` supports multiple TTF and OTF files. Fonts are
searched in this order:

```
config/fnfmod/mods/<pack>/fonts/
config/fnfmod/mods/fonts/            <- global fonts
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

## Character animations (BBS FS) + animation sets

Use **Character Editor** in the Funkin' Machine song selector to edit the global
files visually. The set and role buttons cycle named JSONs and player/opponent data;
the form/action buttons cycle BBS forms and FNF actions. **Preview State** plays the
currently entered BBS state on the model, while the red cross shows the selected
base plus action camera offset. **Save** writes the same JSON format documented below.

The animation setting has two built-in choices:

- **None** disables BBS character animation control.
- **Default (song)** uses the active chart's character IDs. For example, a chart
  whose `player1` is `bf` and `player2` is `dad` uses the active mod's
  `animations/bf.json` and `animations/dad.json`. Global `default.json` (or the
  legacy global `character.json`) is the fallback.

The **Animations** selector lists only character JSON definitions found directly
in `config/fnfmod/mods/animations/`. It never lists raw BBS forms or definitions from
installed mods. `idle2` remains opt-in through a JSON definition; when mapped,
`idle` and `idle2` alternate every beat. Existing playerAnimator/Emotecraft
animation files are not compatible with BBS FS and are ignored.

**Animation definitions:** each `config/fnfmod/mods/animations/<name>.json` is a
user-selectable mapping. A complete pack can privately provide
`mods/<pack>/animations/<character>.json`; those names are available to that
pack's chart and **Change Character** events but cannot be selected in Settings.
The old `animations/<name>/character.json` layout is still read for compatibility,
but new files and Character Editor saves use the named-file layout.
Pick yours with the **"Anims:"** button in the Funkin' Machine menu — in VS mode
each player uses their own set. Selecting **None** remains an explicit opt-out, so
Change Character does not enable BBS control after the user disables it.

A definition selects a BBS form and maps
FNF actions to animation-state IDs:

```json
{
  "bbsForm": "My BF form",
  "icon": "bf",
  "rotation": 0,
  "cameraOffset": [0.0, 0.5],
  "animations": {
    "idle":  "idle",
    "idle2": "danceRight",
    "left":  { "state": "singLEFT",  "cameraOffset": [-1.0, 0.0] },
    "down":  { "state": "singDOWN",  "cameraOffset": [0.0, -1.0] },
    "up":    { "state": "singUP",    "cameraOffset": [0.0,  1.0] },
    "right": { "state": "singRIGHT", "cameraOffset": [1.0,  0.0] },
    "miss":  "miss",
    "hey":   "hey"
  }
}
```

`bbsForm` may be a BBS user-form name, display name, form ID, or the final path
segment of an installed BBS model form. The object key `anim` remains accepted as
an alias for `state`. If `bbsForm` is omitted, Blockified triggers states on the
form already worn by that player. If only `bbsForm` is provided, conventional
state IDs are mapped automatically.

To give a selected definition different opponent-side data, add an `-opp` file
beside it (for example, `bf-opp.json` beside `bf.json`) using the same schema. Its form, state mappings, icon,
`rotation`, and camera offsets override only the opponent side. Missing fields
fall back to the normal named JSON.

`icon` is the icon key without `icon-` or `.png`. For example, `"icon": "bf"`
uses `icon-bf.png` from the song/mod `images/icons` folders or from the global
`config/fnfmod/mods/images/icons/`. It is applied when that animation set is active and also
when Change Character selects the set.

`rotation` is a degree offset added to the normal character rotation, so `0`
preserves the default and negative values rotate the other way. It rotates only
the character; the gameplay camera keeps the stage's normal direction. Command
events can use `<character_rotation:degrees>` for the same additive angle.

`cameraOffset` values are in blocks: x = screen right, y = screen up. The top-level
one is the character's camera center; per-state ones nudge the camera while that
state plays. The legacy role-based `mapping.json` (`"player"`/`"opponent"`) still
works for the default set.

States play on the actual BBS forms during gameplay (both players in VS mode).
When Blockified temporarily applies a form, it restores the player's previous BBS
form when gameplay ends.

`hey` is used by the **Hey!** chart event and by `Hey!` note types. The event's
duration controls when the BBS character may return to its beat-synced idle.
Definitions with only `bbsForm` use the conventional `hey` state name.

## Camera

During a song the camera follows the focused character in full 3D while retaining
FNF-style screen-space animation nudges. Focus follows the chart:
`mustHitSection` = camera on the player side,
otherwise the opponent side (in solo the machine block stands in for the opponent).
Legacy and Minecraft modes use the **Camera Behavior** event to change Must-Hit
camera movement. Value 1 is a speed multiplier; Value 2 is an easing curve.
Constant snaps directly to the current target. An event
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
- Empty Value fields show event-specific hints. The wrapped description below the
  controls stays inside the panel; **Event Help...** opens the complete scrollable
  value guide. Psych built-ins follow Psych Engine 1.0.4's event guidance, while
  Minecraft Command and Blockified camera events have Blockified-specific guides.
- **Camera Zoom** events use Value 1 as a persistent zoom offset (`0` normal,
  positive in, negative out) and Value 2 as a 500 ms easing preset.
- **Camera Focus** events override Must Hit focus using Value 1 (`player`,
  `opponent`, or `gf`) and Value 2 easing. A later Camera Focus with both values empty
  restores normal Must Hit section tracking.
- **Camera Behavior** events change Legacy/Minecraft Must-Hit camera speed and
  easing. Leave both values empty to reset the behavior.
- **Camera Follow Pos** remains Psych-compatible when only Values 1 and 2 are
  used. Value 3 enables Minecraft 3D movement (`X = camera-right`, `Y = up`,
  `Z = camera-forward`), Value 4 selects easing, and Value 5 can override normal
  and Lua focus movement. Empty extra values retain normal Psych behavior.
- **Camera Rotation 3D** uses Values 1/2/3 for additive pitch/yaw/roll and Value 4
  for easing. Leave all three rotations empty to return to the normal view.
- Every Blockified easing control has a separate **in / out / inOut** selector.
  Supported curves are Smooth, Sine, Cubic, Quint, Circ, Elastic, Quad, Quart,
  Expo, Back, Bounce, Linear, and Constant.
- Psych built-ins are available in the dropdown: **Hey!**, **Set GF Speed**,
  **Add Camera Zoom**, **Play Animation**, **Camera Follow Pos**,
  **Alt Idle Animation**, **Screen Shake**, **Change Character**,
  **Change Scroll Speed**, **Set Property**, and **Play Sound**. `Set GF Speed`
  is FNF-profile-only, matching Psych. Other events use Psych semantics in FNF
  mode and Minecraft-aware camera/body variants in Minecraft and Legacy modes.
  **Hey!** uses the mapped BBS `hey` state outside the Psych scene as well.
  **Camera Zoom** remains Blockified Engine's persistent,
  eased zoom; **Add Camera Zoom** is Psych's temporary game/HUD impulse.
- Snap: 4th–64th. Save writes `config/fnfmod/songs/<file>/<file>.json` (Psych format).

## Gameplay options

In the song select screen: **Play as** (Player / Opponent / Both — solo only;
Both puts you center stage playing every note, and one keypress hits overlapping
notes on both strumlines), Downscroll, Ghost Tapping. More in
`config/fnfmod/options.json` (`offsetMs` for audio calibration, `scrollSpeedMult`).
Player and opponent icon choices apply globally to every song. **Default (song)**
uses each chart character's health icon; **None** hides that side's icon.

### Precise Input

*Settings → Gameplay → Precise Input* decouples note timing from the frame rate.
Normally a key press is only noticed when Minecraft polls input, once per rendered
frame, so hit timing rounds to the frame (about 16 ms at 60 fps). With Precise
Input on, a background thread reads the keyboard at roughly a kilohertz, stamps
each press with the moment it happened, and the hit is judged against the song
position at that instant — so timing reflects the actual key press, independent
of frame rate.

The high-rate reader is **Windows only** (`GetAsyncKeyState`); the toggle shows
"(Windows only)" on other systems, where input stays frame-bound. It reads the
keyboard only while the game window is focused and a song is playing, honours the
rebindable note keys, and falls back to the normal per-frame path for any key it
cannot map or if the reader fails to load. The song-position clock it judges
against is already sub-frame accurate (it interpolates the audio position with a
nanosecond timer), so the two halves line up.

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
- `client/anim/CharacterDefinitionFile.java` owns loss-preserving character JSON
  parsing/writing for the visual character editor.
- `client/gameplay/PsychAssetResolver.java` owns ordered Psych asset lookup;
  `client/audio/PsychSoundPlayer.java` owns per-song Lua/event sound lifetime,
  including tagged volume fades.
- `gameplay/PsychRating.java` owns Psych's rating-name tiers and full-combo
  classification, so gameplay, results, and Lua all report the same values.
- `client/camera/CameraOverlay.java` owns per-camera flash and fade state.
- `client/lua/LuaSaveData.java` owns Lua save slots and achievement persistence
  under `config/fnfmod/saves/`.
- `client/input/NoteInput.java` is the timestamped note-input queue; a source
  pushes press/release events and gameplay drains them each frame.
  `client/input/WindowsRawKeyBackend.java` is the high-rate (kHz) source that
  makes hit timing frame-rate-independent. `SongPlayer.positionMsAt(nano)` maps an
  input timestamp to a song position, and `gameplay/GameplayClock.java` freezes
  animation time while paused.

These classes are intended as stable starting points for contributors. Keep file
I/O, parsing, and rendering out of screens when adding comparable features.
