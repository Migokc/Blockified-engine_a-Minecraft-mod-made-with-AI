// Generates compact, offline VS Code data from Blockified's registered Lua API.
// The upstream Psych data is licensed/attributed in ../LICENSE and notices.
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../..');
const here = path.resolve(__dirname, '..');
const gameplaySource = fs.readFileSync(path.join(root, 'src/main/java/com/fnfmod/client/lua/PsychLuaRuntime.java'), 'utf8');
const menuSource = fs.readFileSync(path.join(root, 'src/main/java/com/fnfmod/client/lua/MachineMenuRuntime.java'), 'utf8');
const effectsSource = fs.readFileSync(path.join(root, 'src/main/java/com/fnfmod/client/lua/LuaLayerEffects.java'), 'utf8');
const parentingSource = fs.readFileSync(path.join(root, 'src/main/java/com/fnfmod/client/lua/LuaObjectParenting.java'), 'utf8');
const psych = JSON.parse(fs.readFileSync(path.join(here, 'data/psych_1.0.json'), 'utf8'));
const matchNames = (source, regex) => [...source.matchAll(regex)].map(match => match[1]);
const unique = names => [...new Set(names)].sort((a, b) => a.localeCompare(b));
const entry = (args, documentation, returns = 'any') => ({ args, documentation, returns });
const detailed = (args, documentation, parameters, returns = 'any') =>
  ({ args, documentation, parameters, returns });

const unsupportedBlock = gameplaySource.match(/registerNoOps\(g,([\s\S]*?)\);/);
if (!unsupportedBlock) throw new Error('Could not locate Blockified no-op registrations');
const unsupported = new Set(matchNames(unsupportedBlock[1], /"([^"]+)"/g));
for (const name of ['runHaxeCode', 'changeDiscordPresence', 'changeDiscordClientID']) unsupported.add(name);
const helperNames = unique(matchNames(effectsSource, /\bfn\(globals,\s*"([^"]+)"/g)
  .concat(matchNames(parentingSource, /\bglobals\.set\("([^"]+)"/g)));
// Both runtimes install these helpers after gameplay's legacy no-op list.
for (const name of helperNames) unsupported.delete(name);

const gameplayOverrides = {
  debugPrint: detailed('text:string, color:string|number?',
    'Show a temporary debug line over gameplay and write it to the log. The optional color accepts a hex/name string or an integer color value.',
    { text: 'Message to display.', color: 'Optional text color; defaults to white.' }, 'void'),
  setRenderDistance: entry('chunks:number', 'Set the client render distance in chunks.'),
  getRenderDistance: entry('', 'Get the current render distance.', 'number'),
  setFOV: entry('degrees:number', 'Set gameplay camera field of view.'),
  getFOV: entry('', 'Get gameplay camera field of view.', 'number'),
  setHudStyle: entry('style:string', 'Change the gameplay HUD style during the song.'),
  getHudStyle: entry('', 'Return the active gameplay HUD style.', 'string'),
  setTextQuality: entry('tag:string, quality:number', 'Set text rasterization quality for a Lua text object.'),
  setTextLineSpacing: entry('tag:string, spacing:number', 'Set line spacing on a Lua text object.'),
  setTextLetterSpacing: entry('tag:string, spacing:number', 'Set letter spacing on a Lua text object.'),
  setTextFontMode: detailed('tag:string, mode:string',
    'Choose smooth or pixel rendering for a Lua text object. Smooth custom fonts use anti-aliased glyphs; pixel mode keeps glyphs crisp.',
    { tag: 'Lua text object tag.', mode: '`smooth` or `pixel`.' }, 'boolean'),
  getTextFontMode: detailed('tag:string', 'Read the selected rendering mode of a Lua text object.',
    { tag: 'Lua text object tag.' }, 'string'),
  setCharacterShadow: detailed('tag:string, enabled:boolean?',
    'Show or hide the ground shadow for a character.',
    { tag: 'Character tag, such as `boyfriend`, `dad`, or an extra-character tag.',
      enabled: 'True shows the shadow; false hides it. Defaults to true.' }, 'boolean'),
  doTweenText: entry('tag:string, target:string, value:number, duration:number, ease:string, property:string', 'Tween a Blockified text property such as LineSpacing or LetterSpacing.'),
  doTweenZ: entry('tag:string, target:string, value:number, duration:number, ease:string', 'Tween an object on the world Z axis.'),
  doTweenRotationX: entry('tag:string, target:string, value:number, duration:number, ease:string', 'Tween 3D rotation X.'),
  doTweenRotationY: entry('tag:string, target:string, value:number, duration:number, ease:string', 'Tween 3D rotation Y.'),
  doTweenRotationZ: entry('tag:string, target:string, value:number, duration:number, ease:string', 'Tween 3D rotation Z.'),
  addBlockifiedCharacter: entry('tag:string, character:string, x:number, y:number, z:number, rotation:number, animation:string, side:string', 'Create a 2D character in Minecraft world space.'),
  setObjectMask: entry('target:string, mask:string, mode:string?, invert:boolean?, maskOnly:boolean?', 'Mask an object using another object. Mode is alpha or luminance; masking does not transform the target.'),
  setObjectGradient: entry('tag:string, type:string, colors:table, angle:number?', 'Configure a gradient effect on an object. See the Blockified layer-effects documentation.'),
  setBlendMode: entry('tag:string, mode:string', 'Set object blend mode.'),
  setObjectParent: entry('child:string, parent:string, keepPosition:boolean?', 'Parent an object. By default, the child local position resets to zero.'),
  removeObjectParent: entry('child:string, keepPosition:boolean?', 'Remove object parenting. Defaults to keeping its world position.'),
  getObjectParent: entry('child:string', 'Get the parent tag, if any.', 'string'),
  addToGroup: entry('group:string, child:string', 'Add an object to a Blockified compositing group.'),
  removeFromGroup: entry('child:string', 'Remove an object from its Blockified compositing group.'),
  makeLuaGradient: entry('tag:string, x:number, y:number, width:number, height:number, type:string, colors:table, angle:number?', 'Create a standalone gradient object.'),
  makeLuaGroup: entry('tag:string, x:number, y:number, width:number, height:number', 'Create a Blockified compositing group.'),
  doTweenEffect: entry('tag:string, target:string, property:string, value:number, duration:number, ease:string', 'Tween a numeric mask, gradient, clip, or blend effect property.'),
  runMinecraftCommand: entry('command:string, source:string', 'Run a Minecraft command, subject to world/server permissions.'),
  triggerEvent: entry('name:string, value1:string, value2:string', 'Trigger a Blockified or supported Psych chart event.'),
  getBlockShading: entry('', 'Get the current directional block-shading state.', 'boolean'),
  getMobShading: entry('', 'Get the current directional entity-shading state.', 'boolean')
};
Object.assign(gameplayOverrides, {
  doTweenText: detailed('tag:string, target:string, amount:number, duration:number?, easing:string, property:string',
    'Tween a Lua text property. Preferred call: `doTweenText(tag, target, amount, duration, easing, property)`. You can omit duration; then the fourth argument is easing and the duration is one second.',
    { tag: 'Unique tween ID, passed to `onTweenCompleted`.', target: 'Tag of an existing Lua text object.',
      amount: 'Final numeric value of the chosen text property.', duration: 'Duration in seconds; omit for one second.',
      easing: 'Easing name, for example `linear` or `expoOut`.',
      property: '`LineSpacing`, `LetterSpacing`, `BorderSize`, or `TextSize` (aliases are accepted).' }),
  cameraOrbit: detailed('pinned:boolean?, x:number?, y:number?, z:number?, duration:number?, easing:string?',
    'Enable the orbit pivot. With `pinned=false`, XYZ are offsets relative to the focused character; with `pinned=true`, they are absolute stage-local coordinates. Camera Follow Pos supplies the camera position/radius, while Camera Rotation 3D animates the orbit.',
    { pinned: 'False follows the current camera focus; true pins the pivot to stage-local coordinates.',
      x: 'Pivot X offset or stage-local position.', y: 'Pivot Y offset or stage-local position.',
      z: 'Pivot Z offset or stage-local position.', duration: 'Pivot tween length in seconds; defaults to 0.5.',
      easing: 'Pivot tween easing; defaults to smooth.' }),
  showTimeBar: detailed('visible:boolean?', 'Show or hide the Blockified gameplay time bar.',
    { visible: 'True shows the time bar; omitted defaults to true.' }),
  setObjectBorder: detailed('tag:string, size:number, color:string, style:string?',
    'Draw a fixed-screen-size outline around a supported non-text object. World geometry still occludes it. Text uses `setTextBorder`.',
    { tag: 'Object, character, or entity tag.', size: 'Outline thickness. Zero disables it.',
      color: 'Hex color such as `FF0000`.', style: 'Use `outline`; `none` clears it.' }),
  setObjectRotation: detailed('tag:string, x:number, y:number, z:number',
    'Set all three rotation axes of a Lua object. Z corresponds to its 2D angle.',
    { tag: 'Lua object tag.', x: 'X-axis rotation in degrees.', y: 'Y-axis rotation in degrees.',
      z: 'Z-axis rotation/2D angle in degrees.' }),
  setObjectRenderMode: detailed('tag:string, mode:string',
    'Choose rendering behavior for a world-space Lua object; the same property can be set via `setProperty(tag .. ".renderMode", mode)`.',
    { tag: 'World-space object tag.', mode: '`auto`, `lit`, `flat`/`unlit`, or `emissive`.' }),
  setWorldSpriteGravity: detailed('tag:string, enabled:boolean?',
    'Enable or disable gravity for a world-space sprite. True is the default argument.',
    { tag: 'World-space sprite tag.', enabled: 'True enables gravity; false disables it.' }),
  setWorldSpriteCollision: detailed('tag:string, enabled:boolean?',
    'Enable or disable block collision for a world-space sprite.',
    { tag: 'World-space sprite tag.', enabled: 'True enables collision; false disables it.' }),
  setWorldSpriteIRLightsShadows: detailed('tag:string, enabled:boolean?',
    'Control whether a world-space sprite casts shadows for IRLights. The alternate `Irlights` capitalization is also accepted.',
    { tag: 'World-space sprite tag.', enabled: 'True enables IRLights shadows.' }),
  setCameraBopEnabled: detailed('camera:string?, enabled:boolean?',
    'Enable or disable beat-driven camera bopping without changing the song or note timing.',
    { camera: '`game`, `hud`, or `both` (default).', enabled: 'True enables bopping; false disables it.' }),
  addBlockifiedCharacter: detailed('tag:string, character:string, x:number?, y:number?, z:number?, rotation:number?, animation:string?, side:string?',
    'Create an extra 2D character in 3D world space. `makeBlockifiedCharacter` is an alias.',
    { tag: 'Unique Lua character tag.', character: 'Character definition or sprite asset name.',
      x: 'World X coordinate.', y: 'World Y coordinate.', z: 'World Z coordinate.',
      rotation: 'Facing rotation in degrees.', animation: 'Initial animation, default `idle`.',
      side: '`player` or `opponent`; defaults to player.' }),
  changeBlockifiedCharacter: detailed('tag:string, character:string, animation:string?',
    'Replace the definition of an existing extra Blockified character.',
    { tag: 'Existing extra-character tag.', character: 'New character definition name.',
      animation: 'Optional animation to play after the change.' }),
  makeLuaGradient: detailed('tag:string, x:number, y:number, width:number, height:number, type:string?, colors:table?, angle:number?',
    'Create an independent gradient object; it can also be used as a mask source.',
    { tag: 'Unique gradient tag.', x: 'Object X position.', y: 'Object Y position.',
      width: 'Object width.', height: 'Object height.', type: '`linear`, `radial`, or `angular`.',
      colors: 'Table of 2–16 color stops; entries can contain `at`, `color`, and `alpha`.',
      angle: 'Gradient angle in degrees.' }),
  setObjectMask: detailed('target:string, mask:string, mode:string?, invert:boolean?, maskOnly:boolean?',
    'Mask one object using another existing object. This does not move, scale, or rotate the target; use parenting for linked transforms.',
    { target: 'Tag of object being masked.', mask: 'Tag of the mask-source object.',
      mode: '`alpha` (default) or `luminance`.', invert: 'Invert the sampled mask.',
      maskOnly: 'Hide the source from normal rendering while keeping it as a mask; defaults to true.' }),
  setObjectGradient: detailed('tag:string, type:string, colors:table, angle:number?',
    'Apply a local-space gradient to an existing object.',
    { tag: 'Existing object tag.', type: '`linear`, `radial`, or `angular`.',
      colors: 'Table of 2–16 stops; each stop may have `at`, `color`, and `alpha`.',
      angle: 'Gradient angle in degrees.' }),
  setBlendMode: detailed('tag:string, mode:string', 'Change how this object blends with pixels behind it.',
    { tag: 'Existing object tag.', mode: '`normal`, `add`, `multiply`, `screen`, `subtract`, `lighten`, `darken`, `difference`, or `overlay`.' }),
  setObjectParent: detailed('child:string, parent:string, keepPosition:boolean?',
    'Attach an object to another object. By default its local position becomes 0,0,0, with the parent as its new origin.',
    { child: 'Tag to attach.', parent: 'Parent object tag.',
      keepPosition: 'True retains the child’s current world-space transform; false resets local position.' }),
  runMinecraftCommand: detailed('command:string, source:string?',
    'Run a Minecraft command subject to world/server cheat permissions. `runCommand` is an alias.',
    { command: 'Command text, with or without a leading slash.', source: '`player` or `server` command source.' })
});
gameplayOverrides.startCameraOrbit = gameplayOverrides.cameraOrbit;
gameplayOverrides.makeBlockifiedCharacter = gameplayOverrides.addBlockifiedCharacter;
gameplayOverrides.setWorldSpriteIrlightsShadows = gameplayOverrides.setWorldSpriteIRLightsShadows;
gameplayOverrides.setDefaultCameraBop = gameplayOverrides.setCameraBopEnabled;
Object.assign(gameplayOverrides, {
  'blockifiedCharacterExists': detailed('tag:string', 'Check whether an extra Blockified character with this Lua tag exists.',
    { tag: 'Extra-character tag.' }, 'boolean'),
  'removeBlockifiedCharacter': detailed('tag:string', 'Remove an extra 2D/3D Blockified character.',
    { tag: 'Extra-character tag.' }, 'boolean'),
  'resetCharacterPosition': detailed('tag:string', 'Clear an extra character’s stage offset and return it to its resting spot.',
    { tag: 'Extra-character tag.' }, 'boolean'),
  'getCameraBopEnabled': detailed('camera:string?', 'Check whether beat-driven camera bopping is active.',
    { camera: '`game`, `hud`, or `both` (default).' }, 'boolean'),
  'getGlobalFromScript': detailed('script:string, key:string', 'Read a Lua global from another loaded script.',
    { script: 'Loaded script filename/path.', key: 'Global name.' }),
  'setGlobalFromScript': detailed('script:string, key:string, value:any', 'Set a Lua global in another loaded script.',
    { script: 'Loaded script filename/path.', key: 'Global name.', value: 'New Lua value.' }, 'boolean'),
  'getTextQuality': detailed('tag:string', 'Read a Lua text object’s font oversampling quality.',
    { tag: 'Text object tag.' }, 'number'),
  'getTextLineSpacing': detailed('tag:string', 'Read extra line spacing on a Lua text object.',
    { tag: 'Text object tag.' }, 'number'),
  'getTextLetterSpacing': detailed('tag:string', 'Read extra letter spacing on a Lua text object.',
    { tag: 'Text object tag.' }, 'number'),
  'removeObjectGradient': detailed('tag:string', 'Disable an object’s gradient effect.', { tag: 'Object tag.' }),
  'removeObjectMask': detailed('tag:string', 'Remove an assigned object mask.', { tag: 'Object tag.' }),
  'setAllCharacterCollisions': detailed('enabled:boolean?',
    'Enable or disable character-to-character pushing. Block collision and gravity remain separate.',
    { enabled: 'True allows performers to push each other; default true.' }),
  'setAllCharacterShadows': detailed('enabled:boolean?', 'Show or hide the vanilla ground-blob shadows beneath performers.',
    { enabled: 'True shows shadows; default true.' }),
  'setFlatShading': detailed('enabled:boolean?',
    'Toggle full-bright/flat shading for the main performers. Other objects use `.fullbright` or `.lighting` properties.',
    { enabled: 'True enables flat shading; default true.' }),
  'setObjectAntialiasing': detailed('tag:string, enabled:boolean?', 'Toggle texture filtering for a Lua object.',
    { tag: 'Object tag.', enabled: 'True enables antialiasing; false preserves crisp pixels.' }),
  'setObjectClip': detailed('tag:string, type:string, x:number?, y:number?, width:number?, height:number?, radius:number?, softness:number?',
    'Clip an object in its own local space using a rectangle, circle, or rounded shape.',
    { tag: 'Object tag.', type: '`none`, `rect`, `circle`, or `rounded`.',
      x: 'Local clip X.', y: 'Local clip Y.', width: 'Local clip width.',
      height: 'Local clip height.', radius: 'Corner/circle radius.', softness: 'Edge softness.' }),
  'setObjectSeeThrough': detailed('tag:string, enabled:boolean?',
    'Make a world-camera Lua object draw over world geometry instead of being occluded; off by default.',
    { tag: 'World-camera object tag.', enabled: 'True renders through blocks.' }),
  'setWorldSpriteBillboard': detailed('tag:string, enabled:boolean?', 'Make a world-space sprite face the camera.',
    { tag: 'World-space sprite tag.', enabled: 'True billboards; false keeps its explicit rotation.' }),
  'setWorldSpriteLighting': detailed('tag:string, enabled:boolean?', 'Toggle world lighting for a world-space sprite.',
    { tag: 'World-space sprite tag.', enabled: 'True enables lighting; false uses flat/unlit appearance.' }),
  'setWorldSpriteShadows': detailed('tag:string, enabled:boolean?', 'Toggle the world-space sprite’s ground shadow.',
    { tag: 'World-space sprite tag.', enabled: 'True enables the shadow.' }),
  'stopCameraOrbit': entry('', 'Disable orbit mode and return to normal camera behavior.', 'boolean'),
  'getMouseX': entry('', 'Gameplay Lua currently returns 0 for this call; menu Lua has live cursor coordinates.', 'number'),
  'getMouseY': entry('', 'Gameplay Lua currently returns 0 for this call; menu Lua has live cursor coordinates.', 'number')
});
gameplayOverrides.getDefaultCameraBop = gameplayOverrides.getCameraBopEnabled;
gameplayOverrides.runCommand = gameplayOverrides.runMinecraftCommand;
gameplayOverrides.setFullbright = gameplayOverrides.setFlatShading;
gameplayOverrides.setSpriteAntialiasing = gameplayOverrides.setObjectAntialiasing;
gameplayOverrides.setWorldSpriteRenderMode = gameplayOverrides.setObjectRenderMode;
gameplayOverrides.setObjectShaderMode = gameplayOverrides.setObjectRenderMode;

const gameplayNames = unique(matchNames(gameplaySource, /\bfn\(g,\s*"([^"]+)"/g).concat(helperNames)
  .concat(Object.keys(gameplayOverrides).filter(name => gameplaySource.includes('"' + name + '"'))))
  .filter(name => !unsupported.has(name));
const gameplayFunctions = {};
for (const name of gameplayNames) {
  gameplayFunctions[name] = gameplayOverrides[name] || psych.functions[name] ||
    entry('...', 'Registered by Blockified gameplay Lua. Argument details are not yet documented; check the mod documentation/source.');
}
const gameplayCallbacks = unique(matchNames(gameplaySource, /\bcall\("(on[A-Za-z0-9_]+|goodNoteHit|opponentNoteHit|noteMiss|noteMissPress)"/g)
  .concat(['goodNoteHit', 'opponentNoteHit', 'noteMiss', 'noteMissPress', 'onCreate', 'onCreatePost',
    'onUpdate', 'onUpdatePost', 'onBeatHit', 'onStepHit', 'onSectionHit', 'onMeterBeatHit', 'onMeasureHit',
    'onSongStart', 'onEndSong', 'onEvent', 'onPause', 'onResume', 'onTweenCompleted', 'onTimerCompleted']));
const gameplayEvents = {};
const callbackDocs = {
  onStartCountdown: 'Called before the countdown. In Blockified, returning Function_Stop does not hold the countdown.',
  goodNoteHit: 'Called when the player hits a note; held sustains call it repeatedly until the note ends.',
  opponentNoteHit: 'Called when the opponent hits a note.',
  onMeterBeatHit: 'Called for a beat in the active time signature.',
  onMeasureHit: 'Called when a new measure starts.'
};
for (const name of gameplayCallbacks) gameplayEvents[name] = entry(
  psych.events[name]?.args || (name === 'onUpdate' || name === 'onUpdatePost' ? 'elapsed:number' : ''),
  callbackDocs[name] || 'Blockified gameplay callback. Define this as a global Lua function; see the mod documentation for timing and return behavior.',
  psych.events[name]?.returns || 'any');
const gameplayVars = {};
for (const name of unique(matchNames(gameplaySource, /\bg\.set\("([A-Za-z_][A-Za-z_0-9]*)"/g))) {
  if (gameplayFunctions[name]) continue;
  gameplayVars[name] = psych.variables[name] || { documentation: 'Blockified gameplay Lua global.' };
}
for (const name of ['curBeat', 'curStep', 'curSection', 'curMeasure', 'curMeterBeat', 'songPosition']) {
  if (gameplaySource.includes('"' + name + '"')) gameplayVars[name] ||= { documentation: 'Blockified gameplay timing global.' };
}

const menuOverrides = {
  debugPrint: detailed('text:string, color:string|number?',
    'Show a temporary debug line above the menu and write it to the log. The optional color accepts a hex/name string or an integer color value.',
    { text: 'Message to display.', color: 'Optional text color; defaults to white.' }, 'void'),
  setTextFontMode: detailed('tag:string, mode:string',
    'Choose smooth or pixel rendering for a menu text widget.',
    { tag: 'Text widget ID.', mode: '`smooth` or `pixel`.' }, 'boolean'),
  getTextFontMode: detailed('tag:string', 'Read the selected rendering mode of a menu text widget.',
    { tag: 'Text widget ID.' }, 'string'),
  'ui.label': entry('id:string, text:string, x:number, y:number, width:number?, height:number?', 'Create a text label. Fractional X/Y values are proportional to the fixed 1280×720 menu canvas.'),
  'ui.button': entry('id:string, text:string, x:number, y:number, width:number, height:number', 'Create a clickable menu button. Define `function button:onClick()` or a global `onClick(id)` callback.'),
  'ui.image': entry('id:string, image:string, x:number, y:number, width:number, height:number', 'Create an image from the current mod assets.'),
  'ui.sprite': entry('id:string, image:string, x:number, y:number, width:number, height:number', 'Create a sprite widget.'),
  'ui.animatedSprite': entry('id:string, image:string, x:number, y:number, width:number, height:number', 'Create an animated sprite widget.'),
  'ui.graph': entry('id:string, shape:string, x:number, y:number, width:number, height:number, color:string', 'Create a geometric menu widget.'),
  'ui.toggle': entry('id:string, text:string, x:number, y:number, width:number, height:number', 'Create a toggle widget.'),
  'ui.slider': entry('id:string, x:number, y:number, width:number, height:number, min:number, max:number', 'Create a slider widget.'),
  'ui.world': entry('widget:any, x:number, y:number, z:number', 'Place a widget in 3D space relative to its machine origin. Returns the widget.'),
  'ui.worldAbsolute': entry('widget:any, x:number, y:number, z:number', 'Place a widget at absolute Minecraft world coordinates. Returns the widget.'),
  'ui.get': entry('id:string', 'Get an existing widget by ID.'),
  'ui.remove': entry('id:string', 'Remove an existing widget.'),
  'machine.openScreen': entry('name:string', 'Open another script from this machine’s `screens` folder.'),
  'machine.playSong': entry('song:string, difficulty:string, options:table?', 'Start a song. Options can include `machine`, `returnTo`, `duet`, `playAs`, and `look`. Use `machine` for a virtual-machine tag.'),
  'machine.openSongSelect': entry('returnTo:string?', 'Open the built-in song selector.'),
  'machine.openSettings': entry('', 'Open Blockified settings.'),
  'machine.exitWorld': entry('', 'Leave the current world without a custom prompt.'),
  'machine.close': entry('', 'Close the current menu.'),
  'camera.setPosition': entry('x:number, y:number, z:number', 'Place the menu camera in Minecraft world space.'),
  'camera.setRotation': entry('pitch:number, yaw:number, roll:number', 'Set menu camera rotation.'),
  'camera.lookAt': entry('x:number, y:number, z:number', 'Point the menu camera at world coordinates.'),
  'camera.setZoom': entry('zoom:number', 'Set the menu camera zoom.'),
  'camera.setFOV': entry('degrees:number', 'Set the menu camera field of view.'),
  'doTweenCamX': entry('tag:string, value:number, duration:number, ease:string', 'Tween menu camera X position.'),
  'doTweenCamY': entry('tag:string, value:number, duration:number, ease:string', 'Tween menu camera Y position.'),
  'doTweenCamZ': entry('tag:string, value:number, duration:number, ease:string', 'Tween menu camera Z position.'),
  'doTweenCamRotationX': entry('tag:string, value:number, duration:number, ease:string', 'Tween menu camera pitch.'),
  'doTweenCamRotationY': entry('tag:string, value:number, duration:number, ease:string', 'Tween menu camera yaw.'),
  'doTweenCamRotationZ': entry('tag:string, value:number, duration:number, ease:string', 'Tween menu camera roll.'),
  'doTweenCamZoom': entry('tag:string, value:number, duration:number, ease:string', 'Tween menu camera zoom.'),
  'setFOV': entry('degrees:number', 'Set menu camera field of view.'),
  'getFOV': entry('', 'Get menu camera field of view.', 'number'),
  'doTweenText': entry('tag:string, target:string, value:number, duration:number, ease:string, property:string', 'Tween text line spacing, letter spacing, or another supported text property.'),
  'runTimer': entry('tag:string, seconds:number, loops:number?', 'Start a menu timer; completion calls `onTimerCompleted`.'),
  'keyboardJustPressed': entry('key:string', 'True on the first frame of a key press.', 'boolean'),
  'keyboardPressed': entry('key:string', 'True while the key is held.', 'boolean'),
  'keyboardReleased': entry('key:string', 'True when the key is released.', 'boolean'),
  'getMouseX': entry('', 'Get mouse X on the fixed menu canvas.', 'number'),
  'getMouseY': entry('', 'Get mouse Y on the fixed menu canvas.', 'number'),
  'setObjectOrder': entry('id:string, order:number', 'Set widget layer order; larger values draw later.'),
  'setObjectMask': entry('target:string, mask:string, mode:string?, invert:boolean?, maskOnly:boolean?', 'Mask a menu widget; does not transform it.'),
  'setObjectParent': entry('child:string, parent:string, keepPosition:boolean?', 'Parent a widget; by default the child local position resets to zero.'),
  'ui.gradient': entry('id:string, x:number, y:number, width:number, height:number, type:string, colors:table, angle:number?', 'Create a standalone gradient widget.'),
  'ui.group': entry('id:string, x:number, y:number, width:number, height:number', 'Create a compositing group widget.')
};
Object.assign(menuOverrides, {
  'ui.label': detailed('id:string, text:string, x:number, y:number, width:number?, height:number?',
    'Create a text label and return its widget table. Set its font, shadow, alignment, border, scale, and order on the table or through text helper functions.',
    { id: 'Unique widget ID.', text: 'Initial label text.',
      x: 'Horizontal center; 0–1 is proportional to the fixed 1280-pixel canvas.',
      y: 'Vertical center; 0–1 is proportional to the fixed 720-pixel canvas.',
      width: 'Optional wrapping width; zero uses text size.', height: 'Optional label height.' }),
  'ui.image': detailed('id:string, image:string, x:number, y:number, width:number, height:number',
    'Create a static image widget. The image path is resolved from the current mod/assets roots. Use `ui.world` to move it into 3D space.',
    { id: 'Unique widget ID.', image: 'Relative image asset path, such as `images/cover.png`.',
      x: 'Horizontal center.', y: 'Vertical center.', width: 'Displayed width.', height: 'Displayed height.' }),
  'ui.sprite': detailed('id:string, image:string, x:number, y:number, width:number, height:number',
    'Create a static sprite widget with the same geometry as `ui.image`.',
    { id: 'Unique widget ID.', image: 'Relative sprite image path.', x: 'Horizontal center.',
      y: 'Vertical center.', width: 'Displayed width.', height: 'Displayed height.' }),
  'ui.toggle': detailed('id:string, text:string, x:number, y:number, width:number, height:number',
    'Create a toggle widget and return its table. Its `value` field starts false and changes when clicked.',
    { id: 'Unique widget ID.', text: 'Displayed toggle label.', x: 'Horizontal center.',
      y: 'Vertical center.', width: 'Toggle width.', height: 'Toggle height.' }),
  'ui.animatedSprite': detailed('id:string, png:string, xml:string?, x:number, y:number, width:number, height:number',
    'Create a sprite-sheet widget. The XML path defaults to the PNG basename with `.xml`; the returned widget has animation controls.',
    { id: 'Unique widget ID.', png: 'Sprite-sheet PNG path.', xml: 'Matching Sparrow XML path; omitted uses the PNG basename.',
      x: 'Horizontal center on the fixed canvas.', y: 'Vertical center on the fixed canvas.',
      width: 'Displayed width in canvas pixels.', height: 'Displayed height in canvas pixels.' }),
  'ui.panel': detailed('id:string, text:string, x:number, y:number, width:number, height:number',
    'Create a background panel. The text argument is retained for the shared constructor layout but is not rendered on a panel.',
    { id: 'Unique widget ID.', text: 'Unused panel text slot; pass an empty string.',
      x: 'Horizontal center.', y: 'Vertical center.', width: 'Panel width.', height: 'Panel height.' }),
  'ui.slider': detailed('id:string, text:string, x:number, y:number, width:number, height:number',
    'Create a slider widget. Configure the returned table’s `min`, `max`, and `value` fields; defaults are 0, 1, and 0.',
    { id: 'Unique widget ID.', text: 'Slider text.', x: 'Horizontal center.', y: 'Vertical center.',
      width: 'Slider width.', height: 'Slider height.' }),
  'ui.button': detailed('id:string, text:string, x:number, y:number, width:number, height:number',
    'Create a button and return its widget table. Its `backgroundColor` and `hoverColor` fields control the fill; define `function button:onClick()` or global `onClick(id)` for interaction.',
    { id: 'Unique widget ID.', text: 'Text displayed on the button.',
      x: 'Horizontal center; 0–1 is proportional to the 1280-pixel canvas.',
      y: 'Vertical center; 0–1 is proportional to the 720-pixel canvas.',
      width: 'Width in canvas pixels.', height: 'Height in canvas pixels.' }),
  'ui.graph': detailed('id:string, shape:string?, x:number, y:number, width:number, height:number, color:string?',
    'Create a colored graph widget. If shape is omitted, geometry begins immediately after the ID and the shape defaults to rectangle.',
    { id: 'Unique widget ID.', shape: 'Optional shape name, such as `rectangle`.',
      x: 'Horizontal center.', y: 'Vertical center.', width: 'Graph width.',
      height: 'Graph height.', color: 'Hex fill color.' }),
  'ui.world': detailed('widget:table, x:number, y:number, z:number',
    'Move a widget into 3D space using block offsets from the current machine origin. Returns the same widget.',
    { widget: 'Widget table returned by a `ui` constructor, or its ID.',
      x: 'Machine-relative Minecraft X offset.', y: 'Machine-relative Y offset.',
      z: 'Machine-relative Z offset.' }),
  'ui.worldAbsolute': detailed('widget:table, x:number, y:number, z:number',
    'Move a widget to an absolute Minecraft world position, independent of machine facing/location.',
    { widget: 'Widget table returned by a `ui` constructor, or its ID.',
      x: 'World X coordinate.', y: 'World Y coordinate.', z: 'World Z coordinate.' }),
  'machine.playSong': detailed('song:string, difficulty:string?, options:table?',
    'Start a song directly. Use `options.machine` to select a hitbox-less virtual machine by tag; `options.returnTo = "menu"` returns to its menu after the song. The other options include `duet`, `playAs`, and `look`.',
    { song: 'Song ID from the current available songs.', difficulty: 'Difficulty name; omitted uses the song default.',
      options: 'Lua table: `machine`, `returnTo`/`onExit`, `duet`, `playAs`, and `look`.' }),
  'machine.openScreen': detailed('name:string', 'Open a second Lua screen from this machine’s `screens/<name>.lua` folder.',
    { name: 'Screen script basename, without `.lua`.' }),
  'machine.openChartEditor': detailed('song:string?, difficulty:string?', 'Open the chart editor, optionally preselecting a song and difficulty.',
    { song: 'Song ID; blank or omitted lets the editor choose.', difficulty: 'Difficulty name.' }),
  'machine.getSongs': entry('', 'Return a Lua array of available songs. Each song has an ID, display name, opponent icon, and difficulties.', 'table'),
  'machine.getSong': detailed('id:string', 'Return one song-data table or nil if the ID is unavailable.',
    { id: 'Song ID.' }, 'table?'),
  'machine.getWeeks': entry('', 'Return a Lua array of available week-data tables.', 'table'),
  'machine.getWeek': detailed('id:string', 'Return one week-data table or nil if the week is unavailable.',
    { id: 'Week ID.' }, 'table?'),
  'machine.hasSong': detailed('id:string', 'Check whether a song is available.', { id: 'Song ID.' }, 'boolean'),
  'machine.hasWeek': detailed('id:string', 'Check whether a week is available.', { id: 'Week ID.' }, 'boolean'),
  'machine.getScreen': entry('', 'Return the name of the current menu screen.', 'string'),
  'machine.getScreens': entry('', 'Return names of available scripts in this machine’s screens folder.', 'table'),
  'machine.close': entry('', 'Close the custom menu and save machine data.'),
  'machine.exitWorld': entry('', 'Leave the Minecraft world immediately, without an extra custom-menu prompt.'),
  'machine.saveData': entry('', 'Persist changes made to `machineData`.'),
  'machine.openSongSelect': detailed('returnTo:string?', 'Open Blockified’s built-in song selector.',
    { returnTo: 'Where quitting/finishing should go after the selected song.' }),
  'camera.setPosition': detailed('x:number, y:number, z:number',
    'Set the machine-menu camera position in Minecraft coordinates. This opts into the custom menu camera behavior.',
    { x: 'World X coordinate.', y: 'World Y coordinate.', z: 'World Z coordinate.' }),
  'camera.setRotation': detailed('pitch:number, yaw:number, roll:number', 'Set the menu camera orientation immediately.',
    { pitch: 'Up/down angle.', yaw: 'Left/right heading.', roll: 'Camera roll.' }),
  'camera.setFOV': detailed('degrees:number', 'Set field of view, independently of the camera zoom property.',
    { degrees: 'Camera field-of-view angle in degrees.' }),
  'doTweenText': detailed('tag:string, target:string, amount:number, duration:number, easing:string, property:string',
    'Tween supported menu text properties, including line spacing, letter spacing, border size, and font scale.',
    { tag: 'Unique tween ID.', target: 'Existing label or button ID.', amount: 'Final numeric value.',
      duration: 'Tween duration in seconds.', easing: 'Easing name.',
      property: '`LineSpacing`, `LetterSpacing`, `BorderSize`, or `TextSize`/`FontScale`.' }),
  'setTextQuality': detailed('id:string, quality:number', 'Change font oversampling for a menu label/button.',
    { id: 'Text widget ID.', quality: 'Font rasterization quality/oversample factor.' }),
  'setTextLineSpacing': detailed('id:string, spacing:number', 'Set extra line spacing for menu text.',
    { id: 'Text widget ID.', spacing: 'Extra spacing between text lines.' }),
  'setTextLetterSpacing': detailed('id:string, spacing:number', 'Set extra space between letters for menu text.',
    { id: 'Text widget ID.', spacing: 'Extra space between glyphs.' }),
  'setTextShadow': detailed('id:string, enabled:boolean', 'Show or hide the Minecraft-style shadow behind menu text.',
    { id: 'Text widget ID.', enabled: 'True shows shadow; false hides it.' }),
  'setTextBorder': detailed('id:string, size:number, color:string, style:string?', 'Draw an outline/border around menu text.',
    { id: 'Text widget ID.', size: 'Border thickness.', color: 'Hex border color.', style: 'Border style, default `outline`.' }),
  'setMenuBlur': detailed('radius:number', 'Take control of Minecraft’s menu-background blur for this menu. Values below 1 disable it.',
    { radius: 'Blur radius; 0 disables blur.' }),
  'enableMenuBlur': detailed('radius:number?', 'Enable menu-background blur; omitted uses the configured blur radius or at least 1.',
    { radius: 'Optional blur radius.' }),
  'disableMenuBlur': entry('', 'Set the current menu’s background blur to zero.'),
  'resetMenuBlur': entry('', 'Stop overriding blur and return to the player’s configured menu blur.'),
  'getMenuBlur': entry('', 'Read the active blur radius, or the user’s configured radius when this menu is not overriding it.', 'number'),
  'doTweenMenuBlur': detailed('tag:string, radius:number, duration:number, easing:string?',
    'Tween the current menu blur radius. The tween controls blur until resetMenuBlur is called.',
    { tag: 'Unique tween ID.', radius: 'Target blur radius.',
      duration: 'Duration in seconds.', easing: 'Easing name; defaults to linear.' }),
  'mouseOver': detailed('id:string', 'Test whether the cursor is over a visible widget, including 3D widgets.',
    { id: 'Widget ID.' }, 'boolean'),
  'mouseInside': detailed('x:number, y:number, width:number, height:number',
    'Test whether the cursor is inside a rectangle centered at X/Y on the fixed menu canvas.',
    { x: 'Rectangle center X.', y: 'Rectangle center Y.', width: 'Width in canvas pixels.',
      height: 'Height in canvas pixels.' }, 'boolean'),
  'isMouseDown': detailed('button:number?', 'Check whether a mouse button is held. Defaults to the left button.',
    { button: 'Mouse button index; 0 is left.' }, 'boolean'),
  'playSound': detailed('tag:string, sound:string, volume:number?, loop:boolean?',
    'Play a menu sound. An empty tag creates a fire-and-forget sound; tagged sounds can later be paused, stopped, or adjusted.',
    { tag: 'Sound instance ID, or empty string for fire-and-forget.', sound: 'Audio asset name/path.',
      volume: 'Playback volume, default 1.', loop: 'True repeats until stopped; default false.' }, 'boolean'),
  'precacheSound': detailed('sound:string', 'Pre-decode audio to avoid a pause when playback begins.',
    { sound: 'Audio asset name/path.' }, 'boolean'),
  'setObjectMask': detailed('target:string, mask:string, mode:string?, invert:boolean?, maskOnly:boolean?',
    'Mask one menu widget with another. This changes visibility, not transforms; parenting is separate.',
    { target: 'Widget being masked.', mask: 'Existing mask-source widget.', mode: '`alpha` or `luminance`.',
      invert: 'Invert the mask.', maskOnly: 'Hide the source outside masking; defaults to true.' }),
  'setObjectParent': detailed('child:string, parent:string, keepPosition:boolean?',
    'Attach a widget to another; by default the child’s local position resets to 0,0,0.',
    { child: 'Child widget ID.', parent: 'Parent widget ID.', keepPosition: 'True keeps the child’s world transform.' })
});
menuOverrides['ui.graphic'] = menuOverrides['ui.graph'];
menuOverrides['ui.gradient'] = gameplayOverrides.makeLuaGradient;
menuOverrides['ui.group'] = gameplayOverrides.makeLuaGroup;
menuOverrides['machine.join'] = menuOverrides['machine.openSongSelect'];
menuOverrides['machine.openOptions'] = menuOverrides['machine.openSettings'];
menuOverrides['setWorldSpriteRenderMode'] = detailed('id:string, mode:string',
  'Choose 3D rendering behavior for a menu widget.', { id: 'Widget ID.', mode: '`auto`, `lit`, `flat`/`unlit`, or `emissive`.' });
menuOverrides['setObjectRenderMode'] = menuOverrides['setWorldSpriteRenderMode'];
menuOverrides['setObjectShaderMode'] = menuOverrides['setWorldSpriteRenderMode'];
for (const [name, property] of Object.entries({ doTweenX: 'X position', doTweenY: 'Y position',
  doTweenZ: 'world Z position', doTweenAlpha: 'opacity', doTweenAngle: '2D angle',
  doTweenRotationX: '3D X rotation', doTweenRotationY: '3D Y rotation',
  doTweenWidth: 'width', doTweenHeight: 'height', doTweenFontScale: 'font scale' })) {
  menuOverrides[name] = detailed('tag:string, target:string, amount:number, duration:number, easing:string?',
    `Tween a widget’s ${property}. The completion callback receives the tween tag.`,
    { tag: 'Unique tween ID.', target: 'Existing widget ID.', amount: `Target ${property}.`,
      duration: 'Duration in seconds.', easing: 'Easing name, such as `linear` or `expoOut`.' });
}
for (const [name, property] of Object.entries({ doTweenCamX: 'world X position', doTweenCamY: 'world Y position',
  doTweenCamZ: 'world Z position', doTweenCamRotationX: 'pitch', doTweenCamRotationY: 'yaw',
  doTweenCamRotationZ: 'roll', doTweenCamZoom: 'zoom' })) {
  menuOverrides[name] = detailed('tag:string, amount:number, duration:number, easing:string?',
    `Tween the menu camera’s ${property}; completion calls onTweenCompleted(tag).`,
    { tag: 'Unique camera tween ID.', amount: `Target ${property}.`,
      duration: 'Duration in seconds.', easing: 'Easing name, default linear.' });
}
Object.assign(menuOverrides, {
  'ui.clear': entry('', 'Remove all widgets, animated states, and active widget tweens from this menu screen.'),
  'machine.getSongExitTarget': entry('', 'Read the current song-exit destination (`menu`, `world`, or `selector`).', 'string'),
  'machine.setSongExitTarget': detailed('target:string', 'Change where a song returns after finishing or quitting.',
    { target: '`menu`, `world`, or `selector`.' }, 'string'),
  'machine.openCharacterEditor': entry('', 'Open Blockified’s character editor and save machine data first.'),
  'machine.openSongDetails': detailed('id:string', 'Open built-in details for an available song.',
    { id: 'Song ID.' }, 'boolean'),
  'camera.reset': entry('', 'Reset the custom machine-menu camera to its original position, rotation, zoom, and FOV.'),
  'addToGroup': detailed('group:string, child:string', 'Put a widget into a compositing group, which controls its combined rendering.',
    { group: 'Existing group ID.', child: 'Widget to add.' }),
  'removeFromGroup': detailed('child:string', 'Remove a widget from its compositing group.',
    { child: 'Widget ID.' }),
  'cancelTimer': detailed('tag:string', 'Cancel an active menu timer.', { tag: 'Timer ID.' }, 'boolean'),
  'cancelTween': detailed('tag:string', 'Cancel a widget, camera, or blur tween by tag.', { tag: 'Tween ID.' }, 'boolean'),
  'doTweenColor': detailed('tag:string, target:string, color:string, duration:number, easing:string?',
    'Tween a widget’s color from its current value to a hex color.',
    { tag: 'Unique tween ID.', target: 'Existing widget ID.', color: 'Target hex color or packed RGB number.',
      duration: 'Duration in seconds.', easing: 'Easing name.' }),
  'doTweenEffect': detailed('tag:string, target:string, property:string, amount:number, duration:number, easing:string?',
    'Tween a numeric gradient, mask, or clip effect property.',
    { tag: 'Unique tween ID.', target: 'Existing widget ID.',
      property: 'Numeric effect field such as `maskX`, `gradientAngle`, or `clipWidth`.',
      amount: 'Target numeric value.', duration: 'Duration in seconds.', easing: 'Easing name.' }),
  'getHoveredObject': entry('', 'Return the topmost visible widget ID beneath the cursor, or an empty string.', 'string'),
  'getObjectParent': detailed('child:string', 'Get the current parent widget ID.', { child: 'Widget ID.' }, 'string'),
  'getTextQuality': detailed('id:string', 'Get the current font oversampling factor of a menu text widget.',
    { id: 'Text widget ID.' }, 'number'),
  'getTextString': detailed('id:string', 'Read the current text of a label or button.', { id: 'Text widget ID.' }, 'string'),
  'makeLuaGradient': gameplayOverrides.makeLuaGradient,
  'makeLuaGroup': gameplayOverrides.makeLuaGroup,
  'removeObjectGradient': detailed('id:string', 'Disable the gradient on a widget without deleting the widget.',
    { id: 'Widget ID.' }),
  'removeObjectMask': detailed('id:string', 'Clear the mask assigned to a widget.', { id: 'Widget ID.' }),
  'removeObjectParent': detailed('child:string, keepPosition:boolean?',
    'Detach a widget from its parent. The default retains the child’s world transform.',
    { child: 'Widget ID.', keepPosition: 'True preserves world position; default true.' }),
  'setBlendMode': gameplayOverrides.setBlendMode,
  'setObjectClip': detailed('id:string, type:string, x:number?, y:number?, width:number?, height:number?, radius:number?, softness:number?',
    'Clip a widget locally to a rectangle, circle, or rounded shape.',
    { id: 'Widget ID.', type: '`none`, `rect`, `circle`, or `rounded`.',
      x: 'Clip center X in local coordinates.', y: 'Clip center Y in local coordinates.',
      width: 'Local clip width.', height: 'Local clip height.', radius: 'Corner/circle radius.',
      softness: 'Soft-edge amount.' }),
  'setObjectGradient': gameplayOverrides.setObjectGradient,
  'setSoundPitch': detailed('tag:string, pitch:number', 'Change pitch of a tagged menu sound.',
    { tag: 'Sound instance ID.', pitch: 'Playback pitch multiplier.' }),
  'setSoundTime': detailed('tag:string, milliseconds:number', 'Seek a tagged menu sound to a time in milliseconds.',
    { tag: 'Sound instance ID.', milliseconds: 'Target audio time, not seconds.' }),
  'setSoundVolume': detailed('tag:string, volume:number', 'Change volume of a tagged menu sound.',
    { tag: 'Sound instance ID.', volume: 'Target volume level.' }),
  'setTextAlignment': detailed('id:string, alignment:string', 'Align menu label/button text.',
    { id: 'Text widget ID.', alignment: '`left`, `center`, or `right`.' }),
  'setTextColor': detailed('id:string, color:string', 'Set menu text color.',
    { id: 'Text widget ID.', color: 'Hex color or packed RGB number.' }),
  'setTextFont': detailed('id:string, font:string', 'Use a font asset resolved from the current mod’s font roots.',
    { id: 'Text widget ID.', font: 'Font path, such as `fonts/custom.ttf`.' }),
  'setTextItalic': detailed('id:string, enabled:boolean', 'Toggle italic styling for menu text.',
    { id: 'Text widget ID.', enabled: 'True enables italic text.' }),
  'setTextSize': detailed('id:string, pixels:number', 'Set text size in pixels relative to the 16-pixel Lua baseline.',
    { id: 'Text widget ID.', pixels: 'Desired text size in menu canvas pixels.' }),
  'setTextString': detailed('id:string, text:string', 'Change the text displayed by a label or button.',
    { id: 'Text widget ID.', text: 'New displayed text.' }),
  'setWorldSpriteLighting': detailed('id:string, enabled:boolean',
    'Enable or disable world lighting for a 3D menu widget and restore its render mode to auto.',
    { id: '3D widget ID.', enabled: 'True respects world lighting; false uses flat rendering.' }),
  'soundExists': detailed('tag:string', 'Check whether a tagged menu sound exists.', { tag: 'Sound instance ID.' }, 'boolean')
});
for (const [name, action] of Object.entries({ pauseSound: 'Pause', resumeSound: 'Resume', stopSound: 'Stop' })) {
  menuOverrides[name] = detailed('tag:string', `${action} a tagged menu sound.`, { tag: 'Sound instance ID.' });
}
for (const [name, value] of Object.entries({ getSoundPitch: 'pitch multiplier',
  getSoundTime: 'time in milliseconds', getSoundVolume: 'volume level' })) {
  menuOverrides[name] = detailed('tag:string', `Read a tagged menu sound’s ${value}.`,
    { tag: 'Sound instance ID.' }, 'number');
}
for (const [name, value] of Object.entries({ getX: 'world X position', getY: 'world Y position',
  getZ: 'world Z position', getPitch: 'pitch', getYaw: 'yaw', getRoll: 'roll',
  getZoom: 'zoom', getFOV: 'field of view in degrees' })) {
  menuOverrides[`camera.${name}`] = entry('', `Read the custom menu camera’s ${value}.`, 'number');
}
const menuFunctions = {};
const tableNames = [
  ['ui', /\bui\.set\("([^"]+)"/g],
  ['machine', /\bmachine\.set\("([^"]+)"/g],
  ['camera', /\bcamera\.set\("([^"]+)"/g]
];
for (const [prefix, regex] of tableNames) {
  for (const name of unique(matchNames(menuSource, regex))) {
    if (prefix === 'machine' && ['profileId', 'id', 'tag'].includes(name)) continue;
    menuFunctions[`${prefix}.${name}`] = menuOverrides[`${prefix}.${name}`] ||
      entry('...', `Blockified machine-menu ${prefix} API.`);
  }
}
for (const name of ['gradient', 'group'])
  menuFunctions[`ui.${name}`] = menuOverrides[`ui.${name}`];
const menuGlobals = unique(matchNames(menuSource, /\bglobals\.set\("([^"]+)"/g)
  .concat(helperNames)
  .concat(matchNames(menuSource, /installTweenFunction\("([^"]+)"/g))
  .concat(matchNames(menuSource, /installCameraAxisTween\("([^"]+)"/g))
  .concat(Object.keys(menuOverrides).filter(name => !name.includes('.'))));
for (const name of menuGlobals) {
  if (['ui', 'machine', 'camera', 'cursor', 'blockifiedEditor', 'machineData', 'screenWidth', 'screenHeight'].includes(name)) continue;
  menuFunctions[name] = menuOverrides[name] ||
    entry('...', 'Registered by Blockified machine-menu Lua.');
}
const menuEvents = {};
for (const name of ['onOpen', 'onClose', 'onUpdate', 'onTimerCompleted', 'onTweenCompleted',
  'onSoundFinished', 'onMouseDown', 'onMouseUp', 'onClick', 'onHover', 'onHoverExit',
  'onKeyPress', 'onKeyRelease', 'onScroll']) {
  const args = { onUpdate: 'dt:number', onTimerCompleted: 'tag:string', onTweenCompleted: 'tag:string',
    onClick: 'id:string', onHover: 'id:string', onHoverExit: 'id:string',
    onKeyPress: 'key:string', onKeyRelease: 'key:string', onScroll: 'dy:number, dx:number' }[name] || '';
  menuEvents[name] = entry(args, 'Machine-menu callback. Widget methods such as `function play:onClick()` can be used where applicable.');
}
const menuVariables = {
  screenWidth: { documentation: 'Fixed menu canvas width (1280), independent of Minecraft window size.' },
  screenHeight: { documentation: 'Fixed menu canvas height (720), independent of Minecraft window size.' },
  machineData: { documentation: 'Persistent data table for the current machine.' },
  'machine.id': { documentation: 'ID/tag of the current virtual or physical machine.' },
  'machine.tag': { documentation: 'Tag of the current machine.' },
  'machine.profileId': { documentation: 'Machine profile ID.' }
};

const write = (name, data) => fs.writeFileSync(path.join(here, 'data', name), JSON.stringify(data, null, 2) + '\n');
write('gameplay.json', {
  $source_notice: 'Adapted from Snirozu/Funkin-Script-AutoComplete data/psych_1.0.json; filtered and modified for Blockified Engine. See LICENSE and THIRD_PARTY_NOTICES.md.',
  functions: gameplayFunctions, events: gameplayEvents, variables: gameplayVars
});
write('menu.json', { functions: menuFunctions, events: menuEvents, variables: menuVariables });
console.log(`Generated ${Object.keys(gameplayFunctions).length} gameplay functions and ${Object.keys(menuFunctions).length} menu functions.`);
