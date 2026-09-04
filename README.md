# Blockified engine (fnfmod)

A feature-full Friday Night Funkin' engine inside of Minecraft — **NeoForge 1.21.1**.

Current release: **2.5.0bbs**.

Documentation: **[Blockified Engine Docs](https://migokc.github.io/Blockified-engine_a-Minecraft-mod-made-with-AI/)**.

> **Unofficial fan project.** Blockified Engine is not affiliated with or
> endorsed by Mojang Studios, Microsoft, The Funkin' Crew, or Friday Night
> Funkin'. All referenced names and trademarks belong to their respective owners.

### 2.5.0bbs highlights

- Machine-menu Lua now supports projected 3D widgets, an authored Minecraft camera
  and FOV, multiple Lua screens with shared audio/data, keyboard and scroll input,
  tagged virtual-machine song launches, and direct world exit. Menus that never
  control the camera retain Minecraft's first-person HUD and hand presentation.
- The Menu Lua Editor is a full 2D/3D authoring viewport with clickable and
  multi-selectable objects, draggable layers, grid snapping, local/global transforms,
  free and authored camera modes, undo/redo, property copy/paste, and tween/keyframe
  editing. It saves ordinary Lua inside a marked generated block and preserves
  handwritten callbacks outside that block.
- Gameplay and menu Lua share gradients, alpha/luminance masks, clipping, nine blend
  modes, compositing groups, object parenting, effect tweens, font-quality controls,
  and shader-facing lit/flat/emissive world rendering. See
  [Lua layer effects](docs/lua-layer-effects.md) for signatures and limits.
- Funkin' Designer now owns chunk-loader and hitbox-less virtual-machine placement;
  the separate Chunk Loader item is gone. Blue/orange crosshair outlines guide
  placement, virtual machines have unique Lua-addressable tags, and chunk points
  remain invisible outside Designer authoring.
- Note Settings and the Creator Tools NoteSkin Editor now separate everyday choices
  from asset authoring. Pack/global pickers preview note skins, splashes, and hold
  covers; Psych-style normal/pixel layouts, per-skin RGB behavior, hurt-note sustains,
  export destinations, and live chart/audio previews share the gameplay renderer.
- The optional Psych-shaped display keeps a native-resolution centered 16:9 game
  canvas with black letterboxing. Gameplay/playtest rollback now also restores
  weather changed by song commands, alongside player, block, time, and game-mode
  state.

### 2.3.4bbs highlights

- The Song Menu now separates Freeplay, Psych-compatible Story Mode, and Settings.
  Weeks have ordered song playback, difficulty lists, score summaries, scrolling,
  artwork, a Week Maker, and Lua-readable metadata. Machine menus can use multiple
  Lua screens while tagged audio and shared data continue between them.
- Note Settings is now a full live chart/song preview and music player. It supports
  per-part Base, Highlight, and Outline RGB colors, delay fine-tuning, pixel notes,
  sustain pieces, splashes, hold covers, chart/difficulty selection, and real-time
  skin changes. The Creator Tools NoteSkin Editor separately edits skin transforms
  while previewing the user's saved settings. Gameplay receptors use the same
  24 FPS animation behavior.
- Chart editing and playtesting gained improved timeline playback, optional playtest
  controls, state-preserving catch-up, stronger rollback when seeking, charting
  offset, Psych/Codename/V-Slice compatibility fixes, and formatted chart/event
  output. Free Camera gained precise numeric position editing and an independent
  Camera View rotation layer alongside normal/orbit rotation.
- The redesigned Blockified screens now share one responsive visual system centered
  on the signature `#B82BFF` purple palette. Settings, Mods, selectors, character and
  machine tools, Story Mode, quick actions, dialogs, and Free Camera use consistent
  panels, spacing, selection states, clipping, and scrolling.
- Mods settings recognizes Psych packs and V-Slice/Polymod metadata, shows pack
  details and permissions in one scrollable panel, and adds combined upstream,
  Blockified, V-Slice, and Psych credits. Character animation selectors support
  local PNGs or Minecraft account skins and preserve texture-keyframed forms.
- Character Editor form bundles no longer duplicate a BBS form that already has the
  same name/identity. Temporary forms recover after interrupted sessions, bundled
  forms can be installed into BBS Recent, and BBS URL texture cache files are cleared
  on world exit, game shutdown, or the launch following a hard crash.

### 2.3.0bbs highlights

- Move a world between your saves and a pack without leaving the game: `/fnf world
  export <pack>` relocates the current world into a pack's `worlds/` folder and
  reopens it as a bundled mod world, and `/fnf world import` moves a bundled world
  back into saves. A short black-screen transition saves, closes, moves, and reloads
  the world with movement and camera locked and a progress bar, and self-heals if the
  game is closed mid-transition.
- A host-only **World Settings** button in the song selector edits a bundled world's
  `blockified-options.json` in-game: toggle cheats, world saving, whether external
  packs and directories are allowed, and hide the button; force the world's players
  onto your current gameplay/visual settings; and bundle the models and textures of
  placed BBS model blocks into the world so it still renders when shared.
- Characters can load Adobe Animate / Better Texture Atlas art (modern V-Slice
  atlases) through new Lua sprite functions, and a character's skin can be sourced
  from its BBS form, the real player skin, or a chosen file.
- Performers, BBS models, entities, and world sprites can carry colored screen-space
  outlines controlled from Lua.
- Dedicated servers no longer cap per-song transfer size; songs of any size transfer,
  while the file-count, name, SHA-1, and overflow checks stay in place.
- Note Settings timeline dragging supports Shift precision mode. Its free-camera
  editor now keeps existing runtime objects synchronized with the selection gizmo
  at mouse/render rate, including paused main BBS performers.
- The free-cam Camera tab exposes editable Position X/Y/Z values in the currently
  selected machine/camera frame, allowing exact camera placement without moving it
  through the viewport controls.

### 2.2.9bbs highlights

- Gameplay Lua is sandboxed from process shutdown and environment access, and
  every callback has an execution budget. Dedicated servers transfer only chart,
  event, and audio data with a 100 MiB per-song limit, bounded chunks, manifest
  validation, SHA-1 checks, and no gameplay Lua; singleplayer and LAN retain local
  scripting and complete mod assets.
- The Mods settings page detects either complete mod folders or containers of
  isolated Psych-style packs, reads `pack.json`/`pack.png`, shows compact paths and
  shows selected-pack details and scrollable per-source/per-pack permissions.
  Intentionally blocked images stay hidden instead of becoming missing textures.
- Camera Orbit now owns an offsettable or pinned XYZ pivot with duration/easing,
  while Camera Rotation 3D's normal layer animates around it. Its independent Camera
  View layer can rotate the view without changing the orbit. Focus changes tween the pivot through
  Camera Behavior, animation offsets can be disabled, and extra characters plus
  Minecraft/Legacy GF replacements participate in camera and character routing.
- Gameplay world sprites and 2D world characters use client-side entity hosts with
  optional gravity, collision, Minecraft blob shadows, and independently toggleable
  IRLights projected shadows. Compatible BBS player forms gain author-controlled
  skin-choice permissions without texture keyframes covering the chosen skin.
- The Directional Shading event independently flattens block/fluid and mob/entity
  lighting while preserving light levels and restoring vanilla state on exit.
  Codename time signatures import alongside V-Slice meters, and naked-mod global
  scripts now run for every eligible local song.
- The chart editor adds an editor-only Charting Offset, resolution-aware GUI scale,
  a built-in/custom Note Type dropdown, bulk notetype application, and gameplay-style
  Vortex receptors. Tap notes confirm exactly as forward scrolling tweens across
  them, backward scrolling stays inactive, and sustain confirms loop while held.

### 2.2.8bbs highlights

- The Character Editor now uses tabbed Character, Model, and Animations pages,
  keeps its live form preview on the left, loads characters and bundled BBS forms
  through searchable in-game lists, and saves player plus any used opponent JSON
  together. Saving a custom character also bundles its selected model and texture.
- Player and Opponent animation settings now open searchable selectors with a
  live preview on the right. Configured note keys preview directions, dual idles
  alternate at 120 BPM, native looping idles remain native, and the Character
  Editor animation button can be cycled directly with the mouse wheel.
- Loading a Character Editor animation with a bundled BBS form imports a private
  copy into BBS's Recent category. Player and distinct opponent bundles are both
  imported, and reloading the same bundle replaces its prior Recent entry.
- Temporary BBS forms used by gameplay and animation previews are restored before
  logout. A tiny per-world recovery journal restores the original form on the next
  login if Minecraft was closed or crashed before normal cleanup could run.
- Freeplay uses an animation definition's `icon` when the chart character has no
  Psych/V-Slice character JSON. If both definitions share a name, the character
  JSON and its health icon take priority.
- Chart audio import accepts multiple selected files, recognizes common Inst and
  voice names, renames only the saved copies, and converts common audio formats
  to OGG through FFmpeg. Ctrl+S uses the assigned folder or the normal song-folder
  layout without a dialog; Save As chooses and remembers a folder, with a frontmost
  warning when a remembered folder no longer exists.
- Active BBS forms now support Lua/free-camera pitch, yaw, roll, and independent
  X/Y/Z visual scaling for main and extra characters. Folder character definitions
  can additionally share a mod-local asset tree through `assets_path.txt`.
- Time signatures now drive written-beat character/icon bops, measure camera
  accents, and new meter-aware Lua values/callbacks while preserving compatible
  quarter-note BPM and note timing. Opponent/Both play modes retain chart-role Lua,
  scoring, animation, icon, vocals, and performer behavior.
- Camera Rotation 3D gains a duration value; `getMenuBlur()` reports the player's
  real configured value; volume keys are rebindable and work in pause menus; the
  playstate cursor fades without replacing the OS cursor; and missing graphics use
  a crisp no-antialiasing placeholder instead of a white square.

### 2.2.6bbs highlights

- New invisible Chunk Loader Point blocks keep configurable square regions loaded
  without altering gameplay camera coordinates. Hold the point item or Funkin'
  Designer to reveal their marker and exact chunk boundary.
- The in-game Designer editor assigns each point a unique tag, a 0-12 chunk
  radius, and an enabled state. Persistent NeoForge tickets survive world reloads,
  overlap safely, and release when their owning point is disabled or removed.
- Gameplay Lua can read and change point tags, radii, enabled states, and world
  coordinates through `chunkLoadPoints.<tag>.*`; mutations participate in normal
  song rollback.
- Chunk Loader Points are protected from flowing water and lava. The feature is
  scoped to singleplayer and LAN and remains disabled on dedicated servers.

### 2.2.5bbs highlights

- Chart Beat Snap now changes subdivision spacing while preserving timeline
  dimensions, Grid Zoom controls visible fixed-height rows independently, and
  notes remain aligned and placeable at every zoom level.
- The chart editor can remember a separate output folder per song, writes chart
  and event JSON in readable pretty-printed form, and waits for playstate rollback
  to finish before opening so world, inventory, time, and game mode are restored.
- Bundled mod worlds gain JSON-only `allowCheats` and `saveOnExit` controls. Setting
  `saveOnExit` to `false` suppresses world, chunk, entity, player, statistics, and
  advancement persistence without affecting ordinary worlds.
- Character and free-camera color dialogs now share the in-game picker with an
  editable hex field. Settings and directory lists scroll responsively with eased
  motion across small resolutions and GUI scales, and the active directory is
  visibly grayed out.

### 2.2.4bbs highlights

- Song discovery now keeps a compact metadata index and reuses unchanged entries,
  avoiding repeated chart/audio/directory parsing. Integrated singleplayer/LAN
  reloads are shared between client and server instead of scanning twice.
- Initial characters, character-change targets, stage art, and resolvable Lua
  images are prepared during the waiting screen. Plain PNGs and Sparrow atlases
  share bounded memory-only caches, reducing first-use stalls without copying
  graphics into a disk cache.
- The Psych-style health-bar outline now follows `healthBar.visible` and cannot be
  more opaque than `healthBar.alpha`, while direct `healthBarBG` controls remain
  available.
- Custom rating HUD scripts can read Blockified's configured position through
  `getProperty('rating.x')` and `getProperty('rating.y')` in the shared 1280x720
  Lua canvas, including non-FNF HUD styles and different GUI scales.

### 2.2.3bbs highlights

- The chart editor gains song/section time signatures, a metronome with volume
  and playback-rate sliders, zoom-aware colored Inst/player/opponent waveforms,
  transient markers, loop playback with pre-roll, three-tap BPM measurement,
  named bookmarks/comments, stem mute/solo, exact audio selection, and a native
  save-location chooser. Meter now drives gameplay measure accents and written-beat
  character/HUD bops without changing Psych-compatible note or BPM timing; the
  remaining analysis/playback tools do not alter gameplay.
- Psych-compatible character colors (`healthbar_colors`) now tint editor vocal
  waveforms, and character `vocals_file`/vocal-prefix fields resolve split
  `Voices-<prefix>.ogg` stems across Psych-family and Blockified layouts.
- Chart scrolling and song-selection scrolling now ease over 0.2 seconds instead
  of snapping, chart zoom adds grid rows rather than stretching them, and GUI
  scale no longer distorts editor layout or virtual-machine hitbox size.
- FNF-style global master-volume controls use `+`/`-` in gameplay and nearly all
  screens: a compact mouse-draggable top overlay animates in over 0.3 seconds,
  plays pitch-scaled vanilla clicks per percentage, ignores text entry, and stays
  out of Minecraft's pause menu.
- Final playstate restoration now reapplies participant game modes after player
  NBT and restores world time from a dimension-aware exit snapshot, covering
  normal finish, quit, give-up, cancellation, and restart paths reliably.
- Gameplay and Chart Editor playtest rollback also restores rain/thunder flags and
  clear, rain, and thunder transition timers after a song command changes weather.
  Weather is captured lazily, so natural weather progression is not rewound when
  the song never modifies it. Restart/reset and playtest rewind use the same path.

### 2.2.2bbs highlights

- Free-camera shot copying now compensates for Minecraft's detached third-person
  camera baseline, so pasted Camera Follow Pos values reproduce the authored
  position without changing free-camera movement, rotation, or zoom.
- Minecraft Command event `<player>` and `<opponent>` placeholders now use a
  player-only selector when that role belongs to a human, allowing player-only
  commands such as `/gamemode` and `/xp` while bots remain entity-selectable.
- A song snapshots each participant's game mode and restores it when gameplay
  finishes, quits, gives up, cancels, or restarts.

### 2.2.1bbs highlights

- The naked global mod (`config/fnfmod/mods/` root) is now a Psych-style shared
  asset base: loose `characters/`, `images/`, `stages/`, `sounds/`, and `music/`
  placed directly there fall through to every song, after the song's own pack so a
  pack's own assets always win. Available in all playback modes as trusted local
  content; a bundled mod world stays isolated to its owning pack.
- Fixed "Both" mode: when a Boyfriend note and a Dad note overlap in the same lane
  and merge into one hit, the stacked note now plays its own character's sing
  animation instead of scoring silently, so both characters animate on overlap.

### 2.2.0bbs highlights

- Global content folders now live inside `config/fnfmod/mods/` — `mods/animations`,
  `mods/scripts`, `mods/fonts`, and health icons at `mods/images/icons` — so the
  `mods/` folder is both the naked global mod and the named-pack container, matching
  Psych's layout. Existing installs auto-migrate on first launch; `songs`, `skins`,
  `splashes`, and `hitsounds` stay at the config root.
- A bundled world can force the player's settings while it is played: a
  `blockified-options.json` in the world folder shadows any subset of settings keys
  and locks them (grayed out) in-game, then restores the player's own values on exit.
  The forced values are never written back to `options.json`. Two JSON-only world
  controls are also supported: `allowCheats` enables/disables player commands, and
  `saveOnExit: false` runs the world without persisting world or player changes.
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
  abilities, health, food, weather changed by song commands, and other saved player state on finish, quit, loss,
  or disconnect.
- Chart and Lua commands use a copy-on-write world journal. Blocks and block
  entities changed synchronously by those commands return to their original
  state when play ends, without cloning entire chunks or overwriting unrelated
  world changes. Delayed mutations such as later TNT or creeper explosions are
  not currently part of this command transaction.

Required BBS character-animation stack for Minecraft 1.21.1:

- [BBS FS mod](https://modrinth.com/mod/bbs-fs) 2.3.1+
- [Sinytra Connector](https://github.com/Sinytra/Connector) 2.0.0-beta.15+
- [Forgified Fabric API](https://github.com/Sinytra/ForgifiedFabricAPI) 0.116.7+

BBS FS remains a Fabric jar; Connector and Forgified Fabric API translate it at
runtime for NeoForge. Keep all three as separate jars beside Blockified Engine;
they are part of the supported installation, not optional extras.

## What it does

- **Funkin' Machine block** (Functional Blocks creative tab). Right-click it to open the song menu.
- **Funkin' Designer item** (Functional Blocks creative tab). Right-click a
  machine to edit. Shift-right-click copies a profile, then shift-right-click
  another machine to apply it. Using the Designer on air, an ordinary block, or
  an ordinary entity opens its Machines/Blocks tool screen. Shift-use in air clears
  copied profiles and unfinished selections.
- **Chunk Loader Point block** (Designer only). It is invisible, non-colliding,
  and untargetable unless the Funkin' Designer is held. Arm placement from the
  Designer's Blocks tab, then right-click the point with the Designer to edit its
  persistent tag, 0-12 chunk radius, and enabled state.
- **Lightweight songs or complete mods**: use `config/fnfmod/songs/<song-name>/`
  for basic chart/audio entries, or `config/fnfmod/mods/<mod>/` for full creations.
- **Play as Both** merges both chart sides into the existing centered four-lane
  layout, while stage placement, performers, camera focus, and animation routing
  remain visually identical to **Play as Player**.
- **Note and volume keybinds** — Left, Down, Up, Right default to `D F J K`;
  Volume Up/Down default to `+` and `-`. All are rebindable in
  *Options → Controls → Blockified Engine*.
- **Multiplayer**: the first player to click the machine picks the song ("Play VS"), the second player to click joins as the opponent side. On servers, only songs installed **on the server** are playable — the server streams the chart + audio to players who don't have them (cached in `config/fnfmod/cache/`).
- **Dedicated-server safety**: remote sessions transfer only chart JSON, event
  JSON, and audio. Gameplay Lua and rich executable/mod assets are disabled;
  each song is limited to 100 MiB and every downloaded file is checked against
  its manifest size and SHA-1 before entering the cache. Singleplayer and LAN
  retain local Lua/mod behavior.
- **Chart editor**: `/fnf editor [song]` or the button in the song menu. Saves Psych Engine format.
- **Character editor**: open it from the song menu to create/edit named character
  JSONs such as `bf.json` and optional `bf-opp.json`, choose BBS forms and states, preview animations, and
  preview base/per-animation camera offsets without writing JSON manually. Its
  animation list accepts custom names in addition to the built-in FNF action slots.
- **Emergency song exit**: `Ctrl+Shift+Enter` always exits the active song. This
  recovery shortcut is intentionally fixed and cannot be rebound or disabled.
- `/fnf reload` reloads all FNF content without restarting. Targeted forms:
  `/fnf reload songs|skins|splashes|animations|icons|hitsounds|fonts|options|scores`.

The settings page calls external directories **Mods**. It always lists
`config/fnfmod/mods`, recognizes Psych-compatible `pack.json` names and
descriptions plus `pack.png`, and shows each direct child pack separately.
Added Psych `mods` roots can be expanded with the arrow beside them instead of
blending every child into one asset namespace. Paths display only their final
three folders; selecting a pack shows its icon/name/description in the left panel.
You may add either a complete mod folder directly or a container `mods` folder.
The bottom of that scrollable panel controls resource categories for the selected
source or individual child pack; click-drag paints several permissions on/off,
with bright buttons enabled and gray buttons disabled.

V-Slice/Polymod packs are recognized through `_polymod_meta.json` and
`_polymod_icon.png`. Their title, description, `mod_version`, license, and
contributors appear in Mods settings alongside Psych `pack.json` metadata.

The Song Menu's **Settings** tab has a small **C** button in the bottom-right for
Credits. The scrollable screen contains verified hardcoded Blockified, Psych
Engine, Funkin Crew, and Codename Engine credits, then imports installed V-Slice
contributors and Psych `data/credits.txt` entries. Imported names with valid HTTP(S)
links are clickable; hardcoded names deliberately are not.

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
  section focus, camera follow, separate game/HUD zoom, and time-signature-aware
  measure bumps. Psych
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

Psych chart-level `arrowSkin` and `splashSkin` are supported, plus Blockified's
`holdSplashSkin` extension. Their matching PNG/XML Sparrow atlases are resolved
from a complete active pack's `images/` folder. The chart editor's Data tab exposes
these as **Note Texture**, **Note Splash Texture**, and **Hold Cover Texture**.
Each value may name a PNG/XML stem or a folder pack; folder packs use the same
layouts as the user's note skins, hit splashes, and hold covers.

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
    screens/               <- optional extra Lua screens
      story.lua
      settings.lua
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

One machine menu can be split into multiple files. Keep the initial page as
`menu.lua`, put additional pages in `screens/<name>.lua`, then call
`machine.openScreen('name')`. `machine.getScreen()` returns the current page and
`machine.getScreens()` lists every discovered page. Widgets, tweens, timers, and
page callbacks reset during navigation; `machineData` and tagged `playSound`
audio stay alive, so looping menu music does not restart or cut between pages.
Use `machine.openScreen('main')` to go back to `menu.lua`. For example,
`machines/neon/screens/extras.lua` is opened from that profile with:

```lua
local extras = ui.button('extras', 'Extras', 0.5, 0.5, 180, 24)
function extras:onClick()
  machine.openScreen('extras')
end
```

Shared audio follows Minecraft's master-volume setting immediately. Use
`soundExists('menuMusic')` before starting a shared track when a page may reopen.
Physical keyboard input uses Psych-compatible `keyboardPressed(name)`,
`keyboardJustPressed(name)`, and `keyboardReleased(name)`. Menu pages may also
define `onKeyPress(keyName, keyCode, modifiers)` and
`onKeyRelease(keyName, keyCode, modifiers)`; callbacks do not repeat while a key
is held and keyboard Lua is suppressed while an editable text box owns input.

Mouse-wheel/trackpad input calls `onScroll(dy, dx)` anywhere in the menu. Positive
`dy` means up, negative means down; `dx` is horizontal scrolling. Fractions are
preserved. A widget can define `function widget:onScroll(dy, dx)`; only the topmost
visible object under the pointer with that handler receives it, followed by the
global callback. This works on 2D and projected 3D objects, including masks and
transforms used by their normal hit testing. Decorative objects without a scroll
handler do not block one underneath. Use either the global or widget callback for
the same action, not both. No layout moves automatically; Lua decides what moves:

```lua
local list = ui.label('list', 'Scroll me', 0.5, 360)
local targetY = 360
function onScroll(dy, dx)
  targetY = math.max(100, math.min(620, targetY + dy * 30))
  doTweenY('listScroll', 'list', targetY, 0.2, 'expoOut')
end
```

Polling alternative: `getMouseWheel()` / `cursor.scrollY` and `getMouseWheelX()` /
`cursor.scrollX` accumulate since the previous `onUpdate` and reset after the next
one (also on page changes/pause). Reading does not consume the delta. Do not
multiply it by `dt`. Menu Editor preview supports wheel Lua; edit-mode scrolling
still controls the editor panel/camera.

Machine menus use a fixed 1280x720 canvas, scaled uniformly and centered. Window
resolution and Minecraft GUI scale therefore do not change widget layout;
GUI scale also does not change physical widget size. Normalized X/Y values (`-1` through `1`) use canvas dimensions;
larger values are canvas pixels. Lua globals `screenWidth` and `screenHeight` are
always `1280` and `720`. Non-16:9 screens letterbox the canvas.

Every menu constructor can also render in Minecraft's 3D scene. Call
`ui.world(widget, x, y, z)` after construction, or set `widget.space = 'world'`.
World X/Y/Z are block offsets from the machine/automatic-menu origin; Y points up.
World buttons, toggles, sliders, and custom mouse handlers remain clickable through
projected bounds. `billboard`, `lighting`, `seeThrough`, `rotationX`, `rotationY`,
and `angle` control their world presentation.

```lua
local sign = ui.animatedSprite('sign', 'images/sign.png', 'images/sign.xml', 0, 0, 160, 80)
ui.world(sign, 2, 1.5, -3)
sign.billboard = false
sign.rotationY = 25

camera.setPosition(120.5, 68, -42.5)
camera.lookAt(124, 66, -38)
doTweenCamX('cameraX', 128, 0.8, 'expoOut')
doTweenCamRotationY('cameraYaw', 35, 0.8, 'expoOut')
doTweenCamZoom('cameraZoom', 1.35, 0.6, 'quadInOut') -- 1 = normal FOV
```

Camera Lua uses immediate `camera.setPosition`, `camera.setRotation`, `camera.lookAt`,
`camera.setZoom`, matching getters, and `camera.reset`. Tween individual axes with
`doTweenCamX/Y/Z`, rotate with `doTweenCamRotationX/Y/Z`, and tween FOV with
`doTweenCamZoom`. Their Psych-style arguments are `(tag, value, duration, easing)`;
completion calls `onTweenCompleted(tag)`. This menu camera is independent of
gameplay Lua's existing `setFOV` behavior.

Creator Tools includes **Menu Lua Editor** for profiles in the current mod. Its
viewport reserves a uniformly scaled 1280x720 canvas while its tabbed panel edits
screen/world position, size, rotation, layers, effects, parenting, tweens, and the
Minecraft camera. Click or Shift-click objects/layers to select one or several;
G/R/S transforms them, Shift gives precision, right-drag + WASD/Q/E flies the
viewport camera, Numpad Decimal frames a selected 3D object, and MMB orbits. Save
updates a marked generated block inside `menu.lua`; handwritten callbacks and code
outside that block remain untouched.

A bundled world's `blockified-options.json` may use:

```json
{
  "autoOpenMenu": true,
  "autoMenuProfile": "my-mod:neon",
  "forcePsychResolution": true
}
```

Escape on any custom Lua menu page opens Minecraft's pause menu directly, without
a Blockified exit dialog. Returning restores the same Lua page and shared audio;
Escape does not navigate from a secondary page to `menu.lua`. Lua can call
`machine.exitWorld()` to leave the world immediately without a confirmation prompt.
This uses the normal disconnect/save lifecycle (including the world's existing
save rules), not a forced game shutdown. It returns false in the Menu Editor,
where world exit is disabled to protect editing.

The automatic menu opens on entry, uses a virtual machine session, and cannot be
closed while enabled. The fixed-canvas option is also available in vanilla Video
Settings and is injected into Sodium/Iris replacement video screens when present.
It keeps a centered 16:9 Psych-shaped game rectangle with opaque black letterboxing;
rendering remains at the window or monitor's native resolution instead of becoming
a pixelated 720p framebuffer, and the user's GUI scale is left unchanged.

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

Psych `weeks/*.json`, `weekList.txt`, story-menu images, difficulties,
`weekCharacters`, visibility flags, and `weekBefore` are discovered from installed
packs. The built-in Story Mode tab shows each week as a list with its image, songs,
and combined saved score. Playback follows the declared order from the first song
through the last; incomplete weeks are not launched. Press **8** in the Song Menu and open **Week Maker** to
create/preview a compatible week. Lua can inspect the same data:

```lua
for _, week in ipairs(machine.getWeeks()) do
  -- week.id/name/storyName/weekName/background/weekBefore/image
  -- week.difficulties, week.characters, week.songs
end

local week = machine.getWeek('week1') -- nil when unavailable
if machine.hasWeek('week1') then print(week.songs[1].id) end
```

`machine.playSong(id, difficulty)` also works with solo/player/Minecraft defaults.
Positional form is `machine.playSong(id, difficulty, duet, playAs, look)`.
Invalid songs/difficulties stay in menu and show error. Server revalidates every
launch. `machine.openSongDetails(id)` opens built-in options for one song.
By default, the song uses the machine that opened this menu: a normal machine,
hitbox anchor, hitbox-less virtual machine, or automatic world-menu origin.
To use a different hitbox-less virtual machine, set the `machine` option to its
Designer tag/ID. You do not need to click that anchor or give it a custom profile:

```lua
function play:onClick()
  machine.playSong('earrings', 'normal', {
    machine = 'earrings_stage', -- tag assigned to the virtual machine with the Designer
    returnTo = 'menu'
  })
end
```

Gameplay uses the target's Minecraft position and facing; `returnTo = 'menu'`
reopens the original launching menu (its main page), not the target anchor's
profile. `returnTo = 'world'` still closes menus after returning the player from
the stage; `selector` opens the original machine's built-in selector. With
`autoOpenMenu`, the world's required menu can reopen after a `world` exit as usual.
All other options (`duet`, `playAs`, `look`) keep working. Omitting `machine`, or
using an empty string, keeps the existing current-machine behavior. `machine.id`
and `machine.tag` still describe the menu's own origin; they do not change when a
button targets another stage.

Tags are case-insensitive, unique within the current dimension, and use up to
64 letters/numbers/underscores/hyphens. Missing, removed, ambiguous, or busy targets
show an error above the menu and do not launch at a fallback location. A true
`playSong` result means a request was sent; server validation is asynchronous.
Tagged launches are for singleplayer/LAN mod worlds, like custom menu Lua itself.
The tag index is saved with the world, so known anchors can be found in unloaded
chunks. Existing anchors from older builds register when their chunk next loads;
visit them once if a tag is not found after updating. Only a small temporary stage
chunk ticket is held while preparing/playing, then released when the session ends.

Navigation/API calls:

- `machine.openSongSelect([returnTo])` — existing complete selector; optional
  `menu`, `world`, or `selector` controls where its next solo song exits.
- `machine.openSettings()` / `machine.openOptions()` — settings; Back returns to Lua menu.
- `machine.openCharacterEditor()` — character editor; Back returns to Lua menu.
- `machine.openChartEditor([songId], [difficulty])` — chart editor.
- `machine.join()` — join waiting LAN session; alias of selector/session interaction.
- `machine.saveData()` — persist `machineData` immediately.
- `machine.close()` — close menu and release machine session; blocked by `autoOpenMenu`.
- `machine.exitWorld()` — leave this world without a prompt; true when queued,
  false if unavailable, already requested, or running inside the Menu Editor.
- `machine.setSongExitTarget('menu'|'world'|'selector')` — choose where solo
  gameplay returns after finish, quit, or loss. Direct custom-menu songs default
  to `menu`; `world` closes every menu. The target also survives a handoff through
  `machine.openSongSelect()`.

Closing the menu or leaving the world releases chooser ownership; opening the
pause menu preserves it. Lua menu
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

#### Chunk loader points

Use the Funkin' Designer's **Blocks → Chunk Loader** tool to place points anywhere
a stage, moving performer, command, or gameplay camera may reach. Each enabled
point persistently keeps a square of
ticking chunks loaded around itself; radius 0 loads only its own chunk, radius 2
loads a 5x5 region, and the safety cap is radius 12. Overlapping points have
independent tickets, so disabling or breaking one never unloads chunks still
owned by another point.

Like Minecraft's Light Block, a point has no collision, model, outline, or target
shape during ordinary play. Hold the Funkin' Designer to reveal its marker and the
exact chunk boundary; disabled points render red. Right-click it with the Designer
to open its in-game tag/radius/on-off editor.

Points work in singleplayer and LAN worlds, restore their tickets after reopening
the world, and do not alter gameplay camera coordinates. Gameplay Lua can read or
temporarily modify a tagged point; changes made by a song join the normal rollback
transaction and return to their pre-song values on finish, quit, loss, or restart:

```lua
setProperty('chunkLoadPoints.stage_right.enabled', true)
setProperty('chunkLoadPoints.stage_right.radius', 4)
setProperty('chunkLoadPoints.stage_right.tag', 'second_stage')

local active = getProperty('chunkLoadPoints.second_stage.enabled')
local radius = getProperty('chunkLoadPoints.second_stage.radius')
```

Tags use lowercase letters, numbers, `_`, or `-` and must be unique in that
dimension. Coordinates are readable as `.x`, `.y`, and `.z`. Persistent chunk
loading and authoring are disabled on dedicated servers.

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

Folder-style definitions can share a single BBS asset tree instead of copying
the same models and textures into every animation folder. Add
`assets_path.txt` beside `character.json`; its first non-comment line is a path
relative to the complete mod root, using the same forward-slash style as Lua:

```text
animations/anim_assets/idk
```

For example, `animations/Earrings/assets_path.txt` can point at
`animations/anim_assets/idk`, whose contents may include `models/`, texture
folders, and every other file referenced as `assets:...` by
`character.form.json`. The normal assets stored inside `animations/Earrings`
still work and override shared files with the same path. Existing definitions
without this text file are unchanged. The path must stay inside its owning mod.
Flat `animations/name.json` definitions can use the sibling file
`animations/name.assets_path.txt`.

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
setProperty('backup.rotation.x', 20)
setProperty('backup.rotation.z', -10)
setProperty('backup.scale.x', 1.25)
setProperty('backup.scale.y', 0.8)
setProperty('backup.scale.z', 1.5)
removeBlockifiedCharacter('backup')
```

When a BBS form is active, Lua and free cam can tilt both extra characters and
the main `boyfriend`/`dad` performers on all three axes. `rotation.x` is pitch
and `rotation.z` is roll. The existing `rotation`, `rotation.y`, `angle`,
`setBlockifiedCharacterRotation`, and Add/Tween Character rotation values keep
their original yaw behavior. In free cam, plain **R** therefore still changes
yaw; use **R X**, **R Z**, or trackball rotation for the new axes. These X/Z
values are render-only and do not rotate player physics, movement, or camera.
Active BBS forms also support independent `scale.x`, `scale.y`, and `scale.z`
on extra characters and the main performers. Plain **S** in free cam scales all
three axes uniformly; **S X**, **S Y**, and **S Z** constrain one axis, while
Shift plus an axis scales the other two. **Alt+S** restores unit scale. BBS scale
is render-only and does not resize the Minecraft entity or its collision box.

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

Directory scans use a persistent metadata index at
`config/fnfmod/cache/song-library-index-v1.json`. Blockified fingerprints relevant
chart, audio, character, and icon files by path, size, and modification time, then
reuses unchanged song metadata instead of parsing every chart again. The index is
rebuilt automatically after files, directory order, resource filters, or the active
mod-world scope change. In integrated singleplayer/LAN hosting, a client-initiated
reload is also shared with the server side of the same game process instead of
scanning the directories twice. Clearing the song cache removes this index; it is
created again on the next scan.

Gameplay PNG optimization is memory-only: Blockified discovers initial characters,
characters named by `Change Character`/`Add Character`, stage graphics, and resolvable
Lua image references while the pre-song waiting screen is open. PNG decoding and
Sparrow XML parsing run in the background, then GPU upload finishes before the client
reports ready. Identical character/Lua/stage graphics share reference-counted textures
and parsed frames. Unused graphics stay in bounded RAM/VRAM LRU caches (256 MiB for
atlases and 128 MiB for plain images) and are cleared on disconnect. PNG/XML files are
never copied into the disk cache.

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

`config/fnfmod/mods/scripts/*.lua` contains naked-mod global scripts. They run for
every locally played song, including lightweight `config/fnfmod/songs/<song>`
entries. Pack-scoped song scripts belong in `mods/<pack>/data/<song>/` or
`mods/<pack>/songs/<song>/`; lightweight song folders still cannot provide their
own Lua. Global scripts obey the installed Mods Lua permission, remain excluded
from isolated mod worlds, and never run in dedicated-server sessions.

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

Psych note callbacks keep their chart-character meaning in every play mode:
`goodNoteHit` is a BF/player-role note and `opponentNoteHit` is a Dad/opponent-role
note. When **Play as Opponent** is selected, the user therefore controls the notes
that call `opponentNoteHit`; BF notes continue to call `goodNoteHit`. `mustPress`,
`playerStrums`, `opponentStrums`, `boyfriend`, `dad`, and `mustHitSection` retain
the same chart-role meaning. Lua `health` and `healthBar.percent` also remain BF-role
values, while scoring, misses, input, and the fail state follow the side being played.
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
`playAnim`, `objectPlayAnimation`, and `luaSpritePlayAnimation` keep Psych's
non-forced default when their `forced` argument is omitted. All three calls
resolve an exact Lua-object tag before built-in
character aliases, so names such as `back_bf` and `back_gf` remain ordinary sprites.
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
state up to that point. **F12** opens an automatic notes-only preview using the
same stable 24 FPS tap/hold animation model as Note Settings; tap confirms play
once, sustain confirms loop until their tails, and consumed sustain pieces do not
remain behind the receptors.

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
lock the sprite to the stage direction. Gameplay Lua and menu Lua world objects
support three shader-facing render modes:

```lua
setObjectRenderMode('worldSign', 'lit')      -- entity lighting and shader shadows
setObjectRenderMode('worldSign', 'flat')     -- full-bright, no directional shading
setObjectRenderMode('worldSign', 'emissive') -- glow/emissive entity material
```

`setWorldSpriteRenderMode` and `setObjectShaderMode` are aliases. Setting
`tag.renderMode` or `tag.shaderMode` works too. `setWorldSpriteLighting(tag,
false)`, `tag.lighting = false`, and `tag.unlit = true` remain compatible and
now select the shader-friendlier `flat` path instead of treating every unlit
object as emissive. Shader packs control their own materials, so `lit` is the
best choice when an object must participate in pack lighting/shadows, while
`flat` is the predictable choice when its texture color must remain unchanged.

Minecraft's directional face lighting is controlled separately from full-bright
object lighting through the built-in `Directional Shading` event. Value 1 controls
blocks/fluids and Value 2 controls mobs/entities (`on` keeps vanilla, `off` is
flat). Flat block shading also disables ambient occlusion and rebuilds visible
chunks. World light levels remain active, entity shadow blobs remain independent,
and both values restore on exit. Lua uses the same event path and can read the
current states:

```lua
triggerEvent('Directional Shading', 'off', 'off')
local mobsEnabled = getMobShading()
local blocksEnabled = getBlockShading()
```

During gameplay, world sprites, graphics, animated spritesheets, and 2D world
characters use client-side Minecraft entity hosts. Free-cam previews remain
direct editor renders. Gravity, block/entity collision, and Minecraft's blob
shadow are disabled by default for these flat entities and can be changed with
Lua. The Psych/FNF-style stage coordinates follow the entity while physics is
active, so `getProperty(tag .. '.x/y/z')` reports its current position.
The hosts use IRLights' supported item-entity caster path, allowing these flat
visuals to cast shadows from IRLights point lights and spotlights when that mod
and its patched shaderpack are active. These projected shadows default on and
are independent from Minecraft's blob shadow.

```lua
setProperty('worldSign.gravity', true)
setProperty('worldSign.collision', true)
setProperty('worldSign.shadow', true)

-- Equivalent convenience functions:
setWorldSpriteGravity('worldSign', true)
setWorldSpriteCollision('worldSign', true)
setWorldSpriteShadows('worldSign', true)

-- IRLights projected shadows (enabled by default):
setWorldSpriteIRLightsShadows('worldSign', false)
setProperty('worldSign.irlightsShadows', true)

-- 2D characters in 3D space use the same projected-shadow switch:
setProperty('gf.irlightsShadows', false)
setCharacterIRLightsShadows('gf', true)
```

Character packs can control whether players may replace a compatible BBS
Steve/Alex form with their selected Minecraft skin/model. In the Character
Editor, open **Skin / Model Rules**. It controls whether player overrides are allowed
and whether the authored form uses its own skin, the performer skin, or a named
Minecraft account's skin and slim/wide model. The permission writes
`"allowPlayerSkinSelection": true/false` to the role's character JSON and
defaults to `true` for older packs. When locked, the Player/Bot Skin control in
the animation selector is disabled and gameplay keeps the form author's choice.
Both Player and Bot animation selectors can independently use the form skin, current
player skin, a PNG file with Slim/Wide selection, or a Minecraft account name. The
account dialog remains open with a loading indicator until the skin is resolved.
When a Minecraft skin is selected, BBS main-model texture keyframes no longer
cover that skin; pose and non-skin animation keyframes continue normally.

## Note skins (Sparrow XML!)

Drop a Friday Night Funkin' spritesheet straight from any FNF mod into:

```
config/fnfmod/skins/<skin-name>/NOTE_assets.png
config/fnfmod/skins/<skin-name>/NOTE_assets.xml
```

Each subfolder is a selectable skin — pick one in Settings → Note Settings.

The selector also has two built-in choices:

- **Default (chart)** uses the active chart's `arrowSkin` and `splashSkin` from
  its complete mod pack. Missing chart assets fall back to procedural notes.
- **None (procedural)** ignores chart note/splash textures and always uses
  Blockified Engine's generated arrows.

The Adobe Animate / Sparrow `<TextureAtlas><SubTexture .../>` XML is parsed,
including `frameX/frameY` trim offsets. Base-game and Psych naming conventions
are recognized (`purple0000`, `arrowLEFT`, `left confirm`, `purple hold piece`, ...).
Named skin folders override the chart's default note and splash textures.

Each note skin decides whether its artwork uses Psych-style RGB-template recoloring
with `"rgb": true` or `"rgb": false` in its skin JSON. Settings → Note Settings →
**Skin Uses RGB** edits that value. When enabled, the configured lane colors are
always applied; there is no separate global RGB switch. The chart editor no longer
stores this choice in song data. Imported Psych `disableNoteRGB` fields remain
readable for round-trip compatibility but do not override the skin JSON. All three
Psych template channels are configurable per direction: red is **Primary**, green
is **Secondary** (white by default), and blue is **Outline**. Existing user configs
automatically receive white secondary colors.
The same Note Settings page has a temporary **Preview UI: Normal/Pixel** control;
it previews the selected skin's Pixel UI artwork without changing chart data.

### Splash packs

Splash selections support the same folder-pack layout as note skins:

```text
config/fnfmod/splashes/<pack-name>/noteSplashes.png
config/fnfmod/splashes/<pack-name>/noteSplashes.xml
config/fnfmod/splashes/<pack-name>/pixelUI/noteSplashes.png
config/fnfmod/splashes/<pack-name>/pixelUI/noteSplashes.xml
```

The atlas files may instead use the pack folder's name, `splash`, or any matching
PNG/XML stem. Pixel UI automatically chooses the pair inside `pixelUI`; without
one, the normal atlas remains as a nearest-filtered fallback. Existing flat pairs
directly inside `config/fnfmod/splashes` remain supported. A note-skin folder can
bundle its own normal and pixel splashes using the same `noteSplashes` paths.

Hold-cover files are intentionally separate from hit-splash packs. A
`splashes/<pack-name>/` subfolder cannot provide a hold cover; files named
`holdSplash`/`holdCover` there are ignored by the hit-splash selector.

Install user-selectable hold-cover packs only under:

```text
config/fnfmod/splashes/holdSplashes/holdSplash.png
config/fnfmod/splashes/holdSplashes/holdSplash.xml
config/fnfmod/splashes/holdSplashes/pixelUI/holdSplash.png
config/fnfmod/splashes/holdSplashes/pixelUI/holdSplash.xml

config/fnfmod/splashes/holdSplashes/<pack-name>/holdSplash.png
config/fnfmod/splashes/holdSplashes/<pack-name>/holdSplash.xml
config/fnfmod/splashes/holdSplashes/<pack-name>/pixelUI/holdSplash.png
config/fnfmod/splashes/holdSplashes/<pack-name>/pixelUI/holdSplash.xml
```

Flat named pairs such as `holdSplashes/my-cover.png/.xml` and freely named
matching PNG/XML pairs inside a pack folder also work. Choose one with
Settings → Note Settings → **Hold Cover**. **Default (chart)** uses the chart's
`holdSplashSkin`, then Psych's standard
`images/noteSplashes/holdSplashes/holdSplash` asset, then the root default shown
above. **OFF** suppresses generic hold covers; a cover bundled by the active note
skin belongs to that skin and takes priority.

Its `start` animation plays once when holding begins, `hold` loops during the
sustain, and `end` plays after a clean finish. RGB-template artwork follows the
skin JSON's `rgb` value. Legacy
per-skin `holdCover<Color>` atlases remain supported and take priority over a
separately chosen chart or settings hold-cover pack.

Notes, receptors, sustain bodies, and sustain ends use one shared source-pixel
scale. Their authored frame resolutions therefore determine their relative size;
for example, a 114 px sustain beside a 152 px note renders 114/152 as wide.
This applies to both Sparrow XML and XML-less Pixel UI sheets.

Optional note-skin JSON values can override the natural result by adjusting each
part's scale, opacity, and position. Scale values are multipliers applied after
resolution-based sizing, so old compensating values may need to be reset to `1.0`.
Position values are offsets in the part's source-art pixels. They scale with the
part, resolution, and vanilla GUI scale, preserving the same relative alignment.
Negative X moves left and negative Y moves up.

Hold covers use a corrected internal neutral baseline equivalent to the old
`holdCoverScale: 1.7` and `holdCoverY: 35` workaround. Their editable JSON values
therefore remain intuitive: scale `1.0` and position `0, 0` mean the corrected
default. Existing files containing that exact workaround are normalized to these
neutral values when loaded and saved.

```json
{
  "rgb": true,

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

Settings → **Note Settings** provides both gameplay-style strumlines on the
left and all note options on the right. It can load a chart from an installed
mod, play that chart's instrumental/vocals, and preview its difficulty, Pixel UI,
notes, sustains, hit splashes, and hold covers. The Note Skin, Splashes, and Hold
Cover selectors live here instead of Visuals and UI. Its centered chart picker
uses the same icon-card grid, search, scrollbar, and eased scrolling as the song
selector. The strumline box also acts as a small song player: restart, five-second
rewind/forward, play/pause, and a draggable timeline seek the loaded song. Hold
Shift while dragging the timeline for one-fifth-speed precision without snapping
when Shift is pressed or released mid-drag.
Click a visible note or either matching receptor to choose the direction whose
colors are being edited; the selected direction is outlined. Base, Highlight,
and Outline are separate color-part buttons.

Open **NoteSkin Editor** from Creator Tools to adjust Scale, Alpha, X, and Y for
notes, receptors, sustains, splashes, or hold covers, then use the fixed **Apply**
button beside **Back**. It intentionally does not duplicate Note Settings' lane
color picker or delay control: the preview uses those saved user settings so it
shows how the authored skin will look in gameplay. **Save To** can update the
skin's original path, export a selected mod folder using Psych's `images/noteSkins`,
`images/noteSplashes`, and `images/pixelUI` layout, or install the complete pack
into Blockified's `config/fnfmod/skins` and `config/fnfmod/splashes` folders.
Folder exports copy the required normal and pixel note, splash, and hold-cover
assets alongside the edited JSON. **Apply** writes or exports the active skin,
keeps the selected note assets as user options, and leaves the screen open.
**Back** discards only changes made since the latest Apply.
New configs use the active PNG's basename (for example,
`NOTE_assets.png` saves as `NOTE_assets.json`). If a skin already has a JSON,
Blockified preserves that file's current name. Splash and
hold-cover transforms always come from the active note skin's JSON, even when the
effect atlas is stored in a separate splash/hold pack or selected by the chart.
When a note skin bundles its own splash or per-lane hold-cover artwork, that skin
owns the effect: the corresponding selector is disabled and displays **From note skin**.

## Character animations (BBS FS) + animation sets

Use **Character Editor** in the Funkin' Machine song selector to edit the global
files visually. The set and role buttons cycle named JSONs and player/opponent data;
the form/action buttons cycle BBS forms and FNF actions. **Preview State** plays the
currently entered BBS state on the model, while the red cross shows the selected
base plus action camera offset. **Save** writes the same JSON format documented below.

The separate **Player Anims** and **Opponent Anims** settings have two built-in
choices:

- **None** disables BBS character animation control.
- **Default (song)** uses the active chart's character IDs. For example, a chart
  whose `player1` is `bf` and `player2` is `dad` uses the active mod's
  `animations/bf.json` and `animations/dad.json`. Global `default.json` (or the
  legacy global `character.json`) is the fallback.

Both animation selectors list only character JSON definitions found directly
in `config/fnfmod/mods/animations/`. It never lists raw BBS forms or definitions from
installed mods. **Player Anims** always controls the chart's BF/player role and
**Opponent Anims** always controls the Dad/opponent role. When playing as the
opponent, the local Minecraft performer therefore uses Opponent Anims and the
solo BF bot uses Player Anims. The same role mapping is sent correctly in
multiplayer. `idle2` remains opt-in through a JSON definition; when mapped,
`idle` and `idle2` alternate every beat. Existing playerAnimator/Emotecraft
animation files are not compatible with BBS FS and are ignored.

**Animation definitions:** each `config/fnfmod/mods/animations/<name>.json` is a
user-selectable mapping. A complete pack can privately provide
`mods/<pack>/animations/<character>.json`; those names are available to that
pack's chart and **Change Character** events but cannot be selected in Settings.
The old `animations/<name>/character.json` layout is still read for compatibility,
but new files and Character Editor saves use the named-file layout.
Pick each chart role in the Visual settings. In VS mode the BF participant uses
Player Anims and the Dad participant uses Opponent Anims. Selecting **None** remains an explicit opt-out, so
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
- **Camera Rotation 3D** uses Values 1/2/3 for additive pitch/yaw/roll, Value 4
  for easing, Value 5 for duration in seconds (blank keeps the original 0.5s), and
  Value 6 for the rotation layer. Blank/`normal`/`orbit` retains the original layer;
  `camera`/`view`/`local` selects an independent view-only layer.
  Leave all three rotations empty to return to the normal view. While **Camera
  Orbit** is active, the original layer animates camera position around its pivot,
  while the view-only layer continues rotating the camera itself. The two layers
  retain independent tween state. Camera Orbit itself only enables/disables and positions that pivot.
  Value 2 selects a focus-following or pinned pivot, and Value 3 supplies all-axis
  `X,Y,Z` coordinates: focus-relative offsets when following, absolute stage-local
  coordinates when pinned. Values 4/5 set the duration and easing used when an
  active orbit moves to a new pivot. A focus-based orbit smoothly moves its pivot
  when Camera Focus or a section changes target, following the active Camera
  Behavior speed and easing.
  Camera Behavior Value 3 controls animation/sing camera offsets (`true` by
  default; use `false` for a stable camera and orbit pivot).
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
- Beat Snap supports 4th–64th subdivisions and squishes/stretches boxes without
  changing the timeline dimensions; Grid Zoom independently adds or removes
  fixed-height boxes. The Chart tab's **Charting Offset** is an editor-only
  adjustment added to the Song tab offset for playback, waveforms, metronome,
  and chart alignment. It is stored in `options.json`, never in the chart; only
  the Song offset affects saved charts and gameplay. Under **Edit**, use
  **Choose Saving Folder...** to remember an output folder for the current song
  name. Ctrl+S then overwrites that folder's chart JSON and `events.json`. Mappings
  for every chart live together in `config/fnfmod/chart_editor_save_folders.json`.
  With no mapping, Ctrl+S writes the normal song-name folder (and
  `original_directory.txt` when required) without opening a dialog. **Save As**
  chooses and remembers another folder. If a remembered folder disappears, the
  editor warns on open, clears it, and returns Ctrl+S to the normal folder.

## Gameplay options

In the song select screen: **Play as** (Player / Opponent / Both — solo only;
Both puts you center stage playing every note, and one keypress hits overlapping
notes on both strumlines), Downscroll, Ghost Tapping. More in
`config/fnfmod/options.json` (`offsetMs` for audio calibration, `scrollSpeedMult`).
Audio delay is adjustable directly in Note Settings with live song/chart feedback.
Use the slider for broad changes or its `-`/`+` buttons for fine tuning: click for
1 ms, or Shift-click for 8 ms.
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

## License

Blockified Engine is distributed under the [MIT License](LICENSE). The bundled
LuaJ runtime retains its own MIT notice in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); both notices are included in
release JARs under `META-INF`.
