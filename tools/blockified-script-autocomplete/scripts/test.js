const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const child = require('node:child_process');
const Module = require('node:module');
const api = require('../extension');
const gameplay = require('../data/gameplay.json');
const menu = require('../data/menu.json');

const root = path.resolve(__dirname, '../../..');
const runtime = fs.readFileSync(path.join(root, 'src/main/java/com/fnfmod/client/lua/PsychLuaRuntime.java'), 'utf8');
const machine = fs.readFileSync(path.join(root, 'src/main/java/com/fnfmod/client/lua/MachineMenuRuntime.java'), 'utf8');
assert.equal(api.contextFor('C:\\mods\\Test\\machines\\test\\menu.lua', ''), 'menu');
assert.equal(api.contextFor('/mods/Test/machines/test/screens/extras.lua', ''), 'menu');
assert.equal(api.contextFor('/mods/Test/data/song/script.lua', ''), 'gameplay');
assert.equal(api.contextFor('/other/script.lua', '---@blockifiedMenu\n'), 'menu');
assert.equal(api.contextFor('/other/script.lua', '---@blockifiedGameplay\n', 'menu'), 'gameplay');
assert.equal(api.hasMarker('---@blockifiedMenu\n'), true);
assert.equal(api.hasMarker('print("hello")'), false);
assert.equal(api.argumentsFor({ args: 'tag:String, values:Map<String, Number>, ?enabled:Bool = true' }).length, 3);
assert.equal(api.snippetFor('ui.button', { args: 'id:string, text:string' }),
  'ui.button($0)');
assert.equal(api.snippetFor('onBeatHit', { args: '' }, true), 'function onBeatHit()\n\t$0\n\nend');
assert.equal(api.snippetFor('onUpdate', { args: 'elapsed:Float' }, true), 'function onUpdate(elapsed)\n\t$0\n\nend');
assert.equal(api.symbolAt('machine.playSong("test")', 12).name, 'machine.playSong');
assert.equal(api.signatureAt('machine.playSong("test", ').parameter, 1);
assert.equal(api.signatureAt('machine.playSong("a,b", {x=1, y=2}, ').parameter, 2);
assert.equal(api.signatureAt('doTweenText("t", getProperty("x,y"), ').parameter, 2);
assert.match(api.parameterDescription({ args: 'tag:string', documentation: '* `tag` - The object tag.' }, 'tag:string'), /object tag/);
const model = api.signatureModel('doTweenText', { args: 'tag:string, target:string, amount:number',
  documentation: 'Tween text.', parameters: { amount: 'Final value.' } }, 2, 0);
assert.equal(model.activeParameter, 2);
assert.equal(model.activeSignature, 0);
assert.match(model.views[2], /Final value/);

for (const name of ['makeLuaSprite', 'addLuaSprite', 'triggerEvent', 'setTextQuality', 'doTweenText',
  'setObjectMask', 'setObjectGradient', 'setObjectParent', 'removeObjectParent', 'getObjectParent',
  'addToGroup', 'removeFromGroup']) assert.ok(gameplay.functions[name], `Missing gameplay ${name}`);
for (const name of ['initLuaShader', 'runHaxeCode', 'startVideo', 'changeDiscordPresence'])
  assert.equal(gameplay.functions[name], undefined, `No-op ${name} must be omitted`);
for (const name of ['ui.button', 'ui.gradient', 'ui.group', 'machine.playSong', 'machine.openScreen',
  'camera.setPosition', 'doTweenCamX', 'doTweenX', 'setObjectMask', 'setObjectParent'])
  assert.ok(menu.functions[name], `Missing menu ${name}`);
assert.equal(menu.functions['machine.playSong'].args.includes('options'), true);
assert.equal(menu.functions.playSound.args.startsWith('tag:string, sound:string'), true);
assert.equal(menu.functions['ui.slider'].args.includes('min'), false);
assert.equal(Object.values(menu.functions).filter(definition => definition.documentation.startsWith('Registered')).length, 0);
assert.equal(menu.functions['machine.tag'], undefined, 'Properties should be variables, not functions');
assert.ok(menu.variables['machine.tag']);
assert.ok(gameplay.events.onBeatHit);
assert.match(gameplay.events.onStartCountdown.documentation, /does not hold/);
assert.ok(menu.events.onOpen);
assert.ok(runtime.includes('installLayerEffects(globals)'));
assert.ok(machine.includes('LuaObjectParenting.install(globals'));

// Exercise the VS Code registration against an unsaved Lua buffer. The previous
// file-only selector left Untitled-1 with only upstream Psych suggestions.
const unsaved = {
  languageId: 'lua', fileName: 'Untitled-1', uri: { scheme: 'untitled' },
  lines: ['---@blockifiedGameplay', 'dotwee'],
  getText() { return this.lines.join('\n'); },
  lineAt(index) { return { text: this.lines[index] }; },
  getWordRangeAtPosition() { return null; }
};
let completionSelector;
let completionProvider;
const disposable = { dispose() {} };
const fakeVscode = {
  CompletionItemKind: { Event: 1, Variable: 2, Function: 3 },
  StatusBarAlignment: { Right: 1 },
  CompletionItem: class { constructor(label, kind) { this.label = label; this.kind = kind; } },
  SnippetString: class { constructor(value) { this.value = value; } },
  MarkdownString: class { constructor(value) { this.value = value; } },
  Range: class { constructor(...args) { this.args = args; } },
  Position: class { constructor(line, character) { this.line = line; this.character = character; } },
  workspace: {
    getConfiguration() { return { get(key, fallback) { return fallback; } }; },
    onDidChangeTextDocument() { return disposable; },
    onDidChangeConfiguration() { return disposable; }
  },
  window: {
    activeTextEditor: { document: unsaved },
    createStatusBarItem() { return { show() {}, hide() {} }; },
    onDidChangeActiveTextEditor() { return disposable; }
  },
  commands: { registerCommand() { return disposable; } },
  languages: {
    registerCompletionItemProvider(selector, provider) {
      completionSelector = selector; completionProvider = provider; return disposable;
    },
    registerHoverProvider() { return disposable; },
    registerSignatureHelpProvider() { return disposable; }
  }
};
const originalLoad = Module._load;
Module._load = function(request, parent, isMain) {
  if (request === 'vscode') return fakeVscode;
  return originalLoad.call(this, request, parent, isMain);
};
try { api.activate({ subscriptions: [] }); }
finally { Module._load = originalLoad; }
assert.equal(completionSelector, 'lua');
const suggestions = completionProvider.provideCompletionItems(unsaved, { line: 1, character: 6 });
const tween = suggestions.find(item => item.label.label === 'doTweenText');
assert.ok(tween, 'Blockified completion must be available in an unsaved Lua file');
assert.equal(tween.insertText.value, 'doTweenText($0)');
assert.equal(tween.label.description, 'Blockified');
assert.match(tween.sortText, /^!blockified/);
unsaved.lines = ['---@blockifiedMenu', 'ui.'];
const menuSuggestions = completionProvider.provideCompletionItems(unsaved, { line: 1, character: 3 });
assert.ok(menuSuggestions.some(item => item.label.label === 'button'));
assert.equal(menuSuggestions.some(item => item.label.label === 'makeLuaSprite'), false);

// Re-generating the checked-in catalog must be deterministic.
const before = ['gameplay.json', 'menu.json'].map(name => fs.readFileSync(path.join(__dirname, '../data', name), 'utf8'));
child.execFileSync(process.execPath, [path.join(__dirname, 'generate-data.js')], { cwd: path.join(__dirname, '..') });
const after = ['gameplay.json', 'menu.json'].map(name => fs.readFileSync(path.join(__dirname, '../data', name), 'utf8'));
assert.deepEqual(after, before);
console.log(`Passed: ${Object.keys(gameplay.functions).length} gameplay functions, ${Object.keys(menu.functions).length} menu functions.`);
