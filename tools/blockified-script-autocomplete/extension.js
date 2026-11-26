const gameplay = require('./data/gameplay.json');
const menu = require('./data/menu.json');

function contextFor(fileName, text, configured = 'auto') {
  const header = text.slice(0, 2048);
  if (/^\s*---@blockifiedMenu\b/im.test(header)) return 'menu';
  if (/^\s*---@blockifiedGameplay\b/im.test(header)) return 'gameplay';
  if (configured === 'gameplay' || configured === 'menu') return configured;
  const normalized = fileName.replace(/\\/g, '/').toLowerCase();
  return /(?:^|\/)machines\/[^/]+\/(?:menu\.lua|screens\/[^/]+\.lua)$/.test(normalized)
    ? 'menu' : 'gameplay';
}

function hasMarker(text) {
  return /^\s*---@blockified(?:Menu|Gameplay)\b/im.test(text.slice(0, 2048));
}

function argumentsFor(definition) {
  const raw = definition?.args;
  if (!raw || raw === 'void') return [];
  if (Array.isArray(raw)) return raw.map(String);
  // Types from the upstream Psych database may contain commas in generics.
  const result = [];
  let depth = 0;
  let part = '';
  for (const char of String(raw)) {
    if (char === '<' || char === '{' || char === '(') depth++;
    if (char === '>' || char === '}' || char === ')') depth = Math.max(0, depth - 1);
    if (char === ',' && !depth) {
      result.push(part.trim());
      part = '';
    } else part += char;
  }
  if (part.trim()) result.push(part.trim());
  return result;
}

function argumentLabel(arg) {
  return arg.replace(/^\?/, '').split(/[:=]/)[0].trim().replace(/[^\w]/g, '') || 'value';
}

function snippetFor(name, definition, callback = false) {
  const args = argumentsFor(definition);
  if (callback) return `function ${name}(${args.map(argumentLabel).join(', ')})\n\t$0\n\nend`;
  return `${name}($0)`;
}

function parameterDescription(definition, arg) {
  const name = argumentLabel(arg);
  if (definition.parameters?.[name]) return definition.parameters[name];
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const docs = definition.documentation || '';
  const line = docs.match(new RegExp('(?:^|\\n)\\s*(?:[-*]\\s*)?`?' + escaped +
    '`?\\s*(?:\\*\\(Optional\\)\\*|\\(Optional\\))?\\s*[-–:]\\s*([^\\n]+)', 'i'));
  if (line) return line[1].trim();
  const type = arg.split(':')[1]?.split('=')[0]?.trim();
  const defaultValue = arg.includes('=') ? ` Default: ${arg.split('=').slice(1).join('=').trim()}.` : '';
  return `${type ? `Expected ${type}.` : 'Argument.'}${arg.trim().startsWith('?') || arg.trim().endsWith('?') ? ' Optional.' : ''}${defaultValue}`;
}

function signatureModel(name, definition, currentParameter, viewedParameter = null) {
  const params = argumentsFor(definition);
  const selected = viewedParameter === null ? Math.min(currentParameter, Math.max(0, params.length - 1))
    : Math.min(Math.max(0, viewedParameter), Math.max(0, params.length - 1));
  return {
    label: `${name}(${params.join(', ')})`,
    parameters: params.map(param => ({ label: param, documentation: parameterDescription(definition, param) })),
    activeParameter: Math.min(currentParameter, Math.max(0, params.length - 1)),
    activeSignature: selected,
    views: params.length ? params.map((param, index) =>
      `**${index + 1}. ${argumentLabel(param)}** — ${parameterDescription(definition, param)}`) :
      [definition.documentation || 'No arguments.']
  };
}

function candidateList(data, prefix) {
  if (prefix) {
    const dotted = prefix + '.';
    return Object.entries(data.functions)
      .filter(([name]) => name.startsWith(dotted))
      .map(([name, definition]) => ({ name, label: name.slice(dotted.length), definition, kind: 'function' }))
      .concat(Object.entries(data.variables)
        .filter(([name]) => name.startsWith(dotted))
        .map(([name, definition]) => ({ name, label: name.slice(dotted.length), definition, kind: 'variable' })));
  }
  return Object.entries(data.functions)
    .filter(([name]) => !name.includes('.'))
    .map(([name, definition]) => ({ name, label: name, definition, kind: 'function' }))
    .concat(Object.entries(data.events)
      .map(([name, definition]) => ({ name, label: name, definition, kind: 'event' })))
    .concat(Object.entries(data.variables)
      .filter(([name]) => !name.includes('.'))
      .map(([name, definition]) => ({ name, label: name, definition, kind: 'variable' })));
}

function symbolAt(line, column) {
  const regex = /[A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)*/g;
  for (const match of line.matchAll(regex)) {
    if (column >= match.index && column <= match.index + match[0].length) {
      return { name: match[0], start: match.index, end: match.index + match[0].length };
    }
  }
  return null;
}

function signatureAt(source) {
  const stack = [];
  let quote = '';
  let escaped = false;
  let bracketDepth = 0;
  for (let index = 0; index < source.length; index++) {
    const char = source[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === quote) quote = '';
      continue;
    }
    if (char === '"' || char === "'") { quote = char; continue; }
    if (char === '{' || char === '[') { bracketDepth++; continue; }
    if (char === '}' || char === ']') { bracketDepth = Math.max(0, bracketDepth - 1); continue; }
    if (char === '(') {
      const before = source.slice(0, index).match(/([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)*)\s*$/);
      stack.push({ name: before?.[1] || '', parameter: 0, bracketDepth });
    } else if (char === ')') stack.pop();
    else if (char === ',' && stack.length && bracketDepth === stack.at(-1).bracketDepth)
      stack.at(-1).parameter++;
  }
  return stack.at(-1) || null;
}

function activate(vscodeContext) {
  const vscode = require('vscode');
  // A bare language selector also covers untitled/unsaved Lua buffers.
  const selector = 'lua';
  const modeFor = document => {
    const settings = vscode.workspace.getConfiguration('blockifiedLua', document.uri);
    const text = document.getText();
    if (settings.get('requireMarker', false) && !hasMarker(text)) return null;
    const configured = settings.get('context', 'auto');
    return {
      name: contextFor(document.fileName, text, configured),
      source: hasMarker(text) ? 'file' : configured !== 'auto' ? 'setting' : 'auto'
    };
  };
  const selectData = document => {
    const mode = modeFor(document);
    return mode ? (mode.name === 'menu' ? menu : gameplay) : null;
  };
  const description = (name, definition) => {
    const args = argumentsFor(definition).join(', ');
    const signature = definition.args !== undefined ? `\`${name}(${args})\`\n\n` : `\`${name}\`\n\n`;
    const parameters = definition.args !== undefined ? argumentsFor(definition) : [];
    const help = parameters.length ? '\n\n**Arguments**\n\n' + parameters.map(param =>
      `- \`${param}\` — ${parameterDescription(definition, param)}`).join('\n') : '';
    return new vscode.MarkdownString(signature + (definition.documentation || 'Blockified Lua API.') + help +
      (definition.deprecated ? `\n\nDeprecated: ${definition.deprecated}` : ''));
  };
  const completion = vscode.languages.registerCompletionItemProvider(selector, {
    provideCompletionItems(document, position) {
      const data = selectData(document);
      if (!data) return [];
      const before = document.lineAt(position.line).text.slice(0, position.character);
      const member = before.match(/\b(ui|machine|camera)\.([A-Za-z_]\w*)?$/);
      const prefix = member ? member[1] : null;
      const range = document.getWordRangeAtPosition(position) || new vscode.Range(position, position);
      return candidateList(data, prefix).map(candidate => {
        const kind = candidate.kind === 'event' ? vscode.CompletionItemKind.Event
          : candidate.kind === 'variable' ? vscode.CompletionItemKind.Variable
            : vscode.CompletionItemKind.Function;
        const item = new vscode.CompletionItem({ label: candidate.label, description: 'Blockified' }, kind);
        item.range = range;
        item.filterText = candidate.label;
        item.sortText = `!blockified.${candidate.label}`;
        item.detail = candidate.kind === 'function'
          ? `${candidate.name}(${argumentsFor(candidate.definition).join(', ')})`
          : candidate.kind === 'event' ? `Blockified callback · ${candidate.name}` : candidate.name;
        item.documentation = description(candidate.name, candidate.definition);
        if (candidate.kind !== 'variable') {
          item.insertText = new vscode.SnippetString(snippetFor(candidate.label, candidate.definition,
            candidate.kind === 'event'));
          if (candidate.kind === 'function')
            item.command = { command: 'editor.action.triggerParameterHints', title: 'Parameter hints' };
        }
        return item;
      });
    }
  }, '.');
  const hover = vscode.languages.registerHoverProvider(selector, {
    provideHover(document, position) {
      const data = selectData(document);
      if (!data) return null;
      const symbol = symbolAt(document.lineAt(position.line).text, position.character);
      if (!symbol) return null;
      const definition = data.functions[symbol.name] || data.events[symbol.name] || data.variables[symbol.name];
      if (!definition) return null;
      return new vscode.Hover(description(symbol.name, definition),
        new vscode.Range(position.line, symbol.start, position.line, symbol.end));
    }
  });
  const signatures = vscode.languages.registerSignatureHelpProvider(selector, {
    provideSignatureHelp(document, position) {
      const data = selectData(document);
      if (!data) return null;
      const start = new vscode.Position(Math.max(0, position.line - 20), 0);
      const call = signatureAt(document.getText(new vscode.Range(start, position)));
      if (!call || !data.functions[call.name]) return null;
      const definition = data.functions[call.name];
      const model = signatureModel(call.name, definition, call.parameter);
      const result = new vscode.SignatureHelp();
      result.signatures = model.views.map(view => {
        const signature = new vscode.SignatureInformation(model.label, new vscode.MarkdownString(view));
        signature.parameters = model.parameters.map(param =>
          new vscode.ParameterInformation(param.label, param.documentation));
        return signature;
      });
      result.activeSignature = model.activeSignature;
      result.activeParameter = model.activeParameter;
      return result;
    }
  }, '(', ',');
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  status.command = 'blockifiedLua.selectContext';
  const updateStatus = () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'lua') { status.hide(); return; }
    const mode = modeFor(editor.document);
    status.text = mode ? `$(symbol-method) Blockified: ${mode.name === 'menu' ? 'Menu Lua' : 'Gameplay Lua'}`
      : '$(symbol-method) Blockified: Off';
    status.tooltip = mode ? `Blockified Lua API: ${mode.name} (${mode.source}). Click to change for this file.`
      : 'Blockified Lua is disabled here. Click to choose this file’s API.';
    status.show();
  };
  const chooseContext = vscode.commands.registerCommand('blockifiedLua.selectContext', async () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'lua') {
      vscode.window.showInformationMessage('Open a Lua file to choose its Blockified API.');
      return;
    }
    const selected = await vscode.window.showQuickPick([
      { label: 'Gameplay Lua', description: 'Song, event, stage, or note-type script', value: '---@blockifiedGameplay' },
      { label: 'Machine Menu Lua', description: 'Machine menu or screen script', value: '---@blockifiedMenu' },
      { label: 'Auto (path / workspace default)', description: 'Remove this file’s API marker', value: null }
    ], { placeHolder: 'Choose which Blockified API this Lua file uses' });
    if (!selected) return;
    const document = editor.document;
    const markerLine = document.getText().slice(0, 2048).split(/\r?\n/)
      .findIndex(line => /^\s*---@blockified(?:Menu|Gameplay)\b/.test(line));
    await editor.edit(builder => {
      if (markerLine >= 0) {
        if (selected.value) builder.replace(document.lineAt(markerLine).range, selected.value);
        else if (markerLine + 1 < document.lineCount)
          builder.delete(new vscode.Range(markerLine, 0, markerLine + 1, 0));
        else builder.delete(document.lineAt(markerLine).range);
      } else if (selected.value) builder.insert(new vscode.Position(0, 0), selected.value + '\n');
    });
    updateStatus();
  });
  const activeChanged = vscode.window.onDidChangeActiveTextEditor(updateStatus);
  const documentChanged = vscode.workspace.onDidChangeTextDocument(event => {
    if (event.document === vscode.window.activeTextEditor?.document) updateStatus();
  });
  const configChanged = vscode.workspace.onDidChangeConfiguration(event => {
    if (event.affectsConfiguration('blockifiedLua')) updateStatus();
  });
  updateStatus();
  vscodeContext.subscriptions.push(completion, hover, signatures, status, chooseContext,
    activeChanged, documentChanged, configChanged);
}

function deactivate() {}
module.exports = { activate, deactivate, contextFor, hasMarker, argumentsFor, snippetFor,
  parameterDescription, signatureModel, candidateList, symbolAt, signatureAt };
