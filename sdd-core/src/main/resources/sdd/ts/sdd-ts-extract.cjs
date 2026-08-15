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

// ---------------------------------------------------------------------------- http call sites

const HTTP_VERBS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']);
const MAX_WRAPPER_DEPTH = 3;
/** Stands in for an interpolation while a URL is assembled; never appears in real path text. */
const HOLE = '\u0000';
/** Set once per extraction; the program's own source files, for implementation lookup. */
let PROGRAM_SOURCES = [];

/**
 * Whether a declaration's WRITTEN type is `typeof fetch`.
 *
 * <p>Reading the annotation rather than asking the checker whether the type is assignable to the
 * global fetch is deliberate: the program is built without lib files, so there is no global fetch
 * type to compare against, and loading the DOM lib to answer one question would cost seconds per
 * repo. `typeof fetch` is the declared contract, written in the repo's own source — this is not a
 * name heuristic, and a field merely NAMED fetchImpl with some other type does not qualify.
 */
function isFetchTyped(decl) {
  const type = decl && decl.type;
  return !!type
    && ts.isTypeQueryNode(type)
    && ts.isIdentifier(type.exprName)
    && type.exprName.text === 'fetch';
}

function declarationOf(checker, node) {
  const symbol = checker.getSymbolAtLocation(node);
  if (!symbol) return null;
  const resolved = (symbol.flags & ts.SymbolFlags.Alias) ? checker.getAliasedSymbol(symbol) : symbol;
  const decls = resolved.getDeclarations();
  return decls && decls.length ? decls[0] : null;
}

/** The global `fetch`, which no declaration in the program defines. */
function isBareGlobalFetch(checker, callee) {
  return ts.isIdentifier(callee) && callee.text === 'fetch' && !declarationOf(checker, callee);
}

function calleeExpression(call) {
  return ts.isPropertyAccessExpression(call.expression) ? call.expression.name : call.expression;
}

/** Resolves a call's callee to a value declaration, following property accesses. */
function calleeDeclaration(checker, call) {
  return declarationOf(checker, calleeExpression(call));
}

function parameterIndexOf(fn, decl) {
  if (!fn || !fn.parameters) return -1;
  for (let i = 0; i < fn.parameters.length; i++) {
    if (fn.parameters[i] === decl) return i;
  }
  return -1;
}

/**
 * Describes a function that exists only to make one outbound HTTP call on its caller's behalf:
 * which of its parameters carries the URL, and where its verb comes from.
 *
 * <p>Without this the extractor finds exactly one call site per repo — the one inside the HTTP
 * wrapper itself — and reports that the SDK calls one unknown path, which is worse than useless.
 * Every real call site in the estate reaches fetch through one or two such hops.
 *
 * <p>Returns null for anything that is not unambiguously a pass-through, which is the safe answer:
 * a caller of a function we do not understand contributes no facts at all.
 */
function wrapperInfo(checker, fn, memo, depth) {
  if (!fn || depth > MAX_WRAPPER_DEPTH) return null;
  if (memo.has(fn)) return memo.get(fn);
  memo.set(fn, null);   // cycle guard: a recursive helper resolves to "not a wrapper", not a hang

  const body = fn.body;
  if (!body) return null;

  let found = null;
  let calls = 0;
  const visit = (node) => {
    if (ts.isCallExpression(node)) {
      const outbound = describeOutbound(checker, node, memo, depth + 1);
      if (outbound) {
        calls++;
        found = outbound;
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(body);
  if (calls !== 1 || !found) return null;   // zero or several: not a pass-through

  const urlIndex = paramDrivingUrl(checker, fn, found.urlExpression);
  if (urlIndex < 0) return null;
  // A pass-through forwards its caller's URL; a domain method BUILDS one from its arguments.
  // `fetchSymbols(clientId, securityType)` embedding securityType in `/api/candles/{}/symbols` is
  // the second kind, and mistaking it for the first suppresses the very call site it makes — a
  // wrapper's internal call is skipped on the understanding that its caller was already reported.
  if (!isPassThrough(checker, found.urlExpression, fn)) return null;

  let verb = found.verb;
  let verbParam = -1;
  if (!verb && found.verbExpression && ts.isIdentifier(found.verbExpression)) {
    // For `{ method }` shorthand the identifier's own symbol is the object-literal property, not
    // the value it stands for; the checker has a dedicated lookup for that. Reading the wrong one
    // silently loses the verb and every POST is reported as fetch's GET default.
    const symbol = found.verbShorthand
      ? checker.getShorthandAssignmentValueSymbol(found.verbShorthand)
      : checker.getSymbolAtLocation(found.verbExpression);
    const decls = symbol ? symbol.getDeclarations() : null;
    verbParam = parameterIndexOf(fn, decls && decls.length ? decls[0] : null);
  }
  const info = { urlIndex, verb, verbParam,
    verbParamKind: found.verbFromSpread ? 'init' : 'verb',
    basePrefixed: found.basePrefixed };
  memo.set(fn, info);
  return info;
}

/** Which parameter of `fn` the URL expression is built from; -1 when it is not a parameter. */
/**
 * Whether the URL expression merely forwards one of {@code fn}'s parameters, optionally behind a
 * base that folds to a constant. Any literal path text of its own means the function is composing
 * a route rather than relaying one.
 */
function isPassThrough(checker, expr, fn) {
  if (!expr) return false;
  if (ts.isParenthesizedExpression(expr)) return isPassThrough(checker, expr.expression, fn);
  if (ts.isIdentifier(expr)) {
    return parameterIndexOf(fn, declarationOf(checker, expr)) >= 0;
  }
  if (ts.isBinaryExpression(expr) && expr.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    return (foldsToConstant(checker, expr.left) && isPassThrough(checker, expr.right, fn))
        || (isPassThrough(checker, expr.left, fn) && foldsToConstant(checker, expr.right));
  }
  if (ts.isTemplateExpression(expr)) {
    const spans = expr.templateSpans;
    return expr.head.text === '' && spans.length === 1 && spans[0].literal.text === ''
        && isPassThrough(checker, spans[0].expression, fn);
  }
  return false;
}

/** A prefix that resolves to a constant string — a configured base URL, typically empty. */
function foldsToConstant(checker, expr) {
  return foldConstant(checker, expr, new Set()) !== null;
}

function paramDrivingUrl(checker, fn, urlExpression) {
  let index = -1;
  let count = 0;
  const visit = (node) => {
    if (ts.isIdentifier(node)) {
      const i = parameterIndexOf(fn, declarationOf(checker, node));
      if (i >= 0) {
        count++;
        index = i;
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(urlExpression);
  return count === 1 ? index : -1;   // two parameters in one URL is not a shape we model
}

/**
 * If this call is an outbound HTTP call, what it targets. Either a direct fetch, or a call to a
 * transparent wrapper, in which case the argument at the wrapper's URL position is the URL.
 */
function describeOutbound(checker, call, memo, depth) {
  const callee = call.expression;
  const target = ts.isPropertyAccessExpression(callee) ? callee.name : callee;

  if (isBareGlobalFetch(checker, target) || isFetchTyped(calleeDeclaration(checker, call))) {
    const init = verbFromInit(call.arguments[1]);
    return {
      kind: 'TS_FETCH',
      urlExpression: call.arguments[0],
      verb: init.verb,
      verbExpression: init.expression,
      verbShorthand: init.shorthand || null,
      verbFromSpread: !!init.fromSpread,
      basePrefixed: false,
    };
  }

  const decl = calleeDeclaration(checker, call);
  if (!decl) return null;
  let fn = ts.isFunctionLike(decl) ? decl
    : (decl.initializer && ts.isFunctionLike(decl.initializer) ? decl.initializer : null);
  if (fn && !fn.body && ts.isMethodSignature(decl)) {
    // A call through a narrow interface — the estate's blotter depends on a two-method
    // `BlotterHttp` rather than on the concrete client, which is good design and leaves the callee
    // with no body to read. Fall back to the implementation, but only when the program contains
    // EXACTLY ONE wrapper matching the signature's name and arity: one candidate is a resolution,
    // several would be a guess, and a guess is not recorded.
    fn = uniqueImplementingWrapper(checker, decl, memo, depth);
  }
  if (!fn) return null;

  const info = wrapperInfo(checker, fn, memo, depth);
  if (!info) return null;
  const urlExpression = call.arguments[info.urlIndex];
  if (!urlExpression) return null;
  let verb = info.verb;
  let verbExpression = null;
  if (!verb && info.verbParam >= 0) {
    const verbArg = call.arguments[info.verbParam];
    if (info.verbParamKind === 'init') {
      // The parameter carries a whole RequestInit, not a verb string — the shape produced by a
      // helper that spreads its caller's init into fetch.
      const nested = verbFromInit(verbArg);
      verb = nested.verb;
      verbExpression = nested.expression;
    } else if (verbArg && ts.isStringLiteralLike(verbArg)) {
      verb = verbArg.text.toUpperCase();
    } else {
      verbExpression = verbArg || null;
    }
  }
  return { kind: 'TS_HTTP_WRAPPER', urlExpression, verb, verbExpression, basePrefixed: info.basePrefixed };
}

/**
 * The single method in the program that implements an interface method signature and is itself a
 * transparent wrapper. Matching is by name and parameter count against class declarations in the
 * program's own sources; ambiguity yields nothing.
 */
/**
 * Whether a method can be called with exactly {@code arity} arguments. An implementation may
 * declare more parameters than the narrow interface it satisfies — the estate's HttpClient.getJson
 * takes an optional headers argument that BlotterHttp.getJson does not — so requiring equal counts
 * loses the very calls this lookup exists to find.
 */
function callableWith(method, arity) {
  const required = method.parameters.filter(
    (p) => !p.questionToken && !p.initializer && !p.dotDotDotToken).length;
  const total = method.parameters.length;
  const variadic = method.parameters.some((p) => p.dotDotDotToken);
  return arity >= required && (variadic || arity <= total);
}

function uniqueImplementingWrapper(checker, signature, memo, depth) {
  const name = signature.name && ts.isIdentifier(signature.name) ? signature.name.text : null;
  if (!name) return null;
  const arity = signature.parameters.length;
  const candidates = [];
  for (const source of PROGRAM_SOURCES) {
    const visit = (node) => {
      if (ts.isClassDeclaration(node)) {
        for (const member of node.members) {
          if (ts.isMethodDeclaration(member) && member.name && ts.isIdentifier(member.name)
              && member.name.text === name && member.body && callableWith(member, arity)) {
            candidates.push(member);
          }
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(source);
  }
  const wrappers = candidates.filter((c) => wrapperInfo(checker, c, memo, depth) !== null);
  return wrappers.length === 1 ? wrappers[0] : null;
}

/**
 * The verb an `init` argument specifies. A fetch call with no init, or an object literal with no
 * `method`, is a GET — that is the specification's default, so it is a fact rather than a guess.
 * Anything else (a spread, a variable, a computed method) yields null, which becomes ANY.
 */
function verbFromInit(init) {
  if (!init) return { verb: 'GET', expression: null };
  if (!ts.isObjectLiteralExpression(init)) return { verb: null, expression: null };
  for (const prop of init.properties) {
    const named = prop.name && ts.isIdentifier(prop.name) && prop.name.text === 'method';
    if (named && ts.isPropertyAssignment(prop)) {
      return ts.isStringLiteralLike(prop.initializer)
        ? { verb: prop.initializer.text.toUpperCase(), expression: null }
        : { verb: null, expression: prop.initializer };
    }
    // `{ method, headers, body }` — shorthand, which is how the estate's own client passes the
    // verb down from its parameter. Reading only PropertyAssignment silently turned every POST
    // into a GET, because the absent `method` key looked like fetch's default.
    if (named && ts.isShorthandPropertyAssignment(prop)) {
      return { verb: null, expression: prop.name, shorthand: prop };
    }
  }
  // `{ ...init, signal }` — the verb is whatever the spread carries. When that is a plain
  // identifier the chain can be followed to the caller's argument; anything else is unknowable,
  // and unknowable must not be reported as fetch's GET default.
  for (const prop of init.properties) {
    if (ts.isSpreadAssignment(prop)) {
      return ts.isIdentifier(prop.expression)
        ? { verb: null, expression: prop.expression, fromSpread: true }
        : { verb: null, expression: null };
    }
  }
  return { verb: 'GET', expression: null };
}

/**
 * Reduces a URL expression to a path template, or says it cannot.
 *
 * The ladder mirrors the Java side's ValueResolver: LITERAL, CONSTANT (folded from a const
 * initialiser), TEMPLATE_PARAM (interpolations that occupy whole path segments become `{}`), and
 * DYNAMIC for everything else.
 */
function resolvePath(checker, expr, seen) {
  const raw = expr ? expr.getText() : '';
  const parts = flatten(checker, expr, seen || new Set());
  if (!parts) return { value: null, resolution: 'DYNAMIC', raw };

  let text = '';
  let sawFold = false;
  for (const part of parts) {
    if (part.literal !== undefined) {
      text += part.literal;
      if (part.folded) sawFold = true;
    } else {
      text += HOLE;
    }
  }

  // Holes are validated only AFTER the query string is dropped. A cache-buster such as
  // `?_=${Date.now()}` is not part of a route, and judging it would reject an otherwise perfectly
  // resolvable path — which is what silently lost the SDK's /config/streams.json call.
  const stripped = stripQuery(text);
  for (let i = 0; i < stripped.length; i++) {
    if (stripped[i] !== HOLE) continue;
    // A hole must fill a WHOLE path segment. `/api/v${major}/orders` must not become
    // `/api/v{}/orders`: Routes.templatesMatch compares segment by segment, so a mid-segment
    // substitution would match paths this call can never reach.
    const before = i === 0 ? null : stripped[i - 1];
    const after = i + 1 < stripped.length ? stripped[i + 1] : null;
    if (before !== '/' || (after !== null && after !== '/')) {
      return { value: null, resolution: 'DYNAMIC', raw };
    }
  }
  const sawHole = stripped.indexOf(HOLE) >= 0;
  const value = stripped.split(HOLE).join('{}');
  if (!value.startsWith('/')) {
    // Not a path that can be matched against an endpoint: an absolute URL, or something built on
    // a base that could not be read. Recorded, never matched by path.
    return { value: value || null, resolution: 'DYNAMIC', raw };
  }
  const resolution = sawHole ? 'TEMPLATE_PARAM' : (sawFold ? 'CONSTANT' : 'LITERAL');
  return { value, resolution, raw };
}

/** The query string is not part of a route; it is stripped here so Routes never has to know. */
function stripQuery(text) {
  const q = text.indexOf('?');
  return q < 0 ? text : text.substring(0, q);
}

/**
 * Flattens a URL expression into literal chunks and holes. Handles string literals, template
 * literals, `a + b` concatenation (the SDK builds `this.baseUrl + path` that way), and identifiers
 * that fold to a const initialiser. Returns null when the shape is not one of those.
 */
function flatten(checker, expr, seen) {
  if (!expr) return null;
  if (ts.isStringLiteralLike(expr)) {
    return [{ literal: expr.text }];
  }
  if (ts.isNoSubstitutionTemplateLiteral(expr)) {
    return [{ literal: expr.text }];
  }
  if (ts.isTemplateExpression(expr)) {
    const parts = [{ literal: expr.head.text }];
    for (const span of expr.templateSpans) {
      const inner = foldConstant(checker, span.expression, seen);
      if (inner !== null) {
        parts.push({ literal: inner, folded: true });
      } else {
        parts.push({ hole: true });
      }
      parts.push({ literal: span.literal.text });
    }
    return parts;
  }
  if (ts.isBinaryExpression(expr) && expr.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    const left = flatten(checker, expr.left, seen);
    const right = flatten(checker, expr.right, seen);
    if (!left || !right) return null;
    return left.concat(right);
  }
  if (ts.isParenthesizedExpression(expr)) {
    return flatten(checker, expr.expression, seen);
  }
  const folded = foldConstant(checker, expr, seen);
  if (folded !== null) {
    return [{ literal: folded, folded: true }];
  }
  // A local const holding a template that itself contains interpolations — how the estate builds
  // its candles URL. Its parts are spliced in rather than folded, so the holes survive to be
  // judged as path segments; requiring the whole const to be literal loses the path entirely.
  return inlineConstParts(checker, expr, seen);
}

function inlineConstParts(checker, expr, seen) {
  if (!ts.isIdentifier(expr)) return null;
  const decl = declarationOf(checker, expr);
  if (!decl || seen.has(decl) || !ts.isVariableDeclaration(decl) || !decl.initializer) return null;
  seen.add(decl);
  const parts = flatten(checker, decl.initializer, seen);
  seen.delete(decl);
  return parts;
}

/**
 * A value that is statically a string: a const initialised to one, transitively. The visited set is
 * path-scoped so a self-referential declaration cannot loop.
 */
function foldConstant(checker, expr, seen) {
  if (!expr) return null;
  if (ts.isStringLiteralLike(expr) || ts.isNoSubstitutionTemplateLiteral(expr)) {
    return expr.text;
  }
  if (!ts.isIdentifier(expr) && !ts.isPropertyAccessExpression(expr)) {
    return null;
  }
  const target = ts.isPropertyAccessExpression(expr) ? expr.name : expr;
  const decl = declarationOf(checker, target);
  if (!decl || seen.has(decl)) return null;

  // A property initialised to `options.x ?? ''` is empty by default — the SDK's baseUrl shape.
  // That is a real default, written in source, not an assumption about deployment.
  const init = decl.initializer;
  if (!init) {
    const assigned = defaultAssignmentInConstructor(decl);
    if (assigned === null) return null;
    seen.add(decl);
    const folded = flatten(checker, assigned, seen);
    seen.delete(decl);
    return folded && folded.every((p) => p.literal !== undefined)
      ? folded.map((p) => p.literal).join('') : null;
  }
  if (!ts.isVariableDeclaration(decl) && !ts.isPropertyDeclaration(decl)) return null;
  seen.add(decl);
  const parts = flatten(checker, init, seen);
  seen.delete(decl);
  if (!parts || !parts.every((p) => p.literal !== undefined)) return null;
  return parts.map((p) => p.literal).join('');
}

/** `this.baseUrl = options.baseUrl ?? ''` — reports the `''`, and nothing else. */
function defaultAssignmentInConstructor(decl) {
  if (!ts.isPropertyDeclaration(decl) || !decl.parent || !decl.parent.members) return null;
  const name = decl.name && ts.isIdentifier(decl.name) ? decl.name.text : null;
  if (!name) return null;
  for (const member of decl.parent.members) {
    if (!ts.isConstructorDeclaration(member) || !member.body) continue;
    let result = null;
    const visit = (node) => {
      if (ts.isBinaryExpression(node)
          && node.operatorToken.kind === ts.SyntaxKind.EqualsToken
          && ts.isPropertyAccessExpression(node.left)
          && node.left.expression.kind === ts.SyntaxKind.ThisKeyword
          && node.left.name.text === name
          && ts.isBinaryExpression(node.right)
          && node.right.operatorToken.kind === ts.SyntaxKind.QuestionQuestionToken) {
        result = node.right.right;
      }
      ts.forEachChild(node, visit);
    };
    visit(member.body);
    return result;
  }
  return null;
}

function insideTransparentWrapper(checker, node, memo) {
  for (let n = node.parent; n; n = n.parent) {
    if (ts.isFunctionLike(n) && wrapperInfo(checker, n, memo, 0) !== null) {
      return true;
    }
  }
  return false;
}

/** The nearest named function/method around a node, for `method_or_site`. */
function enclosingSite(node) {
  for (let n = node.parent; n; n = n.parent) {
    if (ts.isFunctionDeclaration(n) || ts.isMethodDeclaration(n) || ts.isConstructorDeclaration(n)) {
      return ts.isConstructorDeclaration(n) ? '<init>'
        : (n.name && ts.isIdentifier(n.name) ? n.name.text : '<anonymous>');
    }
    if ((ts.isFunctionExpression(n) || ts.isArrowFunction(n))
        && ts.isVariableDeclaration(n.parent) && ts.isIdentifier(n.parent.name)) {
      return n.parent.name.text;
    }
  }
  return '<module>';
}

/** The nearest enclosing class, appended to the file path so a site names something a human can find. */
function enclosingContainer(node) {
  for (let n = node.parent; n; n = n.parent) {
    if (ts.isClassDeclaration(n) && n.name) {
      return n.name.text;
    }
  }
  return null;
}

function extractHttpCallSites(request) {
  const repoRoot = request.repoRoot;
  const files = request.files || [];
  const program = buildProgram(files, request.compilerOptions || {});
  const checker = program.getTypeChecker();
  const memo = new Map();
  const sites = [];
  PROGRAM_SOURCES = files.map((f) => program.getSourceFile(f)).filter(Boolean);

  for (const file of files) {
    const source = program.getSourceFile(file);
    if (!source) continue;
    const visit = (node) => {
      if (ts.isCallExpression(node)) {
        const outbound = describeOutbound(checker, node, memo, 0);
        // A wrapper's own internal call is an implementation detail, not an estate fact: the
        // caller it exists for is already reported, and reporting both would claim the SDK calls
        // an unknown path in addition to the real one it calls.
        if (outbound && !insideTransparentWrapper(checker, node, memo)) {
          const path = resolvePath(checker, outbound.urlExpression, new Set());
          const container = enclosingContainer(node);
          const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
          sites.push({
            file: relPath(repoRoot, file),
            line,
            container: relPath(repoRoot, file) + (container ? '#' + container : ''),
            site: enclosingSite(node),
            kind: outbound.kind,
            verb: outbound.verb && HTTP_VERBS.has(outbound.verb) ? outbound.verb : 'ANY',
            pathValue: path.value,
            resolution: path.resolution,
            raw: path.raw,
          });
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(source);
  }
  return { version: PROTOCOL_VERSION, sites, issues: [] };
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
    case 'httpCallSites':
      response = extractHttpCallSites(request);
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
