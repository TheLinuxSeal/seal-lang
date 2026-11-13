// Seal for VSCodium and VS Code: starts `seal lsp` (docs/LSP.md) and shows what it finds: errors and
// warnings as you type, what a name is on hover, go to definition, and each file's outline. A
// small client of the Language Server Protocol, written against VS Code's API alone, so the
// extension needs nothing installed with it.
'use strict';

const vscode = require('vscode');
const { spawn } = require('child_process');

let client = null;

function activate(context) {
    const output = vscode.window.createOutputChannel('Seal');
    const diagnostics = vscode.languages.createDiagnosticCollection('seal');
    context.subscriptions.push(output, diagnostics);

    const start = () => {
        const config = vscode.workspace.getConfiguration('seal');
        client = new Client(config.get('path') || 'seal', ['lsp', ...(config.get('serverArgs') || [])], output, diagnostics);
        client.start();
    };
    start();

    context.subscriptions.push(
        vscode.workspace.onDidOpenTextDocument(doc => client && client.opened(doc)),
        vscode.workspace.onDidChangeTextDocument(e => client && client.changed(e.document)),
        vscode.workspace.onDidSaveTextDocument(doc => client && client.saved(doc)),
        vscode.workspace.onDidCloseTextDocument(doc => client && client.closed(doc)),
        vscode.languages.registerHoverProvider('seal', {
            provideHover: async (doc, position) => {
                const r = await client.request('textDocument/hover', at(doc, position));
                return r && r.contents ? new vscode.Hover(new vscode.MarkdownString(r.contents.value)) : null;
            }
        }),
        vscode.languages.registerDefinitionProvider('seal', {
            provideDefinition: async (doc, position) => {
                const r = await client.request('textDocument/definition', at(doc, position));
                return r ? new vscode.Location(vscode.Uri.parse(r.uri), range(r.range)) : null;
            }
        }),
        vscode.languages.registerDocumentSymbolProvider('seal', {
            provideDocumentSymbols: async doc => {
                const r = await client.request('textDocument/documentSymbol', { textDocument: { uri: doc.uri.toString() } });
                return (r || []).map(symbol);
            }
        }),
        vscode.commands.registerCommand('seal.restartServer', async () => {
            await client.stop();
            diagnostics.clear();
            start();
        }),
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration('seal')) {
                vscode.commands.executeCommand('seal.restartServer');
            }
        }),
        { dispose: () => client && client.stop() }
    );
}

function deactivate() {
    return client ? client.stop() : undefined;
}

function at(doc, position) {
    return { textDocument: { uri: doc.uri.toString() }, position: { line: position.line, character: position.character } };
}

function range(r) {
    return new vscode.Range(r.start.line, r.start.character, r.end.line, r.end.character);
}

function symbol(s) {
    // LSP's symbol kinds count from 1, VS Code's from 0.
    const d = new vscode.DocumentSymbol(s.name, s.detail || '', s.kind - 1, range(s.range), range(s.selectionRange));
    d.children = (s.children || []).map(symbol);
    return d;
}

/** The server, and the messages to and from it: each a JSON body after a Content-Length header. */
class Client {
    constructor(command, args, output, diagnostics) {
        this.command = command;
        this.args = args;
        this.output = output;
        this.diagnostics = diagnostics;
        this.nextId = 1;
        this.waiting = new Map();
        this.buffer = Buffer.alloc(0);
        this.process = null;
        this.ready = null;
    }

    start() {
        const folder = vscode.workspace.workspaceFolders && vscode.workspace.workspaceFolders[0];
        this.output.appendLine(`starting ${this.command} ${this.args.join(' ')}`);
        try {
            this.process = spawn(this.command, this.args, { cwd: folder ? folder.uri.fsPath : undefined });
        } catch (e) {
            this.failed(e);
            return;
        }
        this.process.on('error', e => this.failed(e));
        this.process.on('exit', code => {
            this.output.appendLine(`seal lsp exited with ${code}`);
            for (const w of this.waiting.values()) {
                w.resolve(null);
            }
            this.waiting.clear();
            this.process = null;
        });
        this.process.stderr.on('data', data => this.output.append(data.toString()));
        this.process.stdout.on('data', data => this.received(data));
        this.ready = this.send('initialize', {
            processId: process.pid,
            rootUri: folder ? folder.uri.toString() : null,
            capabilities: { textDocument: { documentSymbol: { hierarchicalDocumentSymbolSupport: true } } }
        }).then(() => {
            this.notify('initialized', {});
            for (const doc of vscode.workspace.textDocuments) {
                this.opened(doc);
            }
        });
    }

    failed(e) {
        const message = e.code === 'ENOENT'
            ? `Seal: there is no ${this.command} to start the language server with; set seal.path to the seal command`
            : `Seal: the language server didn't start: ${e.message}`;
        this.output.appendLine(message);
        vscode.window.showErrorMessage(message);
        this.process = null;
    }

    async stop() {
        if (!this.process) {
            return;
        }
        const p = this.process;
        await Promise.race([this.request('shutdown', null), new Promise(r => setTimeout(r, 2000))]);
        this.notify('exit', null);
        setTimeout(() => p.kill(), 1000);
    }

    opened(doc) {
        if (doc.languageId === 'seal' && this.process) {
            this.notify('textDocument/didOpen', {
                textDocument: { uri: doc.uri.toString(), languageId: 'seal', version: doc.version, text: doc.getText() }
            });
        }
    }

    changed(doc) {
        if (doc.languageId === 'seal') {
            this.notify('textDocument/didChange', {
                textDocument: { uri: doc.uri.toString(), version: doc.version },
                contentChanges: [{ text: doc.getText() }]
            });
        }
    }

    saved(doc) {
        if (doc.languageId === 'seal') {
            this.notify('textDocument/didSave', { textDocument: { uri: doc.uri.toString() } });
        }
    }

    closed(doc) {
        if (doc.languageId === 'seal') {
            this.notify('textDocument/didClose', { textDocument: { uri: doc.uri.toString() } });
        }
    }

    async request(method, params) {
        if (!this.process) {
            return null;
        }
        await this.ready;
        return this.send(method, params);
    }

    send(method, params) {
        const id = this.nextId++;
        return new Promise(resolve => {
            this.waiting.set(id, { resolve });
            this.write({ jsonrpc: '2.0', id, method, params });
        });
    }

    notify(method, params) {
        if (!this.process) {
            return;
        }
        const message = { jsonrpc: '2.0', method, params };
        if (method === 'initialized' || !this.ready) {
            this.write(message);
        } else {
            this.ready.then(() => this.write(message));
        }
    }

    write(message) {
        if (!this.process) {
            return;
        }
        const body = Buffer.from(JSON.stringify(message), 'utf8');
        this.process.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);
        this.process.stdin.write(body);
    }

    received(data) {
        this.buffer = Buffer.concat([this.buffer, data]);
        while (true) {
            const end = this.buffer.indexOf('\r\n\r\n');
            if (end < 0) {
                return;
            }
            const header = this.buffer.slice(0, end).toString('ascii');
            const match = /Content-Length:\s*(\d+)/i.exec(header);
            const length = match ? parseInt(match[1], 10) : 0;
            if (this.buffer.length < end + 4 + length) {
                return;
            }
            const body = this.buffer.slice(end + 4, end + 4 + length).toString('utf8');
            this.buffer = this.buffer.slice(end + 4 + length);
            let message;
            try {
                message = JSON.parse(body);
            } catch (e) {
                this.output.appendLine(`not JSON from seal lsp: ${body}`);
                continue;
            }
            this.handle(message);
        }
    }

    handle(message) {
        if (message.id !== undefined && message.method === undefined) {
            const w = this.waiting.get(message.id);
            if (w) {
                this.waiting.delete(message.id);
                if (message.error) {
                    this.output.appendLine(`seal lsp: ${message.error.message}`);
                }
                w.resolve(message.error ? null : message.result);
            }
        } else if (message.method === 'textDocument/publishDiagnostics') {
            const p = message.params;
            this.diagnostics.set(vscode.Uri.parse(p.uri), p.diagnostics.map(d => {
                // LSP's severities count from 1 (error), VS Code's from 0.
                const found = new vscode.Diagnostic(range(d.range), d.message, (d.severity || 1) - 1);
                found.source = d.source;
                if (d.code !== undefined) {
                    found.code = d.code;
                }
                return found;
            }));
        } else if (message.method === 'window/logMessage' || message.method === 'window/showMessage') {
            this.output.appendLine(message.params.message);
        }
    }
}

module.exports = { activate, deactivate };
