# Blockified Lua AutoComplete for VS Code

Standalone, offline Lua completion for Blockified Engine scripts. Inspired by Snirozu's [Funkin Script AutoCompleter](https://github.com/Snirozu/Funkin-Script-AutoComplete) and built from its Psych Engine API data, but filtered against Blockified's actual Lua registrations. Includes hover docs, argument hints, and callback snippets. The original extension is **not** required.

Accepting a function suggests `functionName()` and leaves the cursor between the parentheses; it does not insert argument placeholders. Signature help shows each argument's meaning. Use the popup's arrows to browse argument explanations without moving the cursor. Callback completions insert a `function … end` body with a blank line after the insertion point.

## Install

In VS Code, open **Extensions → … → Install from VSIX…** and select `blockified-lua-autocomplete.vsix`. Or run `code --install-extension blockified-lua-autocomplete.vsix` if `code` is on your PATH. This does not install anything into Minecraft.

## Contexts

- Gameplay Lua is the default (`scripts`, `data`, `stages`, `custom_events`, `custom_notetypes`).
- `machines/<id>/menu.lua` and `machines/<id>/screens/*.lua` use the machine-menu API, including `ui`, `machine`, and `camera`.
- For files elsewhere, put `---@blockifiedMenu` or `---@blockifiedGameplay` near the top. `blockifiedLua.context` can force a context for the whole workspace or a file-specific VS Code setting.
- Click the **Blockified: Gameplay Lua / Menu Lua** indicator in VS Code's status bar, or run **Blockified Lua: Select Script API** from the Command Palette. The choice adds a per-file header; **Auto** removes it. A file header takes priority over the workspace setting.
- Unsaved **Untitled** files work too, as long as their language mode (bottom right) is **Lua**. Use the per-file header or status-bar selector before saving.
- `blockifiedLua.requireMarker` keeps completions out of unrelated Lua files.

The extension suggests registered functions, not arbitrary `setProperty` paths or assets from a particular mod. Generic Psych documentation can differ in edge cases; Blockified-only and changed signatures are explicitly overridden. Unsupported/no-op Psych APIs are deliberately omitted. This is editor assistance, not Lua validation.

If the upstream Funkin Script AutoCompleter is also enabled in this workspace, both extensions can offer suggestions. This extension marks its entries **Blockified** and sorts them first, but VS Code does not let one extension suppress another extension's completions. Disable the upstream extension for the workspace if you want only Blockified suggestions and documentation.

## Develop

From this folder, run `npm install`, `npm run generate-data`, `npm test`, then `npm run package`. The data generator reads the checked-in Blockified Java runtime and upstream Psych API data; rerun it after Lua API changes. No network request is made by the installed extension.

See `THIRD_PARTY_NOTICES.md` and `LICENSE` in the extension folder for upstream attribution and terms.
