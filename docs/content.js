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
    { title: "Guides", pages: ["quick-start", "first-song-guide", "complete-pack-guide", "animations", "machine-guide"] },
    { title: "Documentation · Content", pages: ["songs", "mod-packs", "mod-worlds", "machines", "machine-hitboxes"] },
    { title: "Documentation · Gameplay", pages: ["note-skins", "custom-notes", "events", "camera", "rollback", "multiplayer"] },
    { title: "Documentation · Lua", pages: ["lua-overview", "lua-blockified", "lua-world", "lua-callbacks", "machine-lua", "lua-compatibility"] },
    { title: "Documentation · Tools", pages: ["chart-editor", "free-camera", "commands", "formats"] },
    { title: "Wiki", pages: ["wiki", "troubleshooting", "faq", "glossary"] }
  ];

  const pages = {};

  pages.home = page("Blockified Engine", "Documentation · Guides · Wiki", "Build Friday Night Funkin' songs, stages, machines, and world scenes inside Minecraft.", ["2.3.0bbs", "NeoForge 1.21.1", "Singleplayer + LAN"],
    `<div class="hero-panel"><span>DOCUMENTATION · GUIDES · WIKI</span><h2>Build with Blockified.</h2><p>Learn by following a guide, look up exact behavior in documentation, or understand systems through the wiki. This site covers Blockified additions and changes without duplicating unchanged Psych Engine material.</p><div class="hero-actions"><a class="button-link" href="#/quick-start">Start here</a><a class="button-link secondary" href="#/animations">Make animations</a><a class="button-link secondary" href="#/lua-overview">Lua API</a></div></div>`,
    section("choose", "Choose how to use this site", `<div class="card-grid category-grid">
      <a class="doc-card category-card guide-card" href="#/quick-start"><span>GUIDES</span><h3>Make something</h3><p>Follow ordered, practical steps from installation to a playable song, complete pack, animation, or custom machine.</p></a>
      <a class="doc-card category-card docs-card" href="#/songs"><span>DOCUMENTATION</span><h3>Look up exact behavior</h3><p>Find formats, APIs, events, callbacks, controls, paths, limits, and Blockified-specific rules.</p></a>
      <a class="doc-card category-card wiki-card" href="#/wiki"><span>WIKI</span><h3>Understand the engine</h3><p>Learn terminology, content scoping, common questions, troubleshooting, and how systems fit together.</p></a>
    </div>`),
    section("popular", "Popular destinations", `<div class="card-grid"><a class="doc-card" href="#/first-song-guide"><span>GUIDE</span><h3>Your first song</h3><p>Create, reload, and test a lightweight song.</p></a><a class="doc-card" href="#/machine-guide"><span>GUIDE</span><h3>Custom machine</h3><p>Texture a machine and build its Lua menu.</p></a><a class="doc-card" href="#/events"><span>REFERENCE</span><h3>Chart events</h3><p>Control Minecraft, characters, and cameras.</p></a><a class="doc-card" href="#/troubleshooting"><span>WIKI</span><h3>Fix a problem</h3><p>Diagnose loading, content, animation, and session issues.</p></a></div>`),
    section("models", "Two content models", `<div class="table-wrap"><table><thead><tr><th>Model</th><th>Location</th><th>Best for</th></tr></thead><tbody>
      <tr><td>Lightweight song</td><td><code>config/fnfmod/songs/&lt;song&gt;/</code></td><td>Charts, audio, metadata, events</td></tr>
      <tr><td>Complete mod pack</td><td><code>config/fnfmod/mods/&lt;mod&gt;/</code></td><td>Lua, art, machines, fonts, worlds</td></tr>
    </tbody></table></div>`),
    section("boundaries", "Important boundaries", `<ul><li>A bundled mod world exposes only its owning pack; ordinary worlds expose all configured content.</li><li>Custom machine editing and Lua menus target singleplayer and LAN, not dedicated servers.</li><li>Unchanged Psych APIs are not duplicated here.</li></ul>`)
  );
  pages.home.titleLogo = "./assets/blockified-engine-logo.png";

  pages.install = page("Install and run", "Getting started", "Download the latest release and install the complete required mod stack.", ["NeoForge", "Minecraft 1.21.1", "4 required JARs"],
    section("download", "Download Blockified", `<div class="release-panel" id="latest-release" aria-live="polite"><span class="release-kicker">LATEST GITHUB RELEASE</span><h3 id="latest-release-title">Checking latest release…</h3><p id="latest-release-status">Looking for a published Blockified JAR.</p><div class="hero-actions"><a class="button-link" id="latest-release-jar" href="https://github.com/Migokc/Blockified-engine_a-Minecraft-mod-made-with-AI/releases" target="_blank" rel="noreferrer">View releases</a><a class="button-link secondary" id="latest-release-page" href="https://github.com/Migokc/Blockified-engine_a-Minecraft-mod-made-with-AI/releases" target="_blank" rel="noreferrer">Release notes</a></div></div>${callout("No build step needed", "Normal players install the published JAR. Building from source is only for development when no suitable release exists.")}`),
    section("requirements", "Required mods", `<p>Blockified requires all four JARs in the same NeoForge instance:</p><div class="table-wrap"><table><thead><tr><th>Mod</th><th>Minimum version</th><th>Purpose</th></tr></thead><tbody><tr><td>Blockified Engine</td><td>Latest release</td><td>FNF engine and Minecraft integration</td></tr><tr><td><a href="https://modrinth.com/mod/bbs-fs" target="_blank" rel="noreferrer">BBS FS</a></td><td>2.3.1+</td><td>Character forms and animation states</td></tr><tr><td><a href="https://github.com/Sinytra/Connector" target="_blank" rel="noreferrer">Sinytra Connector</a></td><td>2.0.0-beta.15+</td><td>Runs the Fabric BBS JAR on NeoForge</td></tr><tr><td><a href="https://github.com/Sinytra/ForgifiedFabricAPI" target="_blank" rel="noreferrer">Forgified Fabric API</a></td><td>0.116.7+</td><td>Fabric API compatibility required by the stack</td></tr></tbody></table></div>${callout("Required stack", "BBS FS, Sinytra Connector, and Forgified Fabric API are requirements, not optional extras. Keep each as a separate JAR beside Blockified Engine.")}`),
    section("launch", "First launch", `<ol><li>Install NeoForge for Minecraft 1.21.1.</li><li>Put Blockified Engine, BBS FS, Sinytra Connector, and Forgified Fabric API in the instance <code>mods</code> folder.</li><li>Start once so <code>config/fnfmod</code> is created.</li><li>Add songs under <code>songs</code>, or complete packs under <code>mods</code>.</li><li>Use <code>/fnf reload all</code> after live content edits.</li></ol>`),
    section("presentation", "Presentation modes", `<p>Minecraft, FNF, and Legacy modes change stage/camera presentation without requiring a different chart format.</p>`)
  );

  pages.scope = page("How this site is organized", "Read this first", "How guides, documentation, and wiki pages divide Blockified knowledge.", ["Blockified-only", "Site map"],
    section("categories", "Three kinds of pages", `<div class="table-wrap"><table><thead><tr><th>Category</th><th>Use it when</th><th>Style</th></tr></thead><tbody><tr><td>Guides</td><td>You want to make or configure something</td><td>Ordered steps with working examples</td></tr><tr><td>Documentation</td><td>You need exact supported behavior</td><td>Formats, APIs, controls, paths, and limits</td></tr><tr><td>Wiki</td><td>You need concepts, answers, or diagnosis</td><td>Explanations, glossary, FAQ, troubleshooting</td></tr></tbody></table></div>`),
    section("included", "Included", `<ul><li>Blockified files, events, APIs, machines, editors, and multiplayer behavior.</li><li>Changes Blockified makes to compatible Psych events or Lua calls.</li><li>Compatibility gaps and Minecraft coordinate rules.</li><li>Practical creation workflows and common failure recovery.</li></ul>`),
    section("upstream", "Not duplicated", `<p>General Psych Engine Lua, chart basics, and unchanged asset behavior are not rewritten. Use the <a href="https://shadowmario.github.io/psychengine.lua/" target="_blank" rel="noreferrer">Psych Engine Lua documentation</a>, then return here for Blockified differences.</p>`),
    section("truth", "Source of truth", `<p>Release-specific documentation follows the newest published Blockified JAR. Report disagreements with a minimal mod/song, log, and reproduction steps.</p>`)
  );

  pages["quick-start"] = page("Quick start", "Guide", "Go from a clean Minecraft instance to a playable Blockified song.", ["Beginner", "10-minute path"],
    section("install", "1. Install the required stack", `<ol><li>Install NeoForge for Minecraft 1.21.1.</li><li>Download the latest Blockified release JAR.</li><li>Add BBS FS, Sinytra Connector, and Forgified Fabric API.</li><li>Put all four separate JARs in the instance <code>mods</code> folder.</li></ol><p><a class="button-link" href="#/install">Open installation guide</a></p>`),
    section("launch", "2. Launch once", `<p>Start Minecraft and enter a normal world. Blockified creates <code>config/fnfmod</code> and its content folders.</p>`),
    section("song", "3. Add one song", code("text", `config/fnfmod/songs/tutorial/
  tutorial.json
  Inst.ogg
  Voices.ogg`)),
    section("reload", "4. Reload and play", `<ol><li>Run <code>/fnf reload songs</code>.</li><li>Place a Funkin' Machine from the Functional Blocks creative tab.</li><li>Right-click it and choose the song.</li><li>Select a difficulty and presentation mode.</li></ol>`),
    section("next", "5. Choose what to build next", `<div class="card-grid"><a class="doc-card" href="#/first-song-guide"><span>GUIDE</span><h3>Finish your first song</h3><p>Charts, audio, difficulties, events, and testing.</p></a><a class="doc-card" href="#/complete-pack-guide"><span>GUIDE</span><h3>Make a complete pack</h3><p>Add Lua, art, characters, machines, and worlds.</p></a><a class="doc-card" href="#/animations"><span>GUIDE</span><h3>Create animations</h3><p>Map BBS states or animate Sparrow atlases.</p></a><a class="doc-card" href="#/machine-guide"><span>GUIDE</span><h3>Customize a machine</h3><p>Build textures, menus, and virtual hitboxes.</p></a></div>`)
  );

  pages["first-song-guide"] = page("Create your first song", "Guide", "Build, discover, and test a lightweight song without writing Lua.", ["Beginner", "Lightweight song"],
    section("folder", "1. Create the folder", `<p>Choose a stable lowercase ID. Folder and base chart filename should match.</p>${code("text", `config/fnfmod/songs/my-song/
  my-song.json
  Inst.ogg
  Voices.ogg`)}`),
    section("chart", "2. Add a chart", `<p>Use a compatible Psych, legacy FNF, V-Slice, or Codename chart. Easiest path: open <code>/fnf editor my-song</code>, author notes and metadata, then save.</p>${callout("Required", "A playable difficulty needs a valid chart and Inst.ogg. Voices are optional when the chart does not need them.")}`),
    section("difficulties", "3. Add difficulties", `<p>For Psych-style files, keep normal at <code>my-song.json</code> and add suffixes such as <code>my-song-easy.json</code> or <code>my-song-hard.json</code>. Keep audio in the same song folder.</p>`),
    section("discover", "4. Reload the library", `<p>Run <code>/fnf reload songs</code>. Open a Funkin' Machine and search for the chart title or folder ID.</p>`),
    section("test", "5. Test all paths", `<ul class="checklist"><li>Song appears in selector.</li><li>Instrumental and vocals start together.</li><li>Normal and extra difficulties load.</li><li>Player/opponent note sides are correct.</li><li>Minecraft, FNF, and Legacy presentation behave as intended.</li><li>Finishing, quitting, and giving up restore playstate.</li></ul>`),
    section("upgrade", "Need scripts or custom assets?", `<p>Lightweight songs intentionally exclude Lua, custom characters, stages, fonts, and rich asset lookup. Move to a <a href="#/complete-pack-guide">complete pack</a> when the song needs those features.</p>`)
  );

  pages["complete-pack-guide"] = page("Create a complete mod pack", "Guide", "Package songs, Lua, art, machines, animations, and worlds together.", ["Intermediate", "Self-contained"],
    section("create", "1. Create the pack root", code("text", `config/fnfmod/mods/My-Mod/
  pack.json
  data/
  songs/
  scripts/
  stages/
  custom_events/
  custom_notetypes/
  animations/
  characters/
  images/
  sounds/
  machines/
  fonts/
  worlds/`)),
    section("manifest", "2. Version the pack", `<p>Add a readable <code>pack.json</code>. Machine/LAN compatibility uses <code>version</code> when present.</p>${code("json", `{
  "name": "My Mod",
  "version": "1.0.0"
}`)}`),
    section("song", "3. Add Psych-style song content", `<p>Put charts under <code>data/&lt;song&gt;/</code> and audio under <code>songs/&lt;song&gt;/</code>. Preserve compatible pack paths instead of copying rich assets into the lightweight-song directory.</p>`),
    section("features", "4. Add only what you use", `<div class="table-wrap"><table><thead><tr><th>Feature</th><th>Folder</th></tr></thead><tbody><tr><td>Global/song Lua</td><td><code>scripts/</code>, <code>data/&lt;song&gt;/</code></td></tr><tr><td>Characters and sprites</td><td><code>characters/</code>, <code>images/</code></td></tr><tr><td>BBS mappings</td><td><code>animations/</code></td></tr><tr><td>Custom chart behavior</td><td><code>custom_events/</code>, <code>custom_notetypes/</code></td></tr><tr><td>Machine profiles/menus</td><td><code>machines/</code></td></tr><tr><td>Bundled saves</td><td><code>worlds/</code></td></tr></tbody></table></div>`),
    section("scope", "5. Test both content scopes", `<ul><li>In a normal world, every installed pack, lightweight song, and configured external directory is available.</li><li>Inside this pack's bundled world, only this owning pack is available.</li></ul>`),
    section("release", "6. Package and test", `<p>Test from a clean instance, keep path case exact, include required assets, and increment <code>version</code> before distributing changes that affect LAN machine compatibility.</p>`)
  );

  pages["machine-guide"] = page("Build a custom machine", "Guide", "Create a textured Funkin' Machine with a Lua menu or virtual interaction volume.", ["Intermediate", "Singleplayer + LAN"],
    section("profile", "1. Create a machine profile", code("text", `config/fnfmod/mods/My-Mod/machines/neon/
  machine.json
  menu.lua
  textures/machine.png`)),
    section("configure", "2. Define appearance", `<p>Set the machine ID, display name, menu path, whole-machine texture, or optional per-face textures in <code>machine.json</code>. Use the Funkin' Designer for live preview and Save/Save As/Reload.</p><p><a href="#/machines">Open machine format documentation</a></p>`),
    section("menu", "3. Build the menu", `<p>Start with a label and button, then call an existing screen or launch an available song directly.</p>${code("lua", `-- Blockified machine menu
local title = ui.label('title', 'Neon Machine', 0.5, 0.16)
local play = ui.button('play', 'Choose Song', 0.5, 0.42, 180, 24)

function play:onClick()
  machine.openSongSelect()
end`)}<p><a href="#/machine-lua">Open full machine Lua reference</a></p>`),
    section("place", "4. Choose physical or virtual", `<ul><li><strong>Physical:</strong> apply the profile to a Funkin' Machine block.</li><li><strong>Virtual:</strong> use the Designer to select a fractional entity-backed volume, confirm it, then place the temporary anchor for facing/location.</li></ul>`),
    section("test", "5. Test player experience", `<ul class="checklist"><li>Every face uses the intended texture.</li><li>Menu opens from the block and virtual hitbox.</li><li>Buttons work with mouse and Escape releases chooser ownership.</li><li>Direct-song buttons handle unavailable song IDs safely.</li><li>LAN guest has matching pack version and machine assets.</li></ul>${callout("Server boundary", "Custom machine authoring and menu Lua target singleplayer/LAN, not dedicated servers.")}`)
  );

  pages.wiki = page("Blockified wiki", "Wiki", "Concepts behind content discovery, gameplay presentation, authoring, and restoration.", ["Concepts", "Architecture"],
    section("mental-model", "Mental model", `<div class="concept-flow"><div><span>1</span><strong>Content</strong><p>Charts, audio, packs, assets, Lua.</p></div><div><span>2</span><strong>Discovery</strong><p>World scope decides what is visible.</p></div><div><span>3</span><strong>Machine</strong><p>Player chooses song and presentation.</p></div><div><span>4</span><strong>Playstate</strong><p>Server owns session; clients render/play.</p></div><div><span>5</span><strong>Restore</strong><p>Saved player/world state returns on exit.</p></div></div>`),
    section("content-models", "Two content models", `<p><strong>Lightweight songs</strong> are fast chart/audio entries without rich scripts or pack assets. <strong>Complete packs</strong> contain Psych-style songs plus Lua, images, characters, animations, machines, fonts, and optional bundled worlds.</p>`),
    section("presentations", "Three presentations", `<ul><li><strong>Minecraft:</strong> world-focused stage and camera.</li><li><strong>FNF:</strong> fixed 1280×720 Psych-style scene.</li><li><strong>Legacy:</strong> Blockified's earlier world presentation behavior.</li></ul><p>Presentation changes visuals and camera behavior; compatible charts remain shared.</p>`),
    section("scope", "World-aware content", `<p>Normal worlds expose all configured content. A bundled world becomes self-contained and exposes only its owning pack. This prevents another installed pack from silently changing the bundled experience.</p>`),
    section("authoring", "Authoring layers", `<ul><li>Chart Editor controls notes, timing, metadata, and events.</li><li>Character Editor maps character definitions and BBS states.</li><li>Free camera edits world objects and camera shots.</li><li>Funkin' Designer edits machine profiles and virtual hitboxes.</li><li>Lua extends supported gameplay and machine-menu behavior.</li></ul>`),
    section("learn-more", "Continue reading", `<div class="card-grid"><a class="doc-card" href="#/glossary"><span>WIKI</span><h3>Glossary</h3><p>Decode Blockified terms.</p></a><a class="doc-card" href="#/faq"><span>WIKI</span><h3>FAQ</h3><p>Short answers to common questions.</p></a><a class="doc-card" href="#/troubleshooting"><span>WIKI</span><h3>Troubleshooting</h3><p>Symptoms, checks, and fixes.</p></a><a class="doc-card" href="#/formats"><span>REFERENCE</span><h3>Paths and formats</h3><p>Canonical content locations.</p></a></div>`)
  );

  pages.troubleshooting = page("Troubleshooting", "Wiki", "Diagnose loading, content discovery, animation, machine, and rollback problems.", ["Support", "Recovery"],
    section("loader", "Minecraft stops during mod loading", `<ol><li>Confirm Minecraft 1.21.1 and matching NeoForge.</li><li>Confirm Blockified Engine, BBS FS, Sinytra Connector, and Forgified Fabric API are all installed.</li><li>Remove duplicate/older copies of those JARs.</li><li>Check <code>logs/latest.log</code> for the first exception, not only the final crash line.</li><li>Test once without third-party Blockified content to separate loader failure from pack failure.</li></ol>`),
    section("songs", "Song does not appear", `<ul><li>Confirm folder/chart/audio names and path case.</li><li>Run <code>/fnf reload songs</code>.</li><li>Remember bundled worlds expose only their owning pack.</li><li>Keep Lua/rich assets in a complete pack, not <code>config/fnfmod/songs</code>.</li></ul>`),
    section("animation", "Animation does not play", `<ul><li>For BBS, confirm form name, mapped state ID, and required dependency stack.</li><li>For Sparrow XML, confirm PNG/XML pairing and exact frame prefix.</li><li>Confirm requested animation name matches the JSON key or Lua registration.</li><li>Run <code>/fnf reload animations</code> after external edits.</li></ul>`),
    section("machine", "Machine menu or hitbox fails", `<ul><li>Confirm machine files live inside the owning complete pack.</li><li>Verify <code>menu.lua</code> path and check Lua errors.</li><li>Recreate a virtual machine after its temporary anchor is dropped, stored, lost, or invalidated.</li><li>Use the Designer to reveal and edit virtual hitboxes.</li></ul>`),
    section("rollback", "World change does not restore", `<p>Rollback covers player state and synchronous block changes inside the active command transaction. Delayed TNT, creepers, scheduled work, or changes outside that boundary are not guaranteed. Provide the command/event, end path, coordinates, and log when reporting a failure.</p>`),
    section("recovery", "Emergency recovery", `<p>Press <code>Ctrl+Shift+Enter</code> to leave a stuck gameplay/editor state. Then preserve <code>latest.log</code> before restarting.</p>`)
  );

  pages.faq = page("Frequently asked questions", "Wiki", "Short answers about installation, content, Lua, machines, multiplayer, and rollback.", ["FAQ"],
    section("build", "Do players need to build Blockified?", `<p>No. Install the JAR attached to the latest GitHub release. Source builds are for development or unreleased testing.</p>`),
    section("requirements", "Are the BBS-related mods optional?", `<p>No. BBS FS, Sinytra Connector, and Forgified Fabric API are part of the required installation stack.</p>`),
    section("content", "Which content is available in each world?", `<p>Normal worlds expose lightweight songs, all installed packs, and configured external directories. A bundled mod world exposes only its owning pack.</p>`),
    section("lua", "Can a lightweight song use Lua?", `<p>No. Use a complete pack for Lua, characters, stages, custom events/notes, fonts, and other rich assets.</p>`),
    section("hitboxes", "Do virtual machine hitboxes use Lua menus?", `<p>Yes. They point to the same machine profile/menu system as physical Funkin' Machines.</p>`),
    section("server", "What works on dedicated servers?", `<p>Server-authoritative songs and compatible libraries work. Custom machine authoring and menu Lua are intentionally limited to singleplayer/LAN.</p>`),
    section("explosions", "Does rollback restore explosions?", `<p>Synchronous command-driven block changes can restore. Delayed TNT or creeper explosions outside the active transaction are not guaranteed.</p>`),
    section("noop", "What does a Lua no-op mean?", `<p>The function is accepted so the script continues, but it does not change rendering or state in that path.</p>`)
  );

  pages.glossary = page("Glossary", "Wiki", "Blockified terms used throughout guides and reference pages.", ["Terminology"],
    section("terms", "Core terms", `<div class="api-list">
      ${api("Asset scope", "The content paths currently allowed by the active world.", "Wiki")}
      ${api("BBS form", "A BBS FS character/model definition that owns named animation states.", "Wiki")}
      ${api("BBS state", "A named pose or animation played on a BBS form.", "Wiki")}
      ${api("Bundled world", "A Minecraft save under one complete pack's worlds folder; it exposes only that owner pack.", "Wiki")}
      ${api("Complete pack", "A self-contained mod folder supporting songs, Lua, art, characters, machines, animations, and worlds.", "Wiki")}
      ${api("Lightweight song", "A chart/audio-focused entry under config/fnfmod/songs without rich pack assets or Lua.", "Wiki")}
      ${api("Machine profile", "Machine appearance, behavior, and optional Lua menu stored inside a complete pack.", "Wiki")}
      ${api("Presentation", "Minecraft, FNF, or Legacy visual/camera treatment applied to a compatible chart.", "Wiki")}
      ${api("Playstate", "The active Blockified song session, including gameplay, camera, audio, and restoration state.", "Wiki")}
      ${api("Sparrow XML", "Texture-atlas XML describing named frames inside a PNG spritesheet.", "Wiki")}
      ${api("Temporary anchor", "Short-lived inventory item used to place facing/location for a virtual machine.", "Wiki")}
      ${api("Virtual machine", "A non-colliding entity-backed interaction volume using a normal machine profile/menu.", "Wiki")}
      ${api("World camera", "Lua/render camera that places supported 2D objects into Minecraft 3D space.", "Wiki")}
    </div>`)
  );

  pages.songs = page("Songs", "Documentation · Content", "The smallest way to add playable charts and audio.", ["Lightweight", "No Lua"],
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

  pages["mod-packs"] = page("Complete mod packs", "Documentation · Content", "Self-contained mods with songs, scripts, art, machines, and worlds.", ["Rich assets", "Lua"],
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
    section("activation", "World-aware activation", `<p>Inside a bundled mod world, only its owning pack is exposed. In an ordinary world, every installed pack and configured external directory is available.</p>`),
    section("library", "Song-library behavior", `<p>Ordinary worlds combine lightweight songs, installed packs, and external directories. Bundled worlds isolate the library to the owning pack.</p>`),
    section("cache", "Loading and caches", `<p>Song discovery stores a compact metadata index at <code>config/fnfmod/cache/song-library-index-v1.json</code> and reuses entries whose relevant paths, sizes, and modification times have not changed. Clearing the song cache is safe; Blockified rebuilds it on the next scan. Gameplay prepares known character, stage, and Lua graphics during the waiting screen and keeps decoded PNGs/Sparrow atlases in bounded memory-only caches. Graphics are never copied into the disk cache, and memory caches clear when the client disconnects.</p>`)
  );

  pages["mod-worlds"] = page("Bundled worlds", "Documentation · Content", "Tie a Minecraft world to one complete Blockified pack.", ["World-scoped assets"],
    section("location", "Location", `<p>Place worlds under <code>config/fnfmod/mods/My-Mod/worlds/</code>. Blockified resolves the owning pack from the active world.</p>`),
    section("scope", "Asset scope", `<div class="table-wrap"><table><thead><tr><th>Location</th><th>Available content</th></tr></thead><tbody><tr><td>Pack's bundled world</td><td>Only the owning pack</td></tr><tr><td>Ordinary local/LAN world</td><td>All lightweight songs, installed packs, and external directories</td></tr><tr><td>Dedicated server</td><td>Global song library; machine authoring/menu Lua disabled</td></tr></tbody></table></div>`),
    section("reason", "Why", `<p>Bundled worlds stay self-contained and deterministic, while ordinary worlds preserve Blockified's full global library.</p>`),
    section("world-options", "World-forced settings", `<p>A bundled world can override the player's own settings while it is being played. Put a <code>blockified-options.json</code> in the world folder (<code>config/fnfmod/mods/My-Mod/worlds/My-World/</code>) with any subset of the user's settings keys, plus the world-only controls below.</p>${code("json", `{
  "allowCheats": false,
  "saveOnExit": true,
  "allowExternalContent": false,
  "hideSettingsButton": false,
  "downscroll": true,
  "noteSkin": "NOTE_assets-future",
  "hudStyle": "fnf",
  "scrollSpeedMult": 2.4,
  "constantScrollSpeed": true
}`)}<div class="api-list">
      ${api("Defined keys", "Shadow the player's value and are locked (grayed out) in the in-game settings while inside that world.")}
      ${api("Undefined keys", "Fall back to the player's own setting, unchanged.")}
      ${api("allowCheats", "Optional boolean. Enables or disables player command permissions for this bundled world.")}
      ${api("saveOnExit", "Defaults to true. When false, autosaves and exit saves are suppressed, including world, entity, and player progress.")}
      ${api("allowExternalContent", "Defaults to false. When true, the world may also use installed packs and configured directories, not only its owning pack.")}
      ${api("hideSettingsButton", "Defaults to false. When true, the in-menu World Settings button is hidden for this world.")}
      ${api("Scope", "Only applies inside that bundled world. Normal/LAN worlds and menus use the player's plain settings.")}
    </div>${callout("Separate from user options", "World-forced values and world-only controls are read only from this bundled world's blockified-options.json. They are never written to the user's options.json, and the player's own settings return when leaving.")}`),
    section("world-settings-menu", "World Settings menu", `<p>Instead of editing the JSON by hand, the host can open a <strong>World Settings</strong> button in the Funkin' Machine song selector while inside a bundled world. It writes the same <code>blockified-options.json</code>.</p><div class="api-list">
      ${api("World controls", "Toggle cheats (Default / On / Off), Save changes to world, and Content (only this mod vs. allow external packs and directories).")}
      ${api("Force my gameplay settings", "Snapshots your current gameplay/visual settings (note colors, scroll, HUD, note skin, icons, ...) into the world so every player is forced to them. Mods, directories, personal calibration and skin choice are never forced.")}
      ${api("Hide this button", "Sets hideSettingsButton so the menu button no longer appears for the world.")}
    </div>${callout("Host only", "The button appears only for the singleplayer or LAN owner of a verified bundled world, since it writes into the world folder.")}`),
    section("world-assets", "Share BBS model-block assets", `<p>The World Settings menu's <strong>Bundle BBS model-block assets</strong> action copies the models and textures used by the BBS model blocks placed near you into <code>&lt;world&gt;/bbs-assets/</code> and registers them, so the blocks still render for anyone you share the world with even when the original files are missing on their install. Only existing placed blocks are bundled. Bundled assets are registered automatically when the world is entered.</p>`),
    section("world-transfer", "Move worlds in and out", `<p>Use <a href="#/commands">/fnf world export &lt;pack&gt;</a> to move an ordinary save into a pack as a bundled world, and <code>/fnf world import</code> to move a bundled world back into your saves. Both play a short transition and reopen the world in its new place.</p>`)
  );

  pages.machines = page("Custom machines", "Documentation · Content", "Reskin the Funkin' machine and replace its selector with a Lua menu.", ["Singleplayer", "LAN", "Lua UI"],
    section("files", "Machine assets", code("text", `config/fnfmod/mods/My-Mod/machines/<machine-id>/
  machine.json
  menu.lua
  textures/
  images/`)),
    section("texture", "Textures", `<p>The machine definition selects art from the owning mod. Keeping it in the mod makes texture availability follow bundled-world scope.</p>`),
    section("interaction", "Interaction", `<p>Right-click opens the menu. Crouch-right-click with the Funkin' Designer edits or copies configuration. Virtual hitboxes open the same menu.</p>`),
    section("menu", "Lua menu", `<p><code>menu.lua</code> can create controls, images, static sprites, and Sparrow XML animation. It can open built-in screens or start a song directly.</p><a class="button-link" href="#/machine-lua">Machine Lua reference</a>`),
    section("chunk-points", "Chunk loader points", `<p>The Functional Blocks tab includes an invisible, non-colliding <strong>Chunk Loader Point</strong>. Hold the point item or Funkin' Designer to reveal and target it, then right-click to edit its unique tag, enabled state, and 0-12 chunk radius. Its cyan boundary shows the exact square of ticking chunks; disabled points are red.</p><p>Tickets persist across world reloads and overlapping points remain independently owned. Water and lava flow around points instead of replacing them. Use points around distant stage areas instead of modifying camera coordinates. Authoring and persistent loading are singleplayer/LAN-only.</p>${callout("Cost", "A radius loads (2r+1)² ticking chunks. Prefer several small points over unnecessarily large radii.")}`),
    section("server", "Server boundary", callout("Singleplayer and LAN", "Custom machine editing, menu Lua, and Chunk Loader Points are intentionally excluded from dedicated servers."))
  );

  pages["machine-hitboxes"] = page("Virtual machine hitboxes", "Documentation · Content", "Invisible non-colliding regions that act like a machine.", ["Entity hitbox", "Funkin' Designer"],
    section("model", "How they work", `<p>A hitbox is an entity-backed volume, not a block. It supports fractional space and non-full blocks. Each group points to a temporary machine anchor containing facing, location, and machine data.</p>`),
    section("flow", "Authoring flow", `<ol><li>Hold the Designer and select a volume.</li><li>Confirm to receive a temporary anchor.</li><li>Place the anchor to bind location/facing.</li><li>Configure its machine and menu.</li></ol>`),
    section("visuals", "Visual indicators", `<p>Selection and hitbox outlines appear while selecting or holding the Designer. They stay hidden during ordinary play.</p>`),
    section("anchor", "Temporary-anchor safety", `<p>The anchor disappears after placement. Dropping or moving it into a container invalidates it, preventing temporary anchors from becoming normal inventory items.</p>`),
    section("controls", "Designer controls", `<div class="table-wrap"><table><thead><tr><th>Input</th><th>Action</th></tr></thead><tbody><tr><td>Right-click</td><td>Open menu</td></tr><tr><td>Crouch + right-click</td><td>Copy/apply machine data</td></tr><tr><td>Left-click</td><td>Begin removal confirmation</td></tr><tr><td>Right-click highlighted anchor</td><td>Confirm group removal</td></tr></tbody></table></div>${callout("Protected removal", "Deleting a hitbox group requires confirmation.")}`)
  );

  pages["note-skins"] = page("Note skins", "Documentation · Gameplay", "Replace receptors, notes, sustains, splashes, and animated arrows.", ["Sparrow XML", "Animated arrows"],
    section("layout", "Skin layout", `<p>Two layouts are supported. A <strong>folder skin</strong> under <code>config/fnfmod/skins/&lt;name&gt;/</code> uses fixed filenames and can carry the full set (V-Slice split atlases, hold covers, its own splashes):</p>${code("text", `config/fnfmod/skins/<name>/
  NOTE_assets.png
  NOTE_assets.xml
  skin.json`)}<p>Or, Psych-style, <strong>flat files named by the skin</strong> in a mod's <code>images/noteSkins/</code>, with the json forcibly named to match. Easiest to import — drop the files straight in:</p>${code("text", `mods/<mod>/images/noteSkins/
  NOTE_assets-future.png
  NOTE_assets-future.xml
  NOTE_assets-future.json`)}<p>Those same flat files also work loose in the global skins folder (<code>config/fnfmod/skins/NOTE_assets-future.png/.xml/.json</code>), not just inside a mod. The skin name is the file's base name (<code>NOTE_assets-future</code>), not a forced <code>NOTE_assets</code>. Flat skins load the classic single atlas + json; the folder layout keeps its extras. On a name clash the order is: mod <code>images/noteSkins</code> → flat file in <code>config/fnfmod/skins</code> → folder in <code>config/fnfmod/skins</code>.</p>`),
    section("json", "skin.json additions", `<p>Blockified reads note scale/alpha/X/Y, receptor values, sustain width/hold values, splash values, and hold-cover values. Missing fields use defaults. Flat skins name this file <code>&lt;skin&gt;.json</code>; folder skins name it <code>skin.json</code>.</p>`),
    section("chart", "Chart selection", `<p>Use chart <code>arrowSkin</code> and <code>splashSkin</code>. Splash atlases live at <code>config/fnfmod/splashes/&lt;name&gt;.png/.xml</code> or a mod's <code>images/noteSplashes/&lt;name&gt;.png/.xml</code> (mod folder checked first).</p>`),
    section("hold", "Long-note animation", `<p>While an animated arrow skin is held on a sustain, Blockified advances the arrow animation every two atlas frames. Only the arrow skin is affected.</p>`),
    section("reload", "Reload", api("/fnf reload skins", "Reloads note skin definitions and atlases.", "Command"))
  );

  pages.animations = page("Animations", "Creation guide", "Create BBS character mappings and Sparrow XML sprite animations.", ["BBS states", "Sparrow XML", "JSON", "Lua"],
    section("choose", "Choose an animation type", `<div class="table-wrap"><table><thead><tr><th>What moves</th><th>Format</th><th>Where it lives</th></tr></thead><tbody><tr><td>Minecraft/BBS character</td><td>BBS form states mapped by JSON</td><td><code>animations/&lt;character&gt;.json</code></td></tr><tr><td>FNF character or world sprite</td><td>PNG + Sparrow XML atlas</td><td>Complete pack <code>images/</code> and character JSON/Lua</td></tr><tr><td>Machine-menu sprite</td><td>PNG + Sparrow XML atlas</td><td>Machine assets loaded by menu Lua</td></tr><tr><td>Notes, receptors, sustains, splashes</td><td>PNG + Sparrow XML atlas</td><td><code>config/fnfmod/skins/&lt;skin&gt;/</code></td></tr></tbody></table></div>`),
    section("bbs-prepare", "1. Prepare BBS states", `<p>Create or import a BBS FS form, then give it named states such as <code>idle</code>, <code>singLEFT</code>, <code>singDOWN</code>, <code>singUP</code>, and <code>singRIGHT</code>. Blockified does not create the movement itself; it tells BBS which state to play.</p>`),
    section("bbs-map", "2. Map a BBS character", `<p>Create <code>animations/my-character.json</code> in a complete pack. Add <code>my-character-opp.json</code> only when the opponent needs a different form or mapping.</p>${code("json", `{
  "bbsForm": "My BF form",
  "vocals_file": "bf",
  "healthbar_colors": [49, 176, 209],
  "cameraOffset": [0.0, 0.5],
  "animations": {
    "idle": "idle",
    "left": { "state": "singLEFT", "cameraOffset": [-1.0, 0.0] },
    "down": "singDOWN",
    "up": "singUP",
    "right": { "state": "singRIGHT", "cameraOffset": [1.0, 0.0] },
    "attack": "sword-swing"
  }
}`)}<p>Keys are Blockified animation names used by gameplay, events, and Lua. Values are BBS state IDs. <code>cameraOffset</code> uses blocks: X is screen-right, Y is screen-up. Psych-compatible <code>healthbar_colors</code> also colors that role's chart-editor waveform. <code>vocals_file</code> selects <code>Voices-&lt;value&gt;.ogg</code>; aliases such as <code>vocalsFile</code>, <code>vocal_file</code>, and vocal-prefix spellings are accepted.</p>`),
    section("bbs-shared-assets", "3. Share one BBS asset folder", `<p>Folder-style definitions can point several animations at one mod-local asset tree. Put <code>assets_path.txt</code> beside <code>character.json</code>. Its first non-comment line uses the same mod-root-relative path style as Lua:</p>${code("text", `animations/anim_assets/idk`)}<p>For example, <code>animations/Earrings/assets_path.txt</code> now makes <code>animations/anim_assets/idk</code> available to that definition's <code>character.form.json</code>. Put the shared <code>models/</code>, textures, and other <code>assets:...</code> targets there. Assets kept directly in <code>animations/Earrings</code> remain supported and override same-named shared files. Paths cannot leave the owning mod. Flat definitions may use <code>animations/my-character.assets_path.txt</code>.</p>${callout("Addition, not replacement", "Nothing changes for existing self-contained animation folders. Add the text file only when definitions should reuse a shared asset tree.")}`),
    section("bbs-use", "4. Use and test it", `<ol><li>Open the Character Editor. Its Character, Model, and Animations tabs edit either the Player or Opponent role while the BBS form preview stays on the left.</li><li>Use <strong>Load</strong> to choose a character from the searchable list. A bundled form is loaded for preview even when it is not installed in BBS's normal config folder.</li><li>Choose animations from the searchable left-hand list and preview them on the right. The configured Left/Down/Up/Right keys play direction states; non-looping idle/idle2 mappings alternate at a 120 BPM preview beat. Hover the Anim button and use the wheel for quick cycling without opening the list.</li><li><strong>Save</strong> writes the player JSON and any meaningfully configured opponent JSON together. For a custom definition it also bundles the selected BBS model and texture, replacing the old separate bundle action.</li><li>On the Model tab, <strong>Player Skin Choice</strong> lets the author allow or lock player skin/model overrides per role. It saves as <code>allowPlayerSkinSelection</code> and defaults to allowed for older packs.</li><li>Under Visual settings, <strong>Player Anims</strong> controls the BF/player chart role and <strong>Opponent Anims</strong> controls Dad/opponent. Their searchable selectors use the same live-preview behavior. Both selectors have an independent skin choice: Player Skin applies your live Minecraft skin to the locally controlled performer, while Bot Skin applies it to the solo bot, when the selected BBS form supports player skins and its author allows the choice. A locked or incompatible skin button is disabled. With a Minecraft skin selected, main-model texture keyframes cannot cover the chosen skin; other animation keyframes still run. Play as Opponent still uses Opponent Anims locally and Player Anims for the solo BF bot; multiplayer sends the correct animation selection for each role.</li><li>Use <strong>Play Animation</strong> with <code>attack</code>, or call <code>characterPlayAnim(tag, 'attack')</code>. Run <code>/fnf reload animations</code> after editing files externally.</li></ol>${callout("Names are open-ended", "Idle and sing directions drive normal gameplay, but custom names are supported. The JSON key is the name used by events and Lua.")}`),
    section("sparrow", "Make a Sparrow XML animation", `<ol><li>Export one PNG spritesheet plus Adobe Animate/Sparrow XML from a compatible atlas tool.</li><li>Keep PNG and XML together; usually use the same base filename.</li><li>Give related frames a shared prefix, such as <code>idle0000</code>, <code>idle0001</code>, and <code>idle0002</code>.</li><li>Register that prefix in character JSON or Lua, then play the registered animation name.</li></ol>${code("lua", `makeAnimatedLuaSprite('dancer', 'images/dancer', 0, 0)
addAnimationByPrefix('dancer', 'idle', 'idle', 24, true)
addAnimationByPrefix('dancer', 'cheer', 'cheer', 24, false)
addLuaSprite('dancer')
playAnim('dancer', 'idle', true)`)}`),
    section("machine", "Animate a machine menu sprite", code("lua", `local dancer = ui.animatedSprite(
  'dancer',
  'images/dancer.png',
  'images/dancer.xml',
  0.5, 0.25
)
dancer:addAnimationByPrefix('idle', 'idle', 24, true)
dancer:playAnimation('idle')`)),
    section("troubleshoot", "Troubleshooting", `<ul><li><strong>Nothing plays:</strong> verify the requested prefix exactly matches XML frame names.</li><li><strong>Beat-bop compatibility:</strong> <code>playAnim</code>, <code>objectPlayAnimation</code>, and <code>luaSpritePlayAnimation</code> keep Psych's non-forced default when <code>forced</code> is omitted. Exact Lua-object tags resolve before character aliases, so names such as <code>back_bf</code> and <code>back_gf</code> animate their sprites rather than BF/GF.</li><li><strong>Wrong BBS motion:</strong> verify the mapped <code>state</code> exists on the selected form.</li><li><strong>Opponent is wrong:</strong> create the optional <code>-opp.json</code> override.</li><li><strong>Atlas not found:</strong> keep path case exact and place rich assets in a complete pack.</li><li><strong>Frames wobble:</strong> export stable registration points; Blockified applies Sparrow trim offsets but cannot repair inconsistent source pivots.</li></ul>`)
  );

  pages["custom-notes"] = page("Custom note types", "Documentation · Gameplay", "Attach Lua behavior and declared properties to chart notes.", ["Complete packs", "Lua"],
    section("files", "Files", `<p>Use <code>custom_notetypes/&lt;Type&gt;.lua</code> and a matching <code>.txt</code>. Blockified parses declared properties before <code>onCreate</code>.</p>`),
    section("builtins", "Built-in types", `<ul><li>Hurt Note</li><li>Alt Animation</li><li>No Animation</li><li>GF Sing</li></ul>`),
    section("properties", "Blockified settings", `<p>Types can control texture/splash, health, hit-causes-miss, ignore/block/priority, animations, ratings, alpha, scrolling, offsets, rotation, scale, hit windows, and hit sounds.</p>`),
    section("sustains", "Long notes", `<p><code>goodNoteHit</code> fires repeatedly while a sustain succeeds and stops at its end. Track the note identity when an effect should happen once.</p>`)
  );

  pages.events = page("Chart events", "Documentation · Gameplay", "Blockified events and modifications to compatible Psych events.", ["Minecraft", "3D camera", "Characters"],
    section("new", "Blockified events", `<div class="api-list">
      ${api("Minecraft Command", "V1 command; V2 player/server context. Supports player, opponent, speaker, direction, camera, and character placeholders. Human roles use player selectors; bots and speakers use entity selectors.", "Event")}
      ${api("Camera Zoom", "V1 zoom offset (-1 to 0.9); V2 duration (default 0.5); V3 easing.", "Event")}
      ${api("Camera Focus", "V1 player, opponent, gf, an Add Character tag (Legacy/Minecraft), or blank to release; V2 easing.", "Event")}
      ${api("Camera Behavior", "V1 speed multiplier; V2 easing; V3 animation offsets (true by default, false disables). Blank resets. Used by Minecraft/Legacy presentation and live orbit pivots.", "Event")}
      ${api("Camera Rotation 3D", "V1 pitch; V2 yaw; V3 roll; V4 easing; V5 duration in seconds (blank = 0.5s). While Camera Orbit is active, these axes rotate the camera around its pivot.", "Event")}
      ${api("Camera Orbit", "V1 on/off; V2 follow/pin; V3 pivot X,Y,Z; V4 pivot duration; V5 pivot easing. Follow offsets the live Camera Focus target; pin uses absolute stage-local coordinates. Camera Rotation 3D performs the orbit.", "Event")}
      ${api("Add Character", "Tag, definition, X/Y/Z, rotation, animation, and side.", "Event")}
      ${api("Remove Character", "Removes an extra character by tag.", "Event")}
      ${api("Tween Character", "Tag, target X/Y/Z, duration, easing, and rotation.", "Event")}
      ${api("Directional Shading", "V1 block/fluid shading; V2 entity shading. on keeps vanilla, off flattens directional lighting for the song.", "Event")}
    </div>`),
    section("follow", "Extended Camera Follow Pos", `<p>V1/V2 remain Psych X/Y. Blockified adds V3 Z in blocks, V4 easing, V5 default/override, V6 machine/camera reference, and V7 duration.</p>`),
    section("gf-replacement", "Girlfriend replacement in world modes", `<p>Minecraft and Legacy presentations do not create a built-in GF. Add a character with the case-insensitive tag <code>gf</code> or <code>girlfriend</code> to make it the GF replacement. GF notes and sustains, GF camera sections, Hey!, Set GF Speed, Alt Idle Animation, Play Animation, Change Character, Camera Focus, and character-oriented Lua aliases such as <code>gf</code>, <code>girlfriend</code>, and <code>speakers</code> route to it. FNF presentation keeps its native GF behavior.</p>`),
    section("modified", "Modified compatible events", `<ul><li><strong>Play Animation:</strong> accepts an Add Character tag and BBS character.</li><li><strong>Change Character:</strong> accepts BBS definitions.</li><li><strong>Hey!:</strong> uses Blockified BBS mapping outside FNF presentation.</li></ul>`),
    section("compatible", "Other compatible built-ins", `<p>Hey!, Set GF Speed, Add Camera Zoom, Alt Idle Animation, Screen Shake, Change Scroll Speed, Set Property, and Play Sound are recognized; unchanged behavior is not duplicated.</p>`)
  );

  pages.camera = page("Gameplay camera", "Documentation · Gameplay", "Camera modes, chart control, and world coordinates.", ["3D", "Events", "Lua"],
    section("modes", "Presentation behavior", `<p>Minecraft and Legacy use a 3D world camera with focus, behavior, position, rotation, and zoom events. FNF keeps stage-style presentation while accepting supported event changes.</p>`),
    section("events", "Chart control", `<p>Combine Camera Focus, Camera Behavior, Camera Follow Pos, Camera Rotation 3D, Camera Orbit, and Camera Zoom for authored shots. Camera Follow Pos sets the initial camera position and radius. Camera Orbit only enables/disables and places the pivot; Camera Rotation 3D animates the camera around it on stage X/Y/Z axes. A following pivot uses its XYZ as an offset from the live Camera Focus target, while a pinned pivot uses absolute stage-local XYZ. Re-triggering Orbit tweens to the new pivot with its V4 duration and V5 easing; Camera Focus target changes continue using Camera Behavior. Animation/sing offsets apply by default; Camera Behavior V3 can disable them.</p>`),
    section("orbit-authoring", "Author an orbit in free cam", `<ol><li>Open the <strong>Camera</strong> tab in free cam and position the camera; Camera Follow Pos preserves this position/radius.</li><li>Set Camera mode to Orbit. Use MMB or Numpad <code>.</code> to place the pivot, then choose Follow focus or Pinned and fine-tune Pivot X/Y/Z.</li><li>With no object selected, press Ctrl+C and paste into the chart editor. The copied Orbit event establishes the pivot.</li><li>Add Camera Rotation 3D events to animate the camera around that pivot.</li></ol>`),
    section("lua", "Lua control", `<p>World-camera objects use the speaker origin and a fixed scale of 64 Lua pixels per Minecraft block.</p><div class="api-list">${api("cameraOrbit(pinned, x, y, z, duration, easing)", "Enables orbit mode or tweens an active pivot. false uses focus-relative XYZ; true uses pinned absolute stage-local XYZ.", "Lua")}${api("startCameraOrbit(...)", "Alias of cameraOrbit.", "Lua")}${api("stopCameraOrbit()", "Disables orbit mode and resumes the normal focus/follow camera.", "Lua")}</div>`)
  );

  pages.rollback = page("Playstate rollback", "Documentation · Gameplay", "Restore Minecraft state after finishing, quitting, or giving up.", ["Copy-on-write", "Commands"],
    section("state", "Captured state", `<p>The playstate records required player/world state, including inventory, each participant's game mode, and synchronous block changes produced during the session.</p>`),
    section("blocks", "Modified blocks", `<p>Block state is captured copy-on-write: the original is saved on the first affected change. A chart command such as <code>/setblock ... air</code> can restore what existed before the song.</p>`),
    section("explosions", "Explosions", callout("Timing boundary", "Immediate command-driven changes inside the active transaction can restore. Delayed TNT or creeper explosions outside it are not guaranteed rollback coverage.")),
    section("ends", "End paths", `<p>The same restoration runs after normal finish, quit, cancellation, or giving up after a loss. Restarting normal solo gameplay also restores the pre-song player/world baseline and participant game modes, clears executed command-event history, recreates session actors, and then rebuilds PlayState. Editor playtests rebuild locally because they have no server song session.</p>`)
  );

  pages.multiplayer = page("Multiplayer", "Documentation · Gameplay", "Server-authoritative sessions with streamed charts and audio.", ["LAN", "Server authority"],
    section("authority", "Authority", `<p>The server owns session state and validates transitions. Clients synchronize around the authoritative session.</p>`),
    section("transfer-safety", "Dedicated-server content boundary", `<p>Dedicated servers may transfer only chart JSON, event JSON, and song audio; gameplay Lua and rich mod assets are disabled. A song manifest may total at most 100 MiB. The client accepts only requested manifest members, derives the allowed chunk count from each declared size, bounds chunk size/count/concurrency, then verifies final size and SHA-1 before atomically publishing a cache file. Singleplayer and LAN keep local Lua/mod behavior.</p>`),
    section("delivery", "Song delivery", `<p>Charts/audio can stream to clients and be cached, reducing the need for identical preinstalled lightweight songs.</p>`),
    section("machines", "Machine boundary", `<p>Custom menus and machine editing are for singleplayer/LAN. Dedicated servers exclude authoring and menu Lua while retaining compatible library behavior.</p>`)
  );

  pages["lua-overview"] = page("Lua overview", "Documentation · Lua", "Where scripts run and where Blockified differs from upstream engines.", ["Psych compatibility", "Complete packs"],
    section("scope", "Script scope", `<p>Lua runs only from complete packs. Locations include <code>scripts</code>, <code>stages</code>, <code>custom_notetypes</code>, <code>custom_events</code>, <code>data/&lt;song&gt;</code>, and song folders.</p>`),
    section("baseline", "Baseline compatibility", `<p>Blockified exposes broad Psych-style callbacks, variables, properties, tweens, timers, sound, note, and character control. Consult upstream docs for unchanged calls.</p>`),
    section("extensions", "Blockified extensions", `<p>Added APIs cover Minecraft commands, FOV/render distance, extra characters, world objects, 3D camera behavior, and machine menus.</p>`),
    section("trust", "Execution boundary", `<p>Lua is executable content. Only install packs you trust.</p>`)
  );

  pages["lua-blockified"] = page("Blockified Lua API", "Documentation · Lua", "Minecraft, camera, and extra-character functions.", ["Blockified-only"],
    section("minecraft", "Minecraft and camera settings", `<div class="api-list">
      ${api("setRenderDistance(chunks)", "Sets the gameplay render-distance override.")}
      ${api("getRenderDistance()", "Returns active render distance.")}
      ${api("setFOV(degrees)", "Sets gameplay FOV.")}
      ${api("getFOV()", "Returns active FOV.")}
      ${api("runMinecraftCommand(command)", "Runs a command through Blockified session context.")}
      ${api("runCommand(command)", "Command-execution alias.")}
    </div>`),
    section("chunk-loader-properties", "Chunk Loader Point properties", `<p>Address a placed point through <code>chunkLoadPoints.&lt;tag&gt;.&lt;property&gt;</code>. Lua changes are server-authoritative and roll back when the song session ends.</p><div class="api-list">
      ${api("getProperty('chunkLoadPoints.stage.enabled')", "Reads enabled state. Aliases: active and on.")}
      ${api("setProperty('chunkLoadPoints.stage.enabled', true)", "Enables or disables the point.")}
      ${api("setProperty('chunkLoadPoints.stage.radius', 4)", "Changes its radius, clamped to 0-12 chunks.")}
      ${api("setProperty('chunkLoadPoints.stage.tag', 'second_stage')", "Renames it; tags must stay unique in the dimension.")}
      ${api("getProperty('chunkLoadPoints.stage.x|y|z')", "Reads the block's world coordinates.")}
    </div>${code("lua", `setProperty('chunkLoadPoints.stage_right.enabled', true)
setProperty('chunkLoadPoints.stage_right.radius', 4)`)}`),
    section("characters", "Extra characters", `<div class="api-list">
      ${api("addBlockifiedCharacter(tag, definition, x, y, z, rotation, animation, side)", "Creates an extra 2D/BBS world character.")}
      ${api("makeBlockifiedCharacter(...)", "Creation compatibility alias.")}
      ${api("removeBlockifiedCharacter(tag)", "Removes the tagged character.")}
      ${api("blockifiedCharacterExists(tag)", "Checks whether a tag exists.")}
      ${api("setBlockifiedCharacterPosition(tag, x, y, z)", "Moves a character.")}
      ${api("setBlockifiedCharacterRotation(tag, degrees)", "Rotates a character.")}
      ${api("setProperty('tag.rotation.x', degrees)", "Pitches an active BBS form; works for extra tags and main boyfriend/dad performers.")}
      ${api("setProperty('tag.rotation.z', degrees)", "Rolls an active BBS form without changing player physics or camera.")}
      ${api("setProperty('tag.scale.x|y|z', amount)", "Scales an active BBS form independently on each render axis; works for extra and main performers.")}
      ${api("changeBlockifiedCharacter(tag, definition)", "Changes definition while retaining the tag.")}
    </div>${callout("BBS rotation and scale", "Existing rotation, rotation.y, angle, Add Character rotation, and setBlockifiedCharacterRotation remain yaw. Free cam keeps plain R as yaw; constrain with R X or R Z, or use trackball rotation, to tilt a BBS form. Plain S scales a BBS form uniformly in XYZ; S X/Y/Z constrains one axis, Shift+axis scales the other two, and Alt+S resets scale. Scaling is visual only and does not resize collision.")}`),
    section("example", "Example", code("lua", `addBlockifiedCharacter('FNFBF', 'bf', 2.15, 0.79, -0.5, 0, 'idle', 'opponent')
setProperty('FNFBF.scale.x', 0.09)
setProperty('FNFBF.scale.y', 0.09)
setProperty('FNFBF.billboard', false)
setProperty('FNFBF.lighting', false)`)),
    section("multiple", "Animate multiple tags", code("lua", `local singers = {'FNFBF', 'SecondSinger', 'ThirdSinger'}
for _, tag in ipairs(singers) do
  triggerEvent('Play Animation', 'singRIGHT', tag)
end`)),
    section("flat", "Full-bright and directional shading", `<p>A <code>fullbright</code> property renders a target unlit — always at maximum brightness, ignoring world light and time of day — for a flat, evenly-lit look. It works the same on the main performers (boyfriend, dad, gf), extra characters, and world sprites and text. <code>flatShading</code> and <code>unlit</code> are aliases; it is the inverse of the existing <code>lighting</code> property.</p><div class="api-list">
      ${api("setProperty('boyfriend.fullbright', true)", "Full-brights a performer (BBS form lighting = 0). false restores normal lighting.")}
      ${api("setProperty('sprite.fullbright', true)", "Same for a world sprite/text object; equivalent to lighting = false.")}
      ${api("setFlatShading(on) / setFullbright(on)", "Global: full-brights boyfriend, dad, gf, and every existing world object at once.")}
      ${api("triggerEvent('Directional Shading', blocks, mobs)", "Changes both channels through the same built-in event used by charts. Use on for vanilla shading or off for flat shading.")}
      ${api("getBlockShading() / getMobShading()", "Returns each current gameplay-scoped shading state.")}
    </div>${code("lua", `-- Flat, unshaded characters (matches 2D FNF art regardless of world light).
setProperty('boyfriend.fullbright', true)
setProperty('dad.fullbright', true)

-- Or all main performers and world objects at once:
setFlatShading(true)

-- Keep day/night and torch brightness, but remove directional shading.
triggerEvent('Directional Shading', 'off', 'off')`)}${callout("Independent controls", "Directional shading preserves Minecraft's lightmap. Block flattening also removes smooth ambient-occlusion corners; entity shadow blobs and shader-pack shadows remain separate. All states restore when gameplay ends.")}`),
    section("hud", "HUD, rating, and time", `<p>Rating, combo, score, and time are exposed as Psych-style globals updated every frame: <code>ratingName</code>, <code>ratingFC</code>, <code>combo</code>, <code>score</code>, <code>misses</code>, <code>curStep</code>, <code>curBeat</code>, <code>curSection</code>, <code>curDecBeat</code>, <code>curDecStep</code>, <code>songLength</code>, and <code>getSongPosition()</code>. Meter-aware scripts can also read <code>curMeterBeat</code>, <code>curBeatInMeasure</code>, <code>curMeasure</code>, <code>timeSignatureNumerator</code>, and <code>timeSignatureDenominator</code>. Existing <code>curBeat</code>/<code>onBeatHit</code> stay quarter-note based for Psych compatibility.</p><div class="api-list">
      ${api("setHudStyle(style)", "Overrides the HUD style for this song: 'fnf', 'vanilla', 'default', 'abbreviated', 'numbers', or 'none'. Blockified-only.")}
      ${api("getHudStyle()", "Returns the active HUD style.")}
      ${api("setProperty('rating.visible', false)", "Hides Blockified's built-in rating/combo popups so you can draw your own.")}
      ${api("getProperty('rating.x') / getProperty('rating.y')", "Reads the player's configured Blockified Rating Position in the fixed 1280x720 Lua HUD canvas.")}
      ${api("setProperty('timeBar.visible', false)", "Hides the magenta time-bar fill.")}
      ${api("setProperty('timeTxt.visible', false)", "Hides the song-title text on the time bar.")}
      ${api("showTimeBar(false)", "Convenience: hides the whole time bar (fill + title) at once.")}
      ${api("function onMeterBeatHit(beatInMeasure, numerator, denominator)", "Fires on each written meter pulse; beatInMeasure is zero-based.")}
      ${api("function onMeasureHit(measure)", "Fires on each bar downbeat; measure is zero-based.")}
      ${api("function onRatingPopup(name, combo)", "Fires when a note is judged (name = 'sick'/'good'/'bad'/'shit'), even while the built-in popup is hidden — spawn your own popup here.")}
    </div>${code("lua", `function onCreatePost()
  setHudStyle('none')                        -- start from a clean HUD
  setProperty('rating.visible', false)       -- draw ratings yourself
  showTimeBar(false)                         -- and the time bar
  makeLuaText('myrating', '', 400, 260, 260, 60)
  addLuaText('myrating')
end

function onRatingPopup(name, combo)
  setTextString('myrating', name .. (combo > 1 and ' x' .. combo or ''))
end

function onUpdate(elapsed)
  -- your own time readout
  local left = math.max(0, (songLength - getSongPosition()) / 1000)
  setTextString('mytime', string.format('%d:%02d', left // 60, left % 60))
end`)}${callout("Time bar", "Blockified's time bar is the magenta progress fill plus the song title, at the top on upscroll and the bottom on downscroll. The two visible flags hide each part; showTimeBar(false) hides both.")}`)
  );

  pages["lua-world"] = page("World-camera Lua objects", "Documentation · Lua", "Put sprites, atlases, text, and characters in Minecraft space.", ["3D transforms", "64 px = 1 block"],
    section("coordinates", "Coordinate system", `<ul><li>Origin: active speaker/stage origin.</li><li>64 Lua pixels = one block.</li><li>X: stage-right.</li><li>Y: downward.</li><li>Z: toward the stage camera.</li></ul>`),
    section("camera", "World camera", `<div class="api-list">${api("setObjectCamera(tag, 'world')", "Renders an object in world space.")}${api("doTweenZ(tweenTag, objectTag, z, duration, ease)", "Tweens world Z.")}</div>`),
    section("properties", "World properties", `<p>Objects expose 3D rotation, billboard, lighting, XYZ position, and scale through <code>setProperty</code>. During gameplay, sprites, graphics, Sparrow XML spritesheets, and 2D world characters use client-side entity hosts; free-cam previews stay direct editor renders. The hosts participate in IRLights' item-entity shadow-caster path. IRLights projected shadows default on and can be toggled independently with <code>tag.irlightsShadows</code>, <code>setWorldSpriteIRLightsShadows</code>, or <code>setCharacterIRLightsShadows</code> for a 2D world character. Entity gravity, collision, and Minecraft's blob shadow default off and can be enabled with <code>tag.gravity</code>, <code>tag.collision</code>, and <code>tag.shadow</code>, or <code>setWorldSpriteGravity</code>, <code>setWorldSpriteCollision</code>, and <code>setWorldSpriteShadows</code>. While physics is active, Lua XYZ follows the entity.</p>`),
    section("fonts", "Custom fonts", `<p>3D/world text supports custom fonts, resolving the active pack's <code>fonts</code> then global Blockified fonts.</p>`),
    section("noop", "Text compatibility", callout("No-op means accepted, not applied", "Some compatibility functions, including text alignment/border in affected paths, may return without changing rendering."))
  );

  pages["lua-callbacks"] = page("Lua callbacks and notes", "Documentation · Lua", "Blockified callback timing that matters for chart logic.", ["Sustain notes"],
    section("good", "Note-hit roles", `<p>Psych callback names remain tied to chart characters in every play mode: BF/player-role notes call <code>goodNoteHit</code>, while Dad/opponent-role notes call <code>opponentNoteHit</code>. Play as Opponent changes input and scoring ownership, not those Lua identities. <code>mustPress</code>, strum groups, <code>boyfriend</code>/<code>dad</code>, <code>mustHitSection</code>, <code>health</code>, and <code>healthBar.percent</code> therefore stay compatible with the chart; scoring, misses, input, and failure follow the controlled side. For long notes, the matching callback fires repeatedly while the sustain succeeds, then stops when it ends.</p>${code("lua", `function goodNoteHit(index, direction, noteType, isSustainNote)
  if isSustainNote then
    -- Repeats for successful sustain segments.
  end
end`)}`),
    section("once", "Run once instead", `<p>Check <code>isSustainNote</code> and/or track the note identifier when an effect belongs only to the head.</p>`),
    section("events", "Custom event scripts", `<p>Place event Lua under <code>custom_events</code> in a complete pack. Matching declared properties are available before creation callbacks where applicable.</p>`)
  );

  pages["machine-lua"] = page("Machine Lua API", "Documentation · Lua", "Create menus and connect them to Blockified screens or songs.", ["Lua UI", "Sparrow XML"],
    section("widgets", "Widgets", `<div class="api-list">
      ${api("ui.panel(id, x, y, width, height)", "Creates a panel.", "Machine UI")}
      ${api("ui.label(id, text, x, y)", "Creates text.", "Machine UI")}
      ${api("ui.button(id, text, x, y, width, height)", "Creates a button with optional onClick.", "Machine UI")}
      ${api("ui.toggle(id, text, x, y, value)", "Creates a boolean control.", "Machine UI")}
      ${api("ui.slider(id, x, y, width, min, max, value)", "Creates a numeric control.", "Machine UI")}
      ${api("ui.image(id, path, x, y, width, height)", "Displays an image.", "Machine UI")}
      ${api("ui.sprite(id, path, x, y)", "Displays a static sprite.", "Machine UI")}
      ${api("ui.animatedSprite(id, image, xml, x, y)", "Loads a Sparrow XML atlas.", "Machine UI")}
      ${api("ui.graph(id, shape, x, y, width, height, color)", "Draws a generated rectangle, circle/ellipse, line, triangle, or polygon. ui.graphic is an alias.", "Machine UI")}
    </div><p>Machine menus use a fixed 1280x720 canvas that scales uniformly and stays centered. Minecraft GUI scale does not change layout or physical widget size; window resolution scales the complete canvas proportionally. Normalized X/Y values use canvas dimensions; larger values are canvas pixels. <code>screenWidth</code> and <code>screenHeight</code> are always <code>1280</code> and <code>720</code>. Non-16:9 screens letterbox the canvas.</p><p>Widgets use creation order by default. Higher <code>order</code> values render in front and receive clicks first. Change a layer through <code>widget.order</code> or <code>setObjectOrder('widgetId', order)</code>.</p>${code("lua", `local title = ui.label('title', 'Behind cover', 0.5, 0.7)
local cover = ui.image('cover', 'images/cover.png', 0.5, 0.5, 180, 180)
setObjectOrder('title', 0)
setObjectOrder('cover', 1)`)}`),
    section("graphs", "Generated graphics", `<p>Graphs require no image asset. Omit <code>shape</code> for a rectangle. They share widget position, size, visibility, alpha, angle, color, tween, and layer fields. Mutable graph-only fields are <code>borderSize</code>, <code>borderColor</code>, and line <code>thickness</code>. Polygon <code>points</code> are local pixel offsets from the graph center and accept nested pairs or one flat number list.</p>${code("lua", `local card = ui.graph('card', 'rectangle', 0.5, 0.5, 320, 180, '28003F')
card.borderSize = 4
card.borderColor = 0xFF00FF

local dot = ui.graph('dot', 'circle', 0.25, 0.3, 48, 48, 'FFFFFF')

local slash = ui.graph('slash', 'line', 0.5, 0.5, 180, 60, 'FFFFFF')
slash.thickness = 5
slash.angle = -15

local badge = ui.graph('badge', 'polygon', 0.75, 0.3, 140, 120, 'E600FF')
badge.points = {{0, -60}, {70, 45}, {0, 60}, {-70, 45}}
setObjectOrder('badge', 10)`)}`),
    section("actions", "Machine actions", `<div class="api-list">
      ${api("machine.openSongSelect([returnTo])", "Opens the included selector; optional menu, world, or selector sets the next solo song's exit destination.", "Machine")}
      ${api("machine.getSongs()", "Returns authoritative available songs.", "Machine")}
      ${api("machine.getSong(id) / machine.hasSong(id)", "Reads or checks one song.", "Machine")}
      ${api("machine.playSong(id)", "Starts an available song directly.", "Machine")}
      ${api("machine.openSongDetails(id)", "Opens song details.", "Machine")}
      ${api("machine.openSettings() / machine.openOptions()", "Opens settings.", "Machine")}
      ${api("machine.openCharacterEditor()", "Opens character editor.", "Machine")}
      ${api("machine.openChartEditor()", "Opens chart editor.", "Machine")}
      ${api("machine.setSongExitTarget(target)", "Sets solo-song return target: menu, world, or selector. Direct custom-menu play defaults to menu.", "Machine")}
      ${api("machine.getSongExitTarget()", "Returns menu, world, or selector.", "Machine")}
      ${api("machine.join(...) / machine.save() / machine.close()", "Join, persist, or close.", "Machine")}
    </div>`),
    section("direct", "Direct-song button", code("lua", `local play = ui.button('play', 'Play Tutorial', 0.5, 0.42, 180, 24)
function play:onClick()
  if machine.hasSong('tutorial') then
    machine.playSong('tutorial')
  end
end`)),
    section("return", "Choose where quitting returns", `<p>Songs launched directly or through <code>machine.openSongSelect()</code> return to the custom menu by default. Change the destination before opening or playing:</p>${code("lua", `-- Return to Minecraft with every menu closed.
machine.setSongExitTarget('world')

-- This also applies if the next call is machine.openSongSelect().
-- Other values: 'menu' or 'selector'.`)}` + `<p>One direct-play call can override it with an options table:</p>` + code("lua", `machine.playSong('tutorial', 'normal', {
  playAs = 'player',
  look = 'minecraft',
  returnTo = 'world'
})`) + callout("Duets", "Multiplayer duets still return to the world to prevent host/guest menu contention.")),
    section("fonts", "Custom fonts and shadows", `<p>Labels, buttons, toggles, and sliders accept TTF/OTF fonts through their mutable <code>font</code> field. <code>fontScale</code> changes rendered text size without changing the widget hitbox. Text uses Minecraft's shadow by default; set <code>shadow = false</code> to disable it per widget.</p>${code("lua", `local title = ui.label('title', 'Earrings Machine', 0.5, 0.15)
title.font = 'VCR_OSD_MONO.ttf'
title.fontScale = 2.0
title.shadow = false

local play = ui.button('play', 'Play', 0.5, 0.5, 180, 24)
play.font = 'VCR_OSD_MONO.ttf'`)}<p>Resolution order:</p><ol><li><code>machines/&lt;machine&gt;/fonts/</code></li><li>Owning mod's <code>fonts/</code></li><li><code>config/fnfmod/mods/fonts/</code></li></ol>${callout("Safe fallback", "Missing or invalid fonts use Minecraft's default font and write one warning to the log.")}`),
    section("tweens", "Widget tweening", `<p>Machine menus expose Psych-style tagged tweens for widget properties. Supported functions are <code>doTweenX</code>, <code>doTweenY</code>, <code>doTweenAlpha</code>, <code>doTweenAngle</code>, <code>doTweenWidth</code>, <code>doTweenHeight</code>, <code>doTweenFontScale</code>, <code>doTweenColor</code>, and <code>cancelTween</code>.</p>${code("lua", `local cover = ui.image('cover', 'images/cover.png', -0.2, 0.5, 100, 100)

function onOpen()
  doTweenX('cover-enter', 'cover', 0.5, 1, 'sineOut')
end

function onTweenCompleted(tag)
  if tag == 'cover-enter' then
    doTweenAlpha('cover-fade', 'cover', 0.5, 0.4, 'linear')
  end
end`)}` + `<p>Completion also calls an optional widget method: <code>function cover:onTweenCompleted(tag)</code>. Reusing a tag replaces its active tween; <code>cancelTween(tag)</code> stops it without completion callbacks.</p>` + callout("Coordinate modes", "X/Y tweens can cross between pixel and normalized coordinates. Blockified converts mixed endpoints to canvas pixels during interpolation and restores the requested final value without snapping.")),
    section("timers", "Timers", `<p>Machine menus support Psych-style timers, matching the song Lua. <code>runTimer(tag, seconds, loops)</code> starts a timer that fires <code>onTimerCompleted(tag, loops, loopsLeft)</code> after each interval; <code>loops</code> defaults to 1 (<code>seconds</code> to 1). Reusing a tag restarts that timer, and <code>cancelTimer(tag)</code> stops it. Timers run in real time and pair well with <code>onOpen</code>.</p><div class="api-list">
      ${api("runTimer(tag, [seconds], [loops])", "Starts (or restarts) a timer; fires onTimerCompleted each interval, loops times.", "Machine")}
      ${api("cancelTimer(tag)", "Stops a timer without firing its callback.", "Machine")}
    </div>${code("lua", `function onOpen()
  runTimer('tick', 1, 3)   -- fire 3 times, one second apart
end

function onTimerCompleted(tag, loops, loopsLeft)
  if tag == 'tick' then
    -- loops = times fired so far, loopsLeft = remaining
    if loopsLeft == 0 then playSound('', 'sounds/done') end
  end
end`)}`),
    section("animation", "Animated sprites", `<p>Animated sprites support add-by-name/prefix, play/pause/resume/stop, frame selection, animation listing, FPS/loop, transform, tint, alpha, antialiasing, and <code>onComplete</code>.</p>${code("lua", `local dancer = ui.animatedSprite('dancer', 'images/dancer.png', 'images/dancer.xml', 0.5, 0.25)
dancer:addAnimationByPrefix('idle', 'idle', 24, true)
dancer:playAnimation('idle')`)}`),
    section("sound", "Sound", `<p>Menus can play OGG sound effects and music. Files resolve like every other menu asset: the path is tried in the machine folder first, then in the owning mod's root, so shared sounds can live in <code>&lt;mod&gt;/sounds/</code>. The <code>.ogg</code> extension is optional, matching Psych's convention.</p><div class="api-list">
      ${api("playSound(tag, name, [volume], [loop])", "Plays an OGG. Like the widget calls, the tag and path come first. volume defaults to 1.0 and follows the master sound slider. Give a tag to control the sound later, or an empty tag for a fire-and-forget effect. Set loop true for looping music. Returns true on success.", "Machine")}
      ${api("stopSound(tag)", "Stops and releases a tagged sound.", "Machine")}
      ${api("pauseSound(tag) / resumeSound(tag)", "Pauses or resumes a tagged sound.", "Machine")}
      ${api("setSoundVolume(tag, volume)", "Sets a tagged sound's volume from 0 to 1.", "Machine")}
      ${api("precacheSound(name)", "Decodes and caches a sound up front to avoid a first-play hitch.", "Machine")}
    </div><p>An optional <code>onSoundFinished(tag)</code> global is called when a tagged sound ends on its own.</p>${code("lua", `-- Click feedback and looping menu music.
local play = ui.button('play', 'Play', 0.5, 0.5, 180, 24)

function onOpen()
  precacheSound('sounds/select')
  playSound('menuMusic', 'sounds/music', 0.6, true)
end

function play:onClick()
  playSound('click', 'sounds/select')
  machine.playSong('tutorial')
end

function onClose()
  stopSound('menuMusic')
end`)}${callout("Machine folder or mod root", "A bare path like <code>sounds/click.ogg</code> is looked up in <code>machines/&lt;machine&gt;/sounds/</code> first, then in <code>&lt;mod&gt;/sounds/</code>. Force one with a prefix: <code>mod:sounds/click.ogg</code> or <code>machine:sounds/click.ogg</code>. This applies to every menu asset (images, sprites, atlases, sounds), and paths must stay inside the active mod.")}`),
    section("cursor", "Cursor", `<p>Menus can read the cursor position and test what it is over. All positions are in canvas (1280x720) coordinates, the same space as widget <code>x</code>/<code>y</code>. The live <code>cursor</code> table is refreshed every frame, and every widget gets a live <code>hovered</code> boolean.</p><div class="api-list">
      ${api("cursor.x / cursor.y", "Current cursor position in canvas coordinates.", "Machine UI")}
      ${api("cursor.down", "True while the left mouse button is held.", "Machine UI")}
      ${api("cursor.overId", "Id of the topmost visible widget under the cursor, or \"\" if none.", "Machine UI")}
      ${api("getMouseX() / getMouseY()", "Cursor position in canvas coordinates.", "Machine UI")}
      ${api("isMouseDown([button])", "True if a mouse button is held: 0 left (default), 1 right, 2 middle.", "Machine UI")}
      ${api("mouseOver(widgetId)", "True if the cursor is over that widget's bounds and it is visible.", "Machine UI")}
      ${api("mouseInside(x, y, width, height)", "True if the cursor is inside a center-anchored rectangle (x/y center, size in canvas pixels).", "Machine UI")}
      ${api("getHoveredObject()", "Id of the topmost visible widget under the cursor, or \"\".", "Machine UI")}
      ${api("widget.hovered", "Live per-widget boolean, true while the cursor is over it.", "Machine UI")}
    </div><p>Hover can also be handled with callbacks that fire once when the cursor enters or leaves a widget, so you do not have to track the previous state yourself. Each widget can define <code>onHover</code>/<code>onHoverExit</code> methods (called with the widget as <code>self</code>, like <code>onClick</code>), and the globals <code>onHover(id)</code>/<code>onHoverExit(id)</code> fire for any widget. Callbacks run before drawing, so they may safely tween, add, or remove widgets.</p><div class="api-list">
      ${api("function widget:onHover()", "Fires once when the cursor enters the widget.", "Machine UI")}
      ${api("function widget:onHoverExit()", "Fires once when the cursor leaves the widget.", "Machine UI")}
      ${api("onHover(id) / onHoverExit(id)", "Global callbacks for entering/leaving any widget.", "Machine UI")}
    </div><p>Clicks and presses use the same callback style. <code>onClick</code> now works on <em>any</em> widget, not just buttons, so an image or panel can act as a button by defining it. <code>onMouseDown</code>/<code>onMouseUp</code> fire on press and release and receive the button (0 left, 1 right, 2 middle). A widget with no handler and that is not a control lets the click fall through to whatever is under it.</p><div class="api-list">
      ${api("function widget:onClick()", "Left-click on any widget that defines it.", "Machine UI")}
      ${api("function widget:onMouseDown(button)", "Press on the widget; button is 0/1/2.", "Machine UI")}
      ${api("function widget:onMouseUp(button)", "Release over the widget.", "Machine UI")}
      ${api("onClick(id)", "Global: any widget was left-clicked.", "Machine UI")}
      ${api("onMouseDown(id, button) / onMouseUp(id, button)", "Global press/release on a widget.", "Machine UI")}
    </div>${code("lua", `-- An image that behaves like a button.
local cover = ui.image('cover', 'images/cover.png', 0.5, 0.4, 360, 360)
function cover:onClick()
  machine.playSong('tutorial')
end
function cover:onMouseDown(button)
  if button == 1 then machine.openSongDetails('tutorial') end   -- right-click
end`)}${code("lua", `local play = ui.button('play', 'Play', 0.5, 0.5, 220, 48)
local cover = ui.image('cover', 'images/cover.png', 0.5, 0.4, 360, 360)

-- Grow the cover when the play button is hovered, no per-frame tracking.
function play:onHover()
  doTweenWidth('coverW', 'cover', 396, 0.3, 'expoOut')
  doTweenHeight('coverH', 'cover', 396, 0.3, 'expoOut')
end

function play:onHoverExit()
  doTweenWidth('coverW', 'cover', 360, 0.3, 'expoOut')
  doTweenHeight('coverH', 'cover', 360, 0.3, 'expoOut')
end`)}`),
    section("blur", "Menu background blur", `<p>Custom machine menus never draw Minecraft's dark in-world menu tint, so the scene behind the menu stays clean. This is always on for custom menus and is not configurable.</p><p>A menu can also control Minecraft's background <em>blur</em> for its own screen, independent of the player's accessibility setting. Until a script touches it, the player's own blur is kept. Radius is fractional; a value below <code>1</code> means no blur, larger values blur more. <code>resetMenuBlur</code> hands control back to the player's setting.</p><div class="api-list">
      ${api("setMenuBlur(radius)", "Sets the blur radius. Below 1 disables the blur.", "Machine")}
      ${api("enableMenuBlur([radius])", "Enables blur; radius defaults to the player's setting, or 8 if that is off.", "Machine")}
      ${api("disableMenuBlur()", "Turns the blur off for this menu.", "Machine")}
      ${api("getMenuBlur()", "Returns the current override, or the player's exact configured radius (including 0 for Off).", "Machine")}
      ${api("resetMenuBlur()", "Releases control back to the player's blur setting.", "Machine")}
      ${api("doTweenMenuBlur(tag, radius, [duration], [ease])", "Tweens the blur radius. Completion calls onTweenCompleted(tag); cancelTween(tag) stops it.", "Machine")}
    </div>${code("lua", `function onOpen()
  setMenuBlur(0)                              -- start sharp
  doTweenMenuBlur('blurIn', 12, 0.6, 'sineOut')  -- ease the blur in
end

function play:onClick()
  doTweenMenuBlur('blurOut', 0, 0.3, 'sineIn')   -- clear it before playing
end`)}${callout("Scope", "The override applies only while this machine menu is open. Leaving the menu restores the player's normal blur automatically.")}`),
    section("loading", "Asset preloading", `<p>Before a custom menu appears, Blockified silently preloads every asset its widgets reference — images, static and animated sprites, and fonts — plus every sound named by a literal path in <code>playSound</code> or <code>precacheSound</code> anywhere in the script (including inside click handlers), so a sound played later does not lag on first play. Heavy image, atlas, and sound decoding runs on a background thread so the game keeps running instead of freezing; only the quick final upload happens on the main thread, spread across frames. During this brief gate the player cannot move or look around and nothing is drawn yet, so loading never freezes an open menu or desyncs tweens. <code>onOpen</code> runs the instant loading finishes, so tweens started there stay in sync.</p>${callout("Cancel with Esc", "Pressing Esc while a menu is still loading cancels the background decode and leaves, without ever opening the menu.")}<p>Sound paths are found by scanning for literal strings, so <code>playSound('theme', 'sounds/menu.ogg')</code> preloads automatically. A path built from a variable cannot be detected — precache those explicitly, or reference them at the top level. Widgets and assets first created inside <code>onOpen</code> still load on demand.</p>`),
    section("preset", "Minimal preset", code("lua", `-- Blockified machine menu
local title = ui.label('title', 'Earrings Machine', 0.5, 0.16)
local play = ui.button('play', 'Choose Song', 0.5, 0.42, 180, 24)
function play:onClick()
  machine.openSongSelect()
end`))
  );

  pages["lua-compatibility"] = page("Lua compatibility limits", "Documentation · Lua", "Calls Blockified accepts partially or does not implement.", ["Compatibility"],
    section("unsupported", "Not implemented", `<ul><li>HScript/Haxe execution.</li><li>GLSL shaders.</li><li>FlxAnimate APIs.</li><li>Video/dialogue systems.</li><li>General Flixel groups.</li><li>Gamepad-specific APIs.</li><li>Some timebar, story, and loadSong flows.</li></ul>`),
    section("noop", "No-op calls", `<p>A no-op is accepted so Lua continues, but it makes no visual/state change. Text alignment/border are examples in affected render paths.</p>`),
    section("report", "Report a gap", `<p>Include the call, minimal Lua, expected upstream behavior, observed behavior, and log.</p>`)
  );

  pages["chart-editor"] = page("Chart editor", "Documentation · Tools", "Edit charts with musical meter, timing, audio analysis, navigation, and compatibility tools.", ["In-game editor", "Waveforms", "Metronome"],
    section("open", "Open", api("/fnf editor [song]", "Opens the editor, optionally loading a song.", "Command")),
    section("scope", "Editing scope", `<p>Edit note placement, sustain length, sections, BPM/timing, event lanes, metadata, note types, skin choices, and Blockified camera/event values. The Note tab's scrollable Note Type dropdown includes Normal, every hardcoded Psych type, custom <code>custom_notetypes/*.lua</code>/<code>*.txt</code> filenames, and types already used by the chart. <strong>Apply Selected Note</strong> updates the primary note's time, sustain, and type; <strong>Apply Notetype to Selected</strong> changes only the type on every selected note. Both explicit actions support undo. Time signatures affect runtime meter accents and bops, but do not move notes, change judgement, or reinterpret Psych-compatible quarter-note BPM. Editor analysis and playback controls remain authoring-only.</p>`),
    section("audio", "Load the exact song audio", `<p><strong>Choose Song Audio</strong> accepts multiple files at once. Recognized instrumental and vocal names are assigned to <code>Inst.ogg</code>, <code>Voices.ogg</code>, <code>Voices-Player.ogg</code>, or <code>Voices-Opponent.ogg</code>; arbitrary source names are renamed only in the eventual saved copy. When every selected name is unrecognized, the first selection becomes Inst and the rest are discarded. Non-OGG input is converted to usable Vorbis OGG through FFmpeg (using BBS's configured encoder path when available), without modifying the originals. Selecting exact song audio no longer requires making a placeholder folder or reloading songs first. Modified/cached songs keep their <code>original_directory.txt</code> origin, so assets and saves continue targeting the source content.</p>`),
    section("waveforms", "Waveforms and onset markers", `<p>Waveforms are independently toggleable and remain aligned while zooming and using either offset. Inst renders in the middle and defaults to <code>#0000FF</code>; change it with the in-game color picker. Opponent and player stems render on their matching sides using Psych-compatible character <code>healthbar_colors</code>. A combined <code>Voices.ogg</code> renders on the player side. Optional transient/onset markers expose likely beats without moving notes.</p>`),
    section("timing", "Meter, metronome, and BPM", `<p>Set song or section time signatures while retaining a 4/4 default for imported Psych charts. Native V-Slice <code>timeChanges</code> and Codename <code>beatsPerMeasure</code>/<code>stepsPerBeat</code> metadata plus <code>Time Signature Change</code> events are imported. During gameplay the denominator chooses the written pulse and the numerator chooses the bar: character dances and health-icon bops follow each pulse, while automatic camera zoom accents the first pulse of each bar. A section signature change starts a fresh bar. For example, 6/8 produces six eighth-note pulses per camera accent; 2/2 produces two half-note pulses. Notes, judgement windows, BPM, <code>curBeat</code>, and <code>onBeatHit</code> remain quarter-note based for compatibility. The metronome mirrors the same pulse/downbeat model and uses a volume slider. Tap BPM works in fresh groups of three taps: taps 1–2 collect timing, tap 3 creates a value stored to two decimals, and the next tap starts a new group. Automatic BPM estimation is only a suggestion until applied.</p>`),
    section("playback", "Playback and navigation", `<ul><li>Playback-rate slider: slow down or speed up editor playback without changing chart gameplay.</li><li>Charting Offset: editor-only milliseconds added to the Song offset for playback, waveforms, metronome, and visual alignment. It is stored in <code>options.json</code>; only the Song offset is saved to the chart and used by gameplay.</li><li>Loop region: set start/end points and replay with configurable beat pre-roll.</li><li>Bookmarks: add an in-game name and optional comment, then jump previous/next.</li><li>Stem controls: mute or solo Inst, player vocals, and opponent vocals.</li><li>Scrolling: song selector and editor movement interpolate for 0.2 seconds with expo-out easing.</li><li>Vortex receptors: use gameplay strum art at 60% opacity. They confirm as notes cross during playback; sustains keep looping while their hold is active. While paused, forward scrolling triggers tap-note confirms exactly when the scrolling tween visually crosses them, while backward scrolling never triggers tap notes.</li><li>Zoom: adds more fixed-size grid rows instead of stretching existing boxes.</li></ul>`),
    section("save", "Saving", `<p><strong>Ctrl+S / Save</strong> writes pretty-formatted chart and <code>events.json</code> files to the folder assigned to this song. Without an assignment it silently uses the normal <code>config/fnfmod/songs/&lt;song&gt;</code> layout, creates the song folder, and writes <code>original_directory.txt</code> when the loaded chart needs its source preserved. <strong>Save As</strong> opens the native folder chooser and remembers that folder by song title in <code>config/fnfmod/chart_editor_save_folders.json</code>. <strong>Choose Saving Folder</strong> and <strong>Clear Saving Folder</strong> live under Edit. If an assigned folder is later deleted, a frontmost warning appears when the editor opens, clears the stale assignment, and explains the Ctrl+S fallback.</p>`)
  );

  pages["free-camera"] = page("Free camera", "Documentation · Tools", "Blender-inspired framing and object editing during authoring.", ["Object editing", "Lua copy/paste"],
    section("camera", "Camera controls", `<div class="table-wrap"><table><thead><tr><th>Input</th><th>Action</th></tr></thead><tbody>
      <tr><td>Shift + F</td><td>Toggle fly/look; LMB exits</td></tr><tr><td>MMB</td><td>Orbit pivot</td></tr><tr><td>Shift + MMB</td><td>Pan</td></tr><tr><td>Ctrl + MMB</td><td>Dolly</td></tr><tr><td>Wheel</td><td>Dolly; speed only while cursor grab is active</td></tr><tr><td>W/A/S/D, E/Q</td><td>Fly</td></tr><tr><td>Left/Right</td><td>Roll</td></tr><tr><td>Up/Down</td><td>Camera Zoom value</td></tr><tr><td>R</td><td>Reset roll/zoom</td></tr><tr><td>F</td><td>Frame machine/camera</td></tr><tr><td>Ctrl+Shift+Space</td><td>Exit</td></tr>
    </tbody></table></div>`),
    section("objects", "Object controls", `<div class="table-wrap"><table><thead><tr><th>Input</th><th>Action</th></tr></thead><tbody>
      <tr><td>Click</td><td>Select object</td></tr><tr><td>Numpad .</td><td>Focus selected origin</td></tr><tr><td>G / R / S</td><td>Move / rotate / scale</td></tr><tr><td>Numbers</td><td>Exact transform amount</td></tr><tr><td>Alt + G/R/S</td><td>Reset transform</td></tr><tr><td>X/Y/Z</td><td>Axis</td></tr><tr><td>Shift + axis</td><td>Plane</td></tr><tr><td>Shift / Ctrl</td><td>Precise / snap</td></tr><tr><td>LMB / Enter</td><td>Confirm</td></tr><tr><td>RMB / Esc</td><td>Cancel</td></tr><tr><td>Delete</td><td>Delete selection</td></tr><tr><td>Ctrl+Z / Ctrl+Y</td><td>Undo / redo</td></tr>
    </tbody></table></div>`),
    section("clipboard", "Lua copy/paste", `<p><code>Ctrl+C</code> copies the selected object's Lua; with no selection it copies camera events. Camera Follow Pos output compensates for Minecraft's detached-camera baseline, so replay reaches the same authored world position; rotation and zoom values are copied unchanged. <code>Ctrl+V</code> parses supported marked Lua and duplicates/places its type, name, transform, and modifiers. Code is the source; no hidden metadata is saved.</p>${callout("Paste marker", "Clipboard Lua needs the predefined Blockified object comment; arbitrary clipboard Lua is not executed.")}`),
    section("types", "Editable objects", `<p>Blockified characters, sprites, Sparrow XML spritesheets, graphs, world text, and registered free-camera objects can be selected. Framing uses the visible origin marker.</p>`),
    section("ui", "Viewport UI", `<p>HUD starts hidden. Hide Menu swaps menu-only and HUD-only; its button remains at 30% opacity. The bottom-right gizmo hides with the menu. A thick camera marker shows original position/rotation.</p>`)
  );

  pages.commands = page("Commands and recovery", "Documentation · Tools", "Open editors, reload content, and recover from a stuck session.", ["Commands"],
    section("editor", "Editor", api("/fnf editor [song]", "Opens chart editor.", "Command")),
    section("reload", "Reload", `<div class="api-list">${api("/fnf reload all", "Reloads all supported categories.", "Command")}${api("/fnf reload songs", "Reloads song library.", "Command")}${api("/fnf reload skins|splashes|animations|icons|hitsounds|fonts|options|scores", "Reloads one category.", "Command")}</div>`),
    section("world", "Move a world between saves and packs", `<div class="api-list">${api("/fnf world export &lt;pack&gt;", "Moves the current singleplayer world into an installed pack's worlds/ folder, then reopens it as that pack's bundled world. Works from an ordinary save or from another pack's world; the pack you are already in is not offered.", "Command")}${api("/fnf world import", "Moves the current bundled mod world back into Minecraft's saves folder and reopens it as an ordinary world. Only available while inside a bundled world.", "Command")}</div><p>Both play a short black-screen transition while the world is saved, closed, moved, and reopened — movement and camera are locked and a progress bar tracks the reload. Host-only (singleplayer or the LAN owner). See <a href="#/mod-worlds">Bundled worlds</a>.</p>`),
    section("volume", "Global master volume", `<p>Volume Up and Volume Down default to <code>+</code>/<code>=</code> and <code>-</code>. Rebind either under <strong>Options → Controls → Blockified Engine</strong>. Each press changes Minecraft master volume by 10%, including from Minecraft and Blockified pause menus. A compact top-layer slider animates in over 0.3 seconds and can be dragged wherever the current screen exposes a cursor. Its vanilla click changes pitch from 0.0 at 0% to 2.0 at 100% and plays once per displayed percentage while dragging. Volume shortcuts remain disabled during text entry.</p>`),
    section("emergency", "Emergency exit", `<p><code>Ctrl+Shift+Enter</code> leaves a stuck Blockified gameplay/editor state and returns control to Minecraft.</p>`)
  );

  pages.formats = page("Paths and formats", "Documentation · Tools", "Quick reference for Blockified-owned locations.", ["Reference"],
    section("paths", "Path map", `<div class="table-wrap"><table><thead><tr><th>Content</th><th>Path</th></tr></thead><tbody><tr><td>Lightweight songs</td><td><code>config/fnfmod/songs</code></td></tr><tr><td>Complete packs</td><td><code>config/fnfmod/mods</code></td></tr><tr><td>Note skins</td><td><code>config/fnfmod/skins</code></td></tr><tr><td>Splashes</td><td><code>config/fnfmod/splashes</code></td></tr><tr><td>Global BBS mappings</td><td><code>config/fnfmod/mods/animations</code></td></tr><tr><td>Global Lua scripts</td><td><code>config/fnfmod/mods/scripts</code></td></tr><tr><td>Global fonts</td><td><code>config/fnfmod/mods/fonts</code></td></tr><tr><td>Global health icons</td><td><code>config/fnfmod/mods/images/icons</code></td></tr><tr><td>Pack machines</td><td><code>mods/&lt;mod&gt;/machines</code></td></tr><tr><td>Pack worlds</td><td><code>mods/&lt;mod&gt;/worlds</code></td></tr><tr><td>Pack fonts</td><td><code>mods/&lt;mod&gt;/fonts</code></td></tr></tbody></table></div>${callout("Shared base", "Loose <code>characters/</code>, <code>images/</code>, <code>stages/</code>, <code>sounds/</code>, and <code>music/</code> placed directly in the <code>config/fnfmod/mods</code> root fall through to every song, after each song's own pack so the pack always wins. Naked-mod <code>scripts/*.lua</code> also run for every local song, including lightweight <code>config/fnfmod/songs</code> entries; they still obey Lua permissions, mod-world isolation, and the dedicated-server Lua ban. A bundled mod world stays isolated to its owning pack.")}`),
    section("mods-settings", "Mods settings and pack metadata", `<p>The <strong>Mods</strong> settings page always includes <code>config/fnfmod/mods</code>. You may add either one complete mod folder directly or a container <code>mods</code> folder. Direct mods are detected from <code>pack.json</code>/<code>pack.png</code> or standard content folders; containers expand every non-content child and scan each as an isolated pack. Psych-compatible metadata supplies names, descriptions, and icons. Source labels show only the final three path folders. Click any mod to select it, and use its <strong>i</strong> button to open its details; directory rows do not have an info button.</p><p>Use the top-left <strong>Permissions</strong> button to open its drawer. Select a source or a child pack, then click or drag across its resource buttons to enable/disable several categories. Enabled buttons are bright; disabled buttons are gray. A pack inherits its source permissions until given its own override. Images intentionally blocked by this permission or by Minecraft-look isolation are hidden; the missing-asset texture is reserved for files that were allowed but genuinely could not be found or decoded.</p>${callout("No metadata required", "A child folder does not need pack.json or pack.png to remain isolated. Its folder name becomes the display name and the details page reports that no description/icon was provided.")}`),
    section("atlas", "Animated atlases", `<p>Note skins, splashes, characters, world sprites, and machine UI can use PNG plus Sparrow XML. XML prefixes drive animation registration.</p>`),
    section("audio", "Audio", `<p>Song audio uses OGG, conventionally <code>Inst.ogg</code> and optional vocal stems such as <code>Voices.ogg</code>.</p>`),
    section("case", "Portable paths", callout("Keep case exact", "Windows may hide path-case mistakes that fail on case-sensitive systems."))
  );

  window.BLOCKIFIED_DOCS = { groups, pages };
}());
