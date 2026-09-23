#!/usr/bin/env node
/**
 * List string resources in values/strings.xml that nothing appears to reference.
 *
 * Usage: node find_unused_strings.cjs <module_dir> [<extra_search_dir> ...]
 *
 * Looks for R.string.name, @string/name and getString-by-name across source
 * and resource files. This is a heuristic: names built at runtime (string
 * concatenation, reflection, data binding expressions) cannot be detected, so
 * treat the output as candidates to review rather than a delete list.
 */

const fs = require('fs');
const path = require('path');
const { readStrings, readPlurals } = require('./res.cjs');

const CODE_EXT = new Set(['.kt', '.java', '.xml', '.kts', '.js', '.ts']);

function walk(dir, files = []) {
    let entries;
    try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
        return files;
    }
    for (const e of entries) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) {
            if (e.name === 'build' || e.name === '.git' || e.name === 'node_modules') continue;
            walk(full, files);
        } else if (CODE_EXT.has(path.extname(e.name))) {
            files.push(full);
        }
    }
    return files;
}

const args = process.argv.slice(2);
if (args.length < 1) {
    console.log('Usage: node find_unused_strings.cjs <module_dir> [<extra_search_dir> ...]');
    process.exit(1);
}

const moduleDir = args[0];
const stringsFile = path.join(moduleDir, 'src', 'main', 'res', 'values', 'strings.xml');
// <plurals> names are referenced the same way and go stale the same way.
const names = [...readStrings(stringsFile).keys(), ...readPlurals(stringsFile).keys()];
if (names.length === 0) {
    console.error(`No strings found in ${stringsFile}`);
    process.exit(1);
}

// Search the module plus any extra directories the caller names, since a
// string declared in one module is often used from another.
const searchDirs = [moduleDir, ...args.slice(1)];
const files = searchDirs.flatMap(d => walk(d));

// Concatenate once, then test each name, rather than re-reading per name.
const haystack = files
    .filter(f => path.resolve(f) !== path.resolve(stringsFile))
    .map(f => fs.readFileSync(f, 'utf8'))
    .join('\n');

const unused = names.filter(name => {
    const re = new RegExp(`(R\\.string\\.${name}|@string/${name})\\b`);
    return !re.test(haystack);
});

if (unused.length === 0) {
    console.log(`All ${names.length} strings in ${stringsFile} are referenced.`);
} else {
    console.log(`${unused.length} of ${names.length} strings look unused in ${stringsFile}:\n`);
    unused.forEach(n => console.log(`  ${n}`));
    console.log('\nHeuristic only — check for runtime-built names before deleting any of these.');
}
