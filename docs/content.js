(function () {
  "use strict";

  const esc = (value) => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
  const code = (language, value) => `<pre data-language="${language}"><code>${esc(value)}</code></pre>`;
  const section = (id, title, body) => `<section class="doc-section" id="${id}"><h2>${title}</h2>${body}</section>`;
  const api = (signature, description, kind = "Blockified") => `<div class="api-entry"><div class="api-signature"><code>${esc(signature)}</code><span class="kind">${kind}</span></div><p>${description}</p></div>`;
  const callout = (title, body) => `<aside class="callout"><div class="callout-title">${title}</div><p>${body}</p></aside>`;
  const page = (title, eyebrow, description, tags, ...body) => ({ title, eyebrow, description, tags, body: body.join("") });

  const groups = [
    { title: "Start", pages: ["home", "install", "scope"] },
    { title: "Content", pages: ["songs", "mod-packs", "mod-worlds", "machines", "machine-hitboxes"] },
    { title: "Gameplay", pages: ["note-skins", "custom-notes", "events", "camera", "rollback", "multiplayer"] },
    { title: "Lua", pages: ["lua-overview", "lua-blockified", "lua-world", "lua-callbacks", "machine-lua", "lua-compatibility"] },
    { title: "Tools", pages: ["chart-editor", "free-camera", "commands", "formats"] }
  ];

  const pages = {};

  pages.home = page("Blockified Engine", "Official documentation", "Build Friday Night Funkin' songs, stages, machines, and world scenes inside Minecraft.", ["2.1.0bbs", "NeoForge 1.21.1", "Singleplayer + LAN"],
    `<div class="hero-panel"><span>DOCUMENTATION SCOPE</span><h2>Blockified additions, in one place.</h2><p>This reference covers features created or changed by Blockified. Compatible Psych Engine behavior is linked, not copied.</p><div class="hero-actions"><a class="button-link" href="#/install">Install</a><a class="button-link secondary" href="#/lua-overview">Lua API</a></div></div>`,
    section("choose", "Choose a path", `<div class="card-grid">
      <a class="doc-card" href="#/songs"><span>CONTENT</span><h3>Add a song</h3><p>Use a lightweight song folder or complete pack.</p></a>
      <a class="doc-card" href="#/machines"><span>WORLDS</span><h3>Build a machine</h3><p>Texture it, define hitboxes, and script its menu.</p></a>
      <a class="doc-card" href="#/lua-blockified"><span>REFERENCE</span><h3>Use Blockified Lua</h3><p>World objects, commands, cameras, and characters.</p></a>
      <a class="doc-card" href="#/events"><span>CHARTS</span><h3>Author events</h3><p>Control Minecraft and world scenes from charts.</p></a>
    </div>`),
    section("models", "Two content models", `<div class="table-wrap"><table><thead><tr><th>Model</th><th>Location</th><th>Best for</th></tr></thead><tbody>
      <tr><td>Lightweight song</td><td><code>config/fnfmod/songs/&lt;song&gt;/</code></td><td>Charts, audio, metadata, events</td></tr>
      <tr><td>Complete mod pack</td><td><code>config/fnfmod/mods/&lt;mod&gt;/</code></td><td>Lua, art, machines, fonts, worlds</td></tr>
    </tbody></table></div>`),
    section("boundaries", "Important boundaries", `<ul><li>Rich pack assets activate only in that pack's bundled world.</li><li>Custom machine editing and Lua menus target singleplayer and LAN, not dedicated servers.</li><li>Unchanged Psych APIs are not duplicated here.</li></ul>`)
  );

  pages.install = page("Install and run", "Getting started", "Requirements, optional animation support, and first launch.", ["NeoForge", "Minecraft 1.21.1"],
    section("requirements", "Requirements", `<ul><li>Minecraft Java Edition 1.21.1.</li><li>NeoForge for 1.21.1.</li><li>The Blockified Engine JAR in the instance <code>mods</code> folder.</li></ul>`),
    section("bbs", "Optional BBS animation stack", `<p>BBS character animation can use BBS FS 2.3.1+, Sinytra Connector 2.0.0-beta.15+, and Forgified Fabric API 0.116.7+.</p>${callout("Optional", "Standard Blockified song playback does not require the BBS stack.")}`),
    section("launch", "First launch", `<ol><li>Start once so <code>config/fnfmod</code> is created.</li><li>Add songs under <code>songs</code>, or complete packs under <code>mods</code>.</li><li>Use <code>/fnf reload all</code> after live content edits.</li></ol>`),
    section("presentation", "Presentation modes", `<p>Minecraft, FNF, and Legacy modes change stage/camera presentation without requiring a different chart format.</p>`)
  );

  pages.scope = page("Documentation scope", "Read this first", "What this site includes and what remains upstream behavior.", ["Blockified-only"],
    section("included", "Included", `<ul><li>Blockified files, events, APIs, machines, editors, and multiplayer behavior.</li><li>Changes Blockified makes to compatible Psych events or Lua calls.</li><li>Compatibility gaps and Minecraft coordinate rules.</li></ul>`),
    section("upstream", "Not duplicated", `<p>General Psych Engine Lua, chart basics, and unchanged asset behavior are not rewritten. Use the <a href="https://shadowmario.github.io/psychengine.lua/" target="_blank" rel="noreferrer">Psych Engine Lua documentation</a>, then return here for Blockified differences.</p>`),
    section("truth", "Source of truth", `<p>This site follows the current repository release. Report disagreements with a minimal mod/song, log, and reproduction steps.</p>`)
  );

  pages.songs = page("Songs", "Content", "The smallest way to add playable charts and audio.", ["Lightweight", "No Lua"],
    section("layout", "Folder layout", code("text", `config/fnfmod/songs/my-song/
  my-song.json
  Inst.ogg
  Voices.ogg
  events.json
  metadata.json`)),
    section("allowed", "What belongs here", `<p>Lightweight folders accept charts, OGG audio, events, and metadata. Images, Lua, stages, note types, characters, and machines belong in a complete pack.</p>`),
    section("reload", "Reload", api("/fnf reload songs", "Rebuilds the song library without restarting Minecraft.", "Command")),
    section("upgrade", "When to use a complete pack", `<p>Use <code>config/fnfmod/mods/My-Mod/</code> when content needs Lua, art, fonts, characters, custom notes, machines, or a bundled world.</p>`)
  );

  pages["mod-packs"] = page("Complete mod packs", "Content", "Self-contained mods with songs, scripts, art, machines, and worlds.", ["Rich assets", "Lua"],
    section("layout", "Pack layout", code("text", `config/fnfmod/mods/My-Mod/
  pack.json
  data/              songs/
  scripts/           stages/
  custom_events/     custom_notetypes/
  animations/        characters/
  images/            sounds/            music/
  machines/          fonts/             weeks/
  worlds/`)),
    section("metadata", "Pack metadata", `<p><code>pack.json</code> identifies the pack. Keep paths relative and case-exact for portability.</p>`),
    section("activation", "World-scoped activation", `<p>A pack's rich assets are available while the player is inside a world bundled by that pack. Unrelated worlds do not inherit them.</p>`),
    section("library", "Song-library compatibility", `<p>Compatible chart/audio content can remain globally discoverable. World machines, menu Lua, and authoring stay within supported singleplayer/LAN sessions.</p>`)
  );

  pages["mod-worlds"] = page("Bundled worlds", "Content", "Tie a Minecraft world to one complete Blockified pack.", ["World-scoped assets"],
    section("location", "Location", `<p>Place worlds under <code>config/fnfmod/mods/My-Mod/worlds/</code>. Blockified resolves the owning pack from the active world.</p>`),
    section("scope", "Asset scope", `<div class="table-wrap"><table><thead><tr><th>Location</th><th>Pack assets</th></tr></thead><tbody><tr><td>Pack's bundled world</td><td>Available</td></tr><tr><td>Another local world</td><td>Unavailable</td></tr><tr><td>Dedicated server</td><td>Machine authoring/menu Lua disabled</td></tr></tbody></table></div>`),
    section("reason", "Why", `<p>World scoping prevents unrelated saves from inheriting machines, textures, scripts, and menus only because a pack exists in the same instance.</p>`)
  );

  pages.machines = page("Custom machines", "World content", "Reskin the Funkin' machine and replace its selector with a Lua menu.", ["Singleplayer", "LAN", "Lua UI"],
    section("files", "Machine assets", code("text", `config/fnfmod/mods/My-Mod/machines/<machine-id>/
  machine.json
  menu.lua
  textures/
  images/`)),
    section("texture", "Textures", `<p>The machine definition selects art from the owning mod. Keeping it in the mod makes texture availability follow bundled-world scope.</p>`),
    section("interaction", "Interaction", `<p>Right-click opens the menu. Crouch-right-click with the Funkin' Designer edits or copies configuration. Virtual hitboxes open the same menu.</p>`),
    section("menu", "Lua menu", `<p><code>menu.lua</code> can create controls, images, static sprites, and Sparrow XML animation. It can open built-in screens or start a song directly.</p><a class="button-link" href="#/machine-lua">Machine Lua reference</a>`),
    section("server", "Server boundary", callout("Singleplayer and LAN", "Custom machine editing and menu Lua are intentionally excluded from dedicated servers."))
  );

  pages["machine-hitboxes"] = page("Virtual machine hitboxes", "World content", "Invisible non-colliding regions that act like a machine.", ["Entity hitbox", "Funkin' Designer"],
    section("model", "How they work", `<p>A hitbox is an entity-backed volume, not a block. It supports fractional space and non-full blocks. Each group points to a temporary machine anchor containing facing, location, and machine data.</p>`),
    section("flow", "Authoring flow", `<ol><li>Hold the Designer and select a volume.</li><li>Confirm to receive a temporary anchor.</li><li>Place the anchor to bind location/facing.</li><li>Configure its machine and menu.</li></ol>`),
    section("visuals", "Visual indicators", `<p>Selection and hitbox outlines appear while selecting or holding the Designer. They stay hidden during ordinary play.</p>`),
    section("anchor", "Temporary-anchor safety", `<p>The anchor disappears after placement. Dropping or moving it into a container invalidates it, preventing temporary anchors from becoming normal inventory items.</p>`),
    section("controls", "Designer controls", `<div class="table-wrap"><table><thead><tr><th>Input</th><th>Action</th></tr></thead><tbody><tr><td>Right-click</td><td>Open menu</td></tr><tr><td>Crouch + right-click</td><td>Copy/apply machine data</td></tr><tr><td>Left-click</td><td>Begin removal confirmation</td></tr><tr><td>Right-click highlighted anchor</td><td>Confirm group removal</td></tr></tbody></table></div>${callout("Protected removal", "Deleting a hitbox group requires confirmation.")}`)
  );

  pages["note-skins"] = page("Note skins", "Gameplay", "Replace receptors, notes, sustains, splashes, and animated arrows.", ["Sparrow XML", "Animated arrows"],
    section("layout", "Skin layout", code("text", `config/fnfmod/skins/<name>/
  NOTE_assets.png
  NOTE_assets.xml
  skin.json`)),
    section("json", "skin.json additions", `<p>Blockified reads note scale/alpha/X/Y, receptor values, sustain width/hold values, splash values, and hold-cover values. Missing fields use defaults.</p>`),
    section("chart", "Chart selection", `<p>Use chart <code>arrowSkin</code> and <code>splashSkin</code>. Global splash atlases may live at <code>config/fnfmod/splashes/&lt;name&gt;.png/.xml</code>.</p>`),
    section("hold", "Long-note animation", `<p>While an animated arrow skin is held on a sustain, Blockified advances the arrow animation every two atlas frames. Only the arrow skin is affected.</p>`),
    section("reload", "Reload", api("/fnf reload skins", "Reloads note skin definitions and atlases.", "Command"))
  );

  pages["custom-notes"] = page("Custom note types", "Gameplay", "Attach Lua behavior and declared properties to chart notes.", ["Complete packs", "Lua"],
    section("files", "Files", `<p>Use <code>custom_notetypes/&lt;Type&gt;.lua</code> and a matching <code>.txt</code>. Blockified parses declared properties before <code>onCreate</code>.</p>`),
    section("builtins", "Built-in types", `<ul><li>Hurt Note</li><li>Alt Animation</li><li>No Animation</li><li>GF Sing</li></ul>`),
    section("properties", "Blockified settings", `<p>Types can control texture/splash, health, hit-causes-miss, ignore/block/priority, animations, ratings, alpha, scrolling, offsets, rotation, scale, hit windows, and hit sounds.</p>`),
    section("sustains", "Long notes", `<p><code>goodNoteHit</code> fires repeatedly while a sustain succeeds and stops at its end. Track the note identity when an effect should happen once.</p>`)
  );

  pages.events = page("Chart events", "Gameplay", "Blockified events and modifications to compatible Psych events.", ["Minecraft", "3D camera", "Characters"],
    section("new", "Blockified events", `<div class="api-list">
      ${api("Minecraft Command", "V1 command; V2 player/server context. Supports player, opponent, speaker, direction, camera, and character placeholders.", "Event")}
      ${api("Camera Zoom", "V1 zoom offset (-1 to 0.9); V2 duration (default 0.5); V3 easing.", "Event")}
      ${api("Camera Focus", "V1 player, opponent, gf, or blank to release; V2 easing.", "Event")}
      ${api("Camera Behavior", "V1 speed multiplier; V2 easing; blank resets. Used by Minecraft/Legacy presentation.", "Event")}
      ${api("Camera Rotation 3D", "V1 pitch; V2 yaw; V3 roll; V4 easing.", "Event")}
      ${api("Add Character", "Tag, definition, X/Y/Z, rotation, animation, and side.", "Event")}
      ${api("Remove Character", "Removes an extra character by tag.", "Event")}
      ${api("Tween Character", "Tag, target X/Y/Z, duration, easing, and rotation.", "Event")}
    </div>`),
    section("follow", "Extended Camera Follow Pos", `<p>V1/V2 remain Psych X/Y. Blockified adds V3 Z in blocks, V4 easing, V5 default/override, V6 machine/camera reference, and V7 duration.</p>`),
    section("modified", "Modified compatible events", `<ul><li><strong>Play Animation:</strong> accepts an Add Character tag and BBS character.</li><li><strong>Change Character:</strong> accepts BBS definitions.</li><li><strong>Hey!:</strong> uses Blockified BBS mapping outside FNF presentation.</li></ul>`),
    section("compatible", "Other compatible built-ins", `<p>Hey!, Set GF Speed, Add Camera Zoom, Alt Idle Animation, Screen Shake, Change Scroll Speed, Set Property, and Play Sound are recognized; unchanged behavior is not duplicated.</p>`)
  );

  pages.camera = page("Gameplay camera", "Gameplay", "Camera modes, chart control, and world coordinates.", ["3D", "Events", "Lua"],
    section("modes", "Presentation behavior", `<p>Minecraft and Legacy use a 3D world camera with focus, behavior, position, rotation, and zoom events. FNF keeps stage-style presentation while accepting supported event changes.</p>`),
    section("events", "Chart control", `<p>Combine Camera Focus, Camera Behavior, Camera Follow Pos, Camera Rotation 3D, and Camera Zoom for authored shots.</p>`),
    section("lua", "Lua control", `<p>World-camera objects use the speaker origin and a fixed scale of 64 Lua pixels per Minecraft block.</p>`)
  );

  pages.rollback = page("Playstate rollback", "Gameplay", "Restore Minecraft state after finishing, quitting, or giving up.", ["Copy-on-write", "Commands"],
    section("state", "Captured state", `<p>The playstate records required player/world state, including inventory and synchronous block changes produced during the session.</p>`),
    section("blocks", "Modified blocks", `<p>Block state is captured copy-on-write: the original is saved on the first affected change. A chart command such as <code>/setblock ... air</code> can restore what existed before the song.</p>`),
    section("explosions", "Explosions", callout("Timing boundary", "Immediate command-driven changes inside the active transaction can restore. Delayed TNT or creeper explosions outside it are not guaranteed rollback coverage.")),
    section("ends", "End paths", `<p>The same restoration runs after normal finish, quit, or giving up after a loss.</p>`)
  );

  pages.multiplayer = page("Multiplayer", "Gameplay", "Server-authoritative sessions with streamed charts and audio.", ["LAN", "Server authority"],
    section("authority", "Authority", `<p>The server owns session state and validates transitions. Clients synchronize around the authoritative session.</p>`),
    section("delivery", "Song delivery", `<p>Charts/audio can stream to clients and be cached, reducing the need for identical preinstalled lightweight songs.</p>`),
    section("machines", "Machine boundary", `<p>Custom menus and machine editing are for singleplayer/LAN. Dedicated servers exclude authoring and menu Lua while retaining compatible library behavior.</p>`)
  );

  pages["lua-overview"] = page("Lua overview", "Lua", "Where scripts run and where Blockified differs from upstream engines.", ["Psych compatibility", "Complete packs"],
    section("scope", "Script scope", `<p>Lua runs only from complete packs. Locations include <code>scripts</code>, <code>stages</code>, <code>custom_notetypes</code>, <code>custom_events</code>, <code>data/&lt;song&gt;</code>, and song folders.</p>`),
    section("baseline", "Baseline compatibility", `<p>Blockified exposes broad Psych-style callbacks, variables, properties, tweens, timers, sound, note, and character control. Consult upstream docs for unchanged calls.</p>`),
    section("extensions", "Blockified extensions", `<p>Added APIs cover Minecraft commands, FOV/render distance, extra characters, world objects, 3D camera behavior, and machine menus.</p>`),
    section("trust", "Execution boundary", `<p>Lua is executable content. Only install packs you trust.</p>`)
  );

  pages["lua-blockified"] = page("Blockified Lua API", "Lua reference", "Minecraft, camera, and extra-character functions.", ["Blockified-only"],
    section("minecraft", "Minecraft and camera settings", `<div class="api-list">
      ${api("setRenderDistance(chunks)", "Sets the gameplay render-distance override.")}
      ${api("getRenderDistance()", "Returns active render distance.")}
      ${api("setFOV(degrees)", "Sets gameplay FOV.")}
      ${api("getFOV()", "Returns active FOV.")}
      ${api("runMinecraftCommand(command)", "Runs a command through Blockified session context.")}
      ${api("runCommand(command)", "Command-execution alias.")}
    </div>`),
    section("characters", "Extra characters", `<div class="api-list">
      ${api("addBlockifiedCharacter(tag, definition, x, y, z, rotation, animation, side)", "Creates an extra 2D/BBS world character.")}
      ${api("makeBlockifiedCharacter(...)", "Creation compatibility alias.")}
      ${api("removeBlockifiedCharacter(tag)", "Removes the tagged character.")}
      ${api("blockifiedCharacterExists(tag)", "Checks whether a tag exists.")}
      ${api("setBlockifiedCharacterPosition(tag, x, y, z)", "Moves a character.")}
      ${api("setBlockifiedCharacterRotation(tag, degrees)", "Rotates a character.")}
      ${api("changeBlockifiedCharacter(tag, definition)", "Changes definition while retaining the tag.")}
    </div>`),
    section("example", "Example", code("lua", `addBlockifiedCharacter('FNFBF', 'bf', 2.15, 0.79, -0.5, 0, 'idle', 'opponent')
setProperty('FNFBF.scale.x', 0.09)
setProperty('FNFBF.scale.y', 0.09)
setProperty('FNFBF.billboard', false)
setProperty('FNFBF.lighting', false)`)),
    section("multiple", "Animate multiple tags", code("lua", `local singers = {'FNFBF', 'SecondSinger', 'ThirdSinger'}
for _, tag in ipairs(singers) do
  triggerEvent('Play Animation', 'singRIGHT', tag)
end`))
  );

  pages["lua-world"] = page("World-camera Lua objects", "Lua reference", "Put sprites, atlases, text, and characters in Minecraft space.", ["3D transforms", "64 px = 1 block"],
    section("coordinates", "Coordinate system", `<ul><li>Origin: active speaker/stage origin.</li><li>64 Lua pixels = one block.</li><li>X: stage-right.</li><li>Y: downward.</li><li>Z: toward the stage camera.</li></ul>`),
    section("camera", "World camera", `<div class="api-list">${api("setObjectCamera(tag, 'world')", "Renders an object in world space.")}${api("doTweenZ(tweenTag, objectTag, z, duration, ease)", "Tweens world Z.")}</div>`),
    section("properties", "World properties", `<p>Objects can expose 3D rotation, billboard, lighting, shadows, XYZ position, and scale through <code>setProperty</code>. Sparrow XML sprites remain animatable.</p>`),
    section("fonts", "Custom fonts", `<p>3D/world text supports custom fonts, resolving the active pack's <code>fonts</code> then global Blockified fonts.</p>`),
    section("noop", "Text compatibility", callout("No-op means accepted, not applied", "Some compatibility functions, including text alignment/border in affected paths, may return without changing rendering."))
  );

  pages["lua-callbacks"] = page("Lua callbacks and notes", "Lua reference", "Blockified callback timing that matters for chart logic.", ["Sustain notes"],
    section("good", "goodNoteHit", `<p>For long notes, <code>goodNoteHit</code> fires repeatedly while the sustain is held successfully, then stops when it ends.</p>${code("lua", `function goodNoteHit(index, direction, noteType, isSustainNote)
  if isSustainNote then
    -- Repeats for successful sustain segments.
  end
end`)}`),
    section("once", "Run once instead", `<p>Check <code>isSustainNote</code> and/or track the note identifier when an effect belongs only to the head.</p>`),
    section("events", "Custom event scripts", `<p>Place event Lua under <code>custom_events</code> in a complete pack. Matching declared properties are available before creation callbacks where applicable.</p>`)
  );

  pages["machine-lua"] = page("Machine Lua API", "Lua reference", "Create menus and connect them to Blockified screens or songs.", ["Lua UI", "Sparrow XML"],
    section("widgets", "Widgets", `<div class="api-list">
      ${api("ui.panel(id, x, y, width, height)", "Creates a panel.", "Machine UI")}
      ${api("ui.label(id, text, x, y)", "Creates text.", "Machine UI")}
      ${api("ui.button(id, text, x, y, width, height)", "Creates a button with optional onClick.", "Machine UI")}
      ${api("ui.toggle(id, text, x, y, value)", "Creates a boolean control.", "Machine UI")}
      ${api("ui.slider(id, x, y, width, min, max, value)", "Creates a numeric control.", "Machine UI")}
      ${api("ui.image(id, path, x, y, width, height)", "Displays an image.", "Machine UI")}
      ${api("ui.sprite(id, path, x, y)", "Displays a static sprite.", "Machine UI")}
      ${api("ui.animatedSprite(id, image, xml, x, y)", "Loads a Sparrow XML atlas.", "Machine UI")}
    </div>`),
    section("actions", "Machine actions", `<div class="api-list">
      ${api("machine.openSongSelect()", "Opens the included selector.", "Machine")}
      ${api("machine.getSongs()", "Returns authoritative available songs.", "Machine")}
      ${api("machine.getSong(id) / machine.hasSong(id)", "Reads or checks one song.", "Machine")}
      ${api("machine.playSong(id)", "Starts an available song directly.", "Machine")}
      ${api("machine.openSongDetails(id)", "Opens song details.", "Machine")}
      ${api("machine.openSettings() / machine.openOptions()", "Opens settings.", "Machine")}
      ${api("machine.openCharacterEditor()", "Opens character editor.", "Machine")}
      ${api("machine.openChartEditor()", "Opens chart editor.", "Machine")}
      ${api("machine.join(...) / machine.save() / machine.close()", "Join, persist, or close.", "Machine")}
    </div>`),
    section("direct", "Direct-song button", code("lua", `local play = ui.button('play', 'Play Tutorial', 0.5, 0.42, 180, 24)
function play:onClick()
  if machine.hasSong('tutorial') then
    machine.playSong('tutorial')
  end
end`)),
    section("animation", "Animated sprites", `<p>Animated sprites support add-by-name/prefix, play/pause/resume/stop, frame selection, animation listing, FPS/loop, transform, tint, alpha, antialiasing, and <code>onComplete</code>.</p>${code("lua", `local dancer = ui.animatedSprite('dancer', 'images/dancer.png', 'images/dancer.xml', 0.5, 0.25)
dancer:addAnimationByPrefix('idle', 'idle', 24, true)
dancer:playAnimation('idle')`)}`),
    section("preset", "Minimal preset", code("lua", `-- Blockified machine menu
local title = ui.label('title', 'Earrings Machine', 0.5, 0.16)
local play = ui.button('play', 'Choose Song', 0.5, 0.42, 180, 24)
function play:onClick()
  machine.openSongSelect()
end`))
  );

  pages["lua-compatibility"] = page("Lua compatibility limits", "Lua reference", "Calls Blockified accepts partially or does not implement.", ["Compatibility"],
    section("unsupported", "Not implemented", `<ul><li>HScript/Haxe execution.</li><li>GLSL shaders.</li><li>FlxAnimate APIs.</li><li>Video/dialogue systems.</li><li>General Flixel groups.</li><li>Gamepad-specific APIs.</li><li>Some timebar, story, and loadSong flows.</li></ul>`),
    section("noop", "No-op calls", `<p>A no-op is accepted so Lua continues, but it makes no visual/state change. Text alignment/border are examples in affected render paths.</p>`),
    section("report", "Report a gap", `<p>Include the call, minimal Lua, expected upstream behavior, observed behavior, and log.</p>`)
  );

  pages["chart-editor"] = page("Chart editor", "Tools", "Edit notes, sustains, sections, events, metadata, and Blockified options.", ["In-game editor"],
    section("open", "Open", api("/fnf editor [song]", "Opens the editor, optionally loading a song.", "Command")),
    section("scope", "Editing scope", `<p>Edit note placement, sustain length, sections, BPM/timing, event lanes, metadata, note types, skin choices, and Blockified camera/event values.</p>`),
    section("save", "Saving", `<p>Save into the song chart/data location. Reload after external edits. Back up content before replacing or converting charts.</p>`)
  );

  pages["free-camera"] = page("Free camera", "Tools", "Blender-inspired framing and object editing during authoring.", ["Object editing", "Lua copy/paste"],
    section("camera", "Camera controls", `<div class="table-wrap"><table><thead><tr><th>Input</th><th>Action</th></tr></thead><tbody>
      <tr><td>Shift + F</td><td>Toggle fly/look; LMB exits</td></tr><tr><td>MMB</td><td>Orbit pivot</td></tr><tr><td>Shift + MMB</td><td>Pan</td></tr><tr><td>Ctrl + MMB</td><td>Dolly</td></tr><tr><td>Wheel</td><td>Dolly; speed only while cursor grab is active</td></tr><tr><td>W/A/S/D, E/Q</td><td>Fly</td></tr><tr><td>Left/Right</td><td>Roll</td></tr><tr><td>Up/Down</td><td>Camera Zoom value</td></tr><tr><td>R</td><td>Reset roll/zoom</td></tr><tr><td>F</td><td>Frame machine/camera</td></tr><tr><td>Ctrl+Shift+Space</td><td>Exit</td></tr>
    </tbody></table></div>`),
    section("objects", "Object controls", `<div class="table-wrap"><table><thead><tr><th>Input</th><th>Action</th></tr></thead><tbody>
      <tr><td>Click</td><td>Select object</td></tr><tr><td>Numpad .</td><td>Focus selected origin</td></tr><tr><td>G / R / S</td><td>Move / rotate / scale</td></tr><tr><td>Numbers</td><td>Exact transform amount</td></tr><tr><td>Alt + G/R/S</td><td>Reset transform</td></tr><tr><td>X/Y/Z</td><td>Axis</td></tr><tr><td>Shift + axis</td><td>Plane</td></tr><tr><td>Shift / Ctrl</td><td>Precise / snap</td></tr><tr><td>LMB / Enter</td><td>Confirm</td></tr><tr><td>RMB / Esc</td><td>Cancel</td></tr><tr><td>Delete</td><td>Delete selection</td></tr><tr><td>Ctrl+Z / Ctrl+Y</td><td>Undo / redo</td></tr>
    </tbody></table></div>`),
    section("clipboard", "Lua copy/paste", `<p><code>Ctrl+C</code> copies the selected object's Lua; with no selection it copies camera events. <code>Ctrl+V</code> parses supported marked Lua and duplicates/places its type, name, transform, and modifiers. Code is the source; no hidden metadata is saved.</p>${callout("Paste marker", "Clipboard Lua needs the predefined Blockified object comment; arbitrary clipboard Lua is not executed.")}`),
    section("types", "Editable objects", `<p>Blockified characters, sprites, Sparrow XML spritesheets, graphs, world text, and registered free-camera objects can be selected. Framing uses the visible origin marker.</p>`),
    section("ui", "Viewport UI", `<p>HUD starts hidden. Hide Menu swaps menu-only and HUD-only; its button remains at 30% opacity. The bottom-right gizmo hides with the menu. A thick camera marker shows original position/rotation.</p>`)
  );

  pages.commands = page("Commands and recovery", "Tools", "Open editors, reload content, and recover from a stuck session.", ["Commands"],
    section("editor", "Editor", api("/fnf editor [song]", "Opens chart editor.", "Command")),
    section("reload", "Reload", `<div class="api-list">${api("/fnf reload all", "Reloads all supported categories.", "Command")}${api("/fnf reload songs", "Reloads song library.", "Command")}${api("/fnf reload skins|splashes|animations|icons|hitsounds|fonts|options|scores", "Reloads one category.", "Command")}</div>`),
    section("emergency", "Emergency exit", `<p><code>Ctrl+Shift+Enter</code> leaves a stuck Blockified gameplay/editor state and returns control to Minecraft.</p>`)
  );

  pages.formats = page("Paths and formats", "Tools", "Quick reference for Blockified-owned locations.", ["Reference"],
    section("paths", "Path map", `<div class="table-wrap"><table><thead><tr><th>Content</th><th>Path</th></tr></thead><tbody><tr><td>Lightweight songs</td><td><code>config/fnfmod/songs</code></td></tr><tr><td>Complete packs</td><td><code>config/fnfmod/mods</code></td></tr><tr><td>Note skins</td><td><code>config/fnfmod/skins</code></td></tr><tr><td>Splashes</td><td><code>config/fnfmod/splashes</code></td></tr><tr><td>Pack machines</td><td><code>mods/&lt;mod&gt;/machines</code></td></tr><tr><td>Pack worlds</td><td><code>mods/&lt;mod&gt;/worlds</code></td></tr><tr><td>Pack fonts</td><td><code>mods/&lt;mod&gt;/fonts</code></td></tr></tbody></table></div>`),
    section("atlas", "Animated atlases", `<p>Note skins, splashes, characters, world sprites, and machine UI can use PNG plus Sparrow XML. XML prefixes drive animation registration.</p>`),
    section("audio", "Audio", `<p>Song audio uses OGG, conventionally <code>Inst.ogg</code> and optional vocal stems such as <code>Voices.ogg</code>.</p>`),
    section("case", "Portable paths", callout("Keep case exact", "Windows may hide path-case mistakes that fail on case-sensitive systems."))
  );

  window.BLOCKIFIED_DOCS = { groups, pages };
}());
