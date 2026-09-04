# Lua layers: gradients, masks, blending and parenting

These are Blockified additions, shared by gameplay Lua and machine-menu Lua. They do not load arbitrary Psych GLSL shaders.

## Scope

Effects work on Lua sprites (including animated sprites), text, graphics, and menu widgets in 2D or 3D. The extra 2D characters created with `addBlockifiedCharacter` also accept effects and can supply masks. Effects produce a flat material; their world planes still depth-test against Minecraft geometry unless `seeThrough` is enabled. They do not add post-processing to vanilla entities or BBS 3D meshes.

Parenting currently connects gameplay Lua sprite/text/graphic objects to each other, or menu widgets to each other. Extra performer characters and vanilla/BBS entities retain their existing positioning APIs rather than participating in this hierarchy. Parent and child must be in the same camera/space.

## Gradients

```lua
-- Gameplay: created and added immediately. Menu Lua also offers ui.gradient(...).
makeLuaGradient('fade', 100, 100, 400, 200, 'linear', {
    {at = 0, color = 'black', alpha = 1},
    {at = 1, color = 'black', alpha = 0}
}, 0)

setObjectGradient('photo', 'radial', {
    {at = 0, color = 'white', alpha = 1},
    {at = 0.6, color = '#B82BFF', alpha = 1},
    {at = 1, color = 'black', alpha = 0}
})
```

`makeLuaGradient(tag, x, y, width, height, type, stops, angle)` creates a standalone rectangle. Gameplay uses the usual top-left sprite origin; menu widgets use their normal center origin. Move it to world space with `setObjectCamera(tag, 'world')` in gameplay, or `ui.worldAbsolute(ui.get(tag), x, y, z)` in menus.

`setObjectGradient(tag, type, stops, angle)` multiplies an existing object's pixels by the gradient without modifying its source image. Use `linear`, `radial`, `angular`, or `none`. `removeObjectGradient(tag)` disables it. Stops accept 2–16 entries (colors alone are evenly spaced). Each stop can have `at` (0–1), `color`, and `alpha` (0–1).

Properties: `gradientAngle` (degrees), `gradientX/Y` (center, default 0.5), `gradientRadius` (default 0.5), `gradientStrength` (0–1). Without an explicit stop list, `gradientColor1/2` and `gradientAlpha1/2` define the endpoints. The editor's editable `gradientStops` string is the actual stop data: `0:#FFFFFF:1;1:#000000:0`. Clearing it returns to the two endpoint controls. Editing an endpoint color does this automatically.

## Masks

```lua
setObjectMask('photo', 'fade', 'alpha', false, true)
-- target, mask object, mode, inverted, hide mask from normal rendering
```

- **Alpha:** opaque pixels reveal, transparent pixels hide. Opaque black reveals just like opaque white. Use this for black-to-transparent images/gradients.
- **Luminance:** white reveals, black hides, gray partially reveals. Source alpha also applies. Use this for black-and-white masks.
- Inversion reverses the result; a missing source still hides the target, including for inverted masks.

Images, animated sprites, text, graphs, standalone gradients, and compositing groups can be mask sources. The source defaults to `maskOnly = true`: it remains available to the mask renderer without drawing as a visible layer. `removeObjectMask(target)` unlinks the target; set the source's `maskOnly = false` separately to show it again.

Masks fit the target's local rectangle and stay attached as it moves/rotates. `maskX/Y` offset the source in normalized target units; `maskScaleX/Y` default to 1; `maskAngle` is degrees. `maskSoftness` adds a small normalized sampling radius. There is no screen-projected mask mode in this implementation.

Menu widgets can opt into `maskHitTest = true` to ignore near-transparent pixels. Picking uses a cached 64×64 alpha map, not one GPU read per mouse move. Animated pixel-perfect picking adds work; leave it off for ordinary rectangular buttons.

## Blending and clipping

`setBlendMode(tag, mode)` now supports `normal`, `add`, `multiply`, `screen`, `subtract`, `lighten`, `darken`, `difference`, and `overlay` in both runtimes. Non-normal blending uses the pixels already behind the object; order matters. In 3D this does not bypass the depth test.

```lua
setBlendMode('glow', 'add')
setObjectClip('photo', 'rounded', 0, 0, 1, 1, 0.08, 0.002)
-- tag, type, x, y, width, height, corner radius, edge softness
```

Clip coordinates are normalized local units. Types: `none`, `rect`, `circle` (ellipse when stretched), `rounded`. A smaller rectangle crops without rescaling the image.

## Groups versus parents

`makeLuaGroup(tag, x, y, width, height)` / menu `ui.group(...)` create a compositing surface. `addToGroup(group, child)` and `removeFromGroup(child)` control its members. Children use a 1280×720 local canvas; the group scales that result to its width/height. The group can be placed in 3D as one plane. Group alpha/masks/gradients apply after the children have been composited, so overlapping children do not multiply group opacity. Groups are visual composites; child button interaction is not forwarded through the group's texture.

Parenting does **not** flatten objects. Each child retains its own layer and local transform:

```lua
setObjectParent('label', 'panel') -- child X/Y/Z become 0 at the parent's center
setProperty('label.x', 40)        -- gameplay: local offset, not a world position
setProperty('label.y', -20)

setObjectParent('badge', 'panel', true) -- optional: preserve current placement
removeObjectParent('label')            -- detach while preserving placement
local parent = getObjectParent('badge')
```

Menu equivalent for offsets: `ui.get('label').x = 40`. Parented 2D menu coordinates are always **pixels**, even between 0 and 1; parented 3D menu coordinates are **blocks**. Gameplay world children use the same stage-local pixel units as gameplay sprites (64 pixels = 1 block). Children follow parent position, rotation, scale, and visual resizing. Their own positions/rotations/scales remain editable. Cyclic links and cross-camera parenting are rejected by the functions. Clearing a parent through the function is preferable to editing the `parent` string directly, because the function can preserve placement.

## Tweening and editing

```lua
doTweenEffect('turnGradient', 'fade', 'gradientAngle', 180, 2, 'expoInOut')
doTweenEffect('openCrop', 'photo', 'clipWidth', 1, 0.5, 'expoOut')
```

Numeric effect properties also work with gameplay `setProperty`. Menu Lua uses the returned widget table. `doTweenEffect(tag, object, property, target, duration, easing)` uses the runtime's usual tween clock/callbacks; endpoint colors interpolate per RGB channel.

The Menu Lua Editor has an **Effects** tab for blends, gradients, masks, clipping, group membership, and parenting. **Objects** adds Gradient and Group constructors. Effects participate in undo/redo, property copying, ordinary Lua saving, and keyframe capture. Other scripts outside the editor-managed block remain untouched.

## Cost and testing

The compositor uses reusable GPU targets and no disk cache. Objects without effects or parenting keep the previous render path. Each runtime bounds its layer-target cache to 64 MiB and caps surface dimensions; oversized layers are reduced to fit. Non-normal blending copies the current backdrop, so it costs more than a normal layer. Effects remain flat/unlit materials, including on 3D sprites.

`gradlew validateLuaLayers` runs API/parenting checks. `gradlew validateLuaLayers -PluaLayerGpuChecks` additionally compiles both shaders and checks mask, gradient, and clipping pixels in a hidden OpenGL window. Shader-pack appearance and complete in-game/editor interactions still require testing in Minecraft; passing those checks is not an Iris compatibility guarantee.
