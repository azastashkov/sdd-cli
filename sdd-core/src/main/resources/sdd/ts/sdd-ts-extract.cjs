'use strict';
/*
 * sdd's TypeScript reader. Runs under plain `node` with the TypeScript compiler loaded from a
 * sibling file that sdd materialises next to this one.
 *
 * Contract with the Java side: read a request JSON, write a response JSON, exit 0. Any failure is
 * reported by exiting non-zero with a message on stderr — never by writing a partial response,
 * because a partial response would be indistinguishable from a repo that genuinely has little in
 * it.
 *
 * Two rules govern everything here:
 *
 *  1. Only real syntax counts. Every fact comes from a node in the parsed tree, never from a text
 *     scan. A doc comment mentioning a path is trivia and can never become a call site — which is
 *     not hypothetical: the estate's own SDK documents `/api/streams` in a JSDoc block and does not
 *     call it, so a text scraper invents a caller that does not exist.
 *
 *  2. Say what is known, and mark what is not. A value that cannot be resolved statically is
 *     reported as unresolved with its source text attached, never guessed at and never dropped.
 */

const path = require('path');
const fs = require('fs');
const ts = require('./typescript.js');

const PROTOCOL_VERSION = 1;

function fail(message) {
  process.stderr.write(message + '\n');
  process.exit(2);
}

function arg(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : null;
}

/** Repo-relative, forward-slashed, so a path means the same thing on every platform. */
function relPath(repoRoot, file) {
  return path.relative(repoRoot, file).split(path.sep).join('/');
}

// ---------------------------------------------------------------------------- program building

/**
 * A compiler host that refuses to resolve bare specifiers. This is what keeps the sidecar
 * hermetic: it never reads node_modules, so what it reports depends on the repo's own sources and
 * not on what someone happened to install. Relative imports still resolve, which is all the
 * intra-package analysis needs.
 */
function createHost(options) {
  const host = ts.createCompilerHost(options, /*setParentNodes*/ true);
  host.resolveModuleNames = (moduleNames, containingFile) =>
    moduleNames.map((name) => {
      if (!name.startsWith('.')) {
        return undefined;
      }
      const resolved = ts.resolveModuleName(name, containingFile, options, {
        fileExists: host.fileExists,
        readFile: host.readFile,
      });
      return resolved.resolvedModule;
    });
  return host;
}

function buildProgram(fileNames, options) {
  const opts = Object.assign({}, options, {
    noEmit: true,
    // No lib files and no diagnostics: signatures are taken from the written type text, exactly as
    // the Java side takes them from JavaParser's asString(). Loading libs would cost seconds per
    // repo and change nothing that is reported.
    noLib: true,
    skipLibCheck: true,
    skipDefaultLibCheck: true,
    types: [],
  });
  return ts.createProgram(fileNames, opts, createHost(opts));
}

// ---------------------------------------------------------------------------- syntax check

/** One file, parsed alone. The analogue of the Java side's JavaParser gate: syntax only. */
function syntaxCheck(request) {
  const source = ts.createSourceFile(
    request.file || 'anonymous.ts',
    request.text,
    ts.ScriptTarget.Latest,
    /*setParentNodes*/ false,
    scriptKindFor(request.file || 'anonymous.ts'));
  const diagnostics = source.parseDiagnostics || [];
  if (diagnostics.length === 0) {
    return { version: PROTOCOL_VERSION, ok: true, error: null };
  }
  const d = diagnostics[0];
  const pos = source.getLineAndCharacterOfPosition(d.start || 0);
  return {
    version: PROTOCOL_VERSION,
    ok: false,
    error: ts.flattenDiagnosticMessageText(d.messageText, ' ')
      + ' (' + (pos.line + 1) + ',' + (pos.character + 1) + ')',
  };
}

function scriptKindFor(file) {
  if (file.endsWith('.tsx')) return ts.ScriptKind.TSX;
  if (file.endsWith('.jsx')) return ts.ScriptKind.JSX;
  if (file.endsWith('.js') || file.endsWith('.mjs') || file.endsWith('.cjs')) return ts.ScriptKind.JS;
  return ts.ScriptKind.TS;
}

// ---------------------------------------------------------------------------- main

function main() {
  const requestFile = arg('--request');
  const outFile = arg('--out');
  if (!requestFile || !outFile) {
    fail('usage: sdd-ts-extract.cjs --request <file> --out <file>');
  }
  let request;
  try {
    request = JSON.parse(fs.readFileSync(requestFile, 'utf8'));
  } catch (e) {
    fail('unreadable request: ' + e.message);
  }
  if (request.version !== PROTOCOL_VERSION) {
    fail('unsupported request version ' + request.version + ', expected ' + PROTOCOL_VERSION);
  }

  let response;
  switch (request.mode) {
    case 'syntax':
      response = syntaxCheck(request);
      break;
    case 'ping':
      // Used by `sdd doctor` and by the sidecar's own startup check: proves node runs, the
      // compiler loaded, and the protocol matches, without needing a repo.
      response = { version: PROTOCOL_VERSION, ok: true, tsVersion: ts.version };
      break;
    default:
      fail('unsupported mode: ' + String(request.mode));
  }
  fs.writeFileSync(outFile, JSON.stringify(response), 'utf8');
}

main();
