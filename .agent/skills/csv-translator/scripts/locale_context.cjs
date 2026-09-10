const fs = require('fs');
const path = require('path');

/**
 * Resolves and parses the `.locale` translation-context file — the answer to "what is this module
 * for?", which the CSV itself cannot carry.
 *
 * A gap CSV is a list of keys and English sentences with no indication of what the screen does,
 * who is reading it or what the app already calls a concept. That missing context is what produces
 * the quality bug this skill warns about most: a fresh word invented for a concept the app already
 * names. `.locale` is where a module states it once, next to the resources, so every later
 * translation run starts from the same terminology instead of re-deciding it.
 *
 * Resolution walks UP from the res directory, so the file can sit beside res/ or at the module or
 * repo root and still be found; the walk stops at the directory holding .git. Nearest wins, which
 * lets a feature module override the app-wide file.
 */

const FILE_NAME = '.locale';

/** Nearest `.locale` at or above `startDir`, or null. */
function resolveContextFile(startDir) {
    let dir = path.resolve(startDir);
    for (;;) {
        const candidate = path.join(dir, FILE_NAME);
        if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate;
        if (fs.existsSync(path.join(dir, '.git'))) return null;
        const parent = path.dirname(dir);
        if (parent === dir) return null;
        dir = parent;
    }
}

/** Accepts the `.locale` file itself or any directory to search upward from. */
function resolveFrom(target) {
    if (!fs.existsSync(target)) return null;
    if (fs.statSync(target).isFile()) return target;
    return resolveContextFile(target);
}

/**
 * Splits the markdown into the two machine-checkable sections plus the prose. Heading match is
 * loose on purpose — the file is written by hand, and a context file rejected over a heading
 * typo is a context file that stops being written.
 */
function parseContext(file) {
    const text = fs.readFileSync(file, 'utf8');
    // Comments are how the scaffold ships candidates that are not decisions yet — a commented
    // glossary row must stay invisible to the checks, or `init` would hand back a file whose
    // every suggestion is already being enforced.
    const active = text.replace(/<!--[\s\S]*?-->/g, '');
    const sections = new Map();
    let current = '';
    for (const line of active.split('\n')) {
        const heading = line.match(/^#{1,6}\s+(.*?)\s*$/);
        if (heading) {
            current = heading[1].toLowerCase().replace(/[^a-z]+/g, ' ').trim();
            sections.set(current, []);
        } else if (current) {
            sections.get(current).push(line);
        }
    }

    const sectionBody = keywords => {
        for (const [name, lines] of sections) {
            if (keywords.some(k => name.includes(k))) return lines;
        }
        return [];
    };

    const verbatim = sectionBody(['do not translate', 'keep verbatim', 'verbatim'])
        .map(l => l.match(/^\s*[-*]\s+(.+?)\s*$/))
        .filter(Boolean)
        .map(m => m[1].replace(/^[`"']|[`"']$/g, '').trim())
        .filter(Boolean);

    const glossary = [];
    for (const line of sectionBody(['glossary', 'terminology'])) {
        if (!line.includes('|')) continue;
        const cells = line.split('|').map(c => c.trim()).filter((c, i, a) => !(i === 0 && !c) && !(i === a.length - 1 && !c));
        if (cells.length < 3) continue;
        if (/^-{2,}$/.test(cells[0].replace(/[\s:]/g, '')) || cells[0].toLowerCase() === 'term') continue;
        const [term, locale, translation] = cells;
        if (!term || !locale || !translation) continue;
        for (const loc of locale.split(/[,\s]+/).filter(Boolean)) {
            glossary.push({ term, locale: loc, translation });
        }
    }

    return { file, text, verbatim, glossary };
}

/** null when there is no `.locale` anywhere above `target`. */
function loadContext(target) {
    const file = resolveFrom(target);
    return file ? parseContext(file) : null;
}

/** Word-boundary containment that also works when `term` starts or ends with a non-word char. */
function mentions(haystack, term) {
    if (!haystack || !term) return false;
    const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const left = /^\w/.test(term) ? '\\b' : '';
    const right = /\w$/.test(term) ? '\\b' : '';
    return new RegExp(`${left}${escaped}${right}`, 'i').test(haystack);
}

module.exports = { FILE_NAME, resolveContextFile, loadContext, parseContext, mentions };

if (require.main === module) {
    const target = process.argv[2];
    if (!target) {
        console.log(`Usage: node locale_context.cjs <res_directory | ${FILE_NAME} path>`);
        console.log(`  Prints the nearest ${FILE_NAME} at or above the given directory.`);
        console.log('  Read it BEFORE translating; exits non-zero when there is none.');
        process.exit(1);
    }
    const ctx = loadContext(target);
    if (!ctx) {
        console.error(`✗ No ${FILE_NAME} at or above ${path.resolve(target)}.`);
        console.error(`  Translating without it means re-deciding this module's terminology from scratch.`);
        console.error(`  Write one — see the ${FILE_NAME} section of SKILL.md for the sections that are checked.`);
        process.exit(1);
    }
    console.log(`# context: ${ctx.file}\n`);
    console.log(ctx.text.replace(/\n+$/, ''));
    console.log(`\n---`);
    console.log(`checked sections: ${ctx.verbatim.length} verbatim term(s), ${ctx.glossary.length} glossary entr(ies)`);
    process.exit(0);
}
