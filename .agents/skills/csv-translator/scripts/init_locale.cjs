#!/usr/bin/env node
/**
 * Scaffold a `.locale` for a module, pre-filled with everything derivable from its resources.
 *
 * Usage: node init_locale.cjs <res_dir> [--file <resource_file>] [--force]
 *
 * A blank template does not get written — that is why this emits the locale list, the brand-name
 * candidates and the frequent terms already found in values/<file>, so the only thing left is the
 * part a script cannot know: what the module is for and how it should sound. The prose sections
 * ship as TODO markers so an unfinished file is visible rather than merely empty.
 */

const fs = require('fs');
const path = require('path');
const { FILE_NAME, resolveContextFile } = require('./locale_context.cjs');
const { localeOfDir, readStrings, readPlurals } = require('./res.cjs');

const STOPWORDS = new Set(('a an and are as at be by can do does for from get go has have if in into is it its ' +
    'let me my no not of on or our out per so than that the then there they this to try up us use want was we ' +
    'what when where which who will with you your yours all any more most new now off one only other some ' +
    'just also back down over again very such each both few own same too here how why while about after ' +
    'before between during under above below through against because been being had were would could should ' +
    'may might must shall am did doing done').split(' '));

/** Common words that are legitimately capitalised mid-sentence and are NOT brand names. */
const NOT_BRANDS = new Set(['I', 'OK', 'AM', 'PM', 'Android', 'iOS', 'English',
    'APK', 'ADB', 'TV', 'OS', 'URL', 'ID', 'SDK', 'XAPK', 'APKS']);

/** Locale codes whose directory name does not say what the file must actually contain. */
const LOCALE_NOTES = {
    'pt': 'European or Brazilian? Grep for telemovel/autocarro vs celular/onibus and match what is there',
    'pt-BR': 'Brazilian Portuguese',
    'in': "Android's legacy code for Indonesian — never write `id` in the CSV",
    'iw': "Android's legacy code for Hebrew — never write `he`",
    'fil': 'CLDR requires `one` for plurals here; Lint has no rule for it, so only audit_plurals.cjs catches the gap',
    'zh': 'Simplified or Traditional? Check the existing file',
    'ar': 'RTL, and CLDR wants all six plural quantities',
    'el': 'Greek — check the existing file for the register already used',
};

/**
 * Maximal RUNS of capitalised tokens that start somewhere other than the first word of a string —
 * the shape a product or feature name has. Runs, not single words, because "microG Companion" is
 * one name: split into "microG" and "Companion" it would demand each half survive on its own,
 * which no translation of the phrase does. Sentence-initial capitals are skipped; every string
 * has one.
 */
function brandCandidates(strings) {
    const runCounts = new Map();
    const tokenCounts = new Map();
    const standalone = new Set();

    for (const value of strings) {
        const text = value.replace(/%(\d+\$)?[sdfx%]/g, ' ');
        // Tokens keep their hyphen ("Wi-Fi" is one name), and the GAP between two tokens is kept
        // too: only a single space joins words into one name. Splitting on every non-letter would
        // merge "Settings > Advanced > API Key" into one bogus five-word candidate.
        const tokens = [...text.matchAll(/[A-Za-z0-9']+(?:-[A-Za-z0-9']+)*/g)]
            .map(m => ({ word: m[0], start: m.index, end: m.index + m[0].length }));

        let run = [];
        let startedAtFirstWord = false;
        const flush = () => {
            if (run.length && !startedAtFirstWord) {
                const phrase = run.join(' ');
                runCounts.set(phrase, (runCounts.get(phrase) || 0) + 1);
                if (run.length === 1) standalone.add(phrase);
                for (const w of run) tokenCounts.set(w, (tokenCounts.get(w) || 0) + 1);
            }
            run = [];
        };

        tokens.forEach((t, i) => {
            const isName = /^[A-Z]/.test(t.word) && !NOT_BRANDS.has(t.word)
                && !STOPWORDS.has(t.word.toLowerCase());
            const joinedToPrevious = i > 0 && text.slice(tokens[i - 1].end, t.start) === ' ';
            if (!isName || (run.length && !joinedToPrevious)) flush();
            if (isName) {
                if (!run.length) startedAtFirstWord = i === 0;
                run.push(t.word);
            }
        });
        flush();
    }

    // A one-off capital is usually a proper noun inside prose; a repeat is a real name.
    const runs = [...runCounts].filter(([, n]) => n >= 2).sort((a, b) => b[1] - a[1]);

    // A token seen on its own somewhere still needs its own entry, even when a longer run covers
    // it elsewhere: "microG Companion" being protected says nothing about a bare "microG".
    const singles = [...tokenCounts]
        .filter(([w, n]) => n >= 2 && standalone.has(w) && !runs.some(([p]) => p === w))
        .sort((a, b) => b[1] - a[1]);

    return [...runs, ...singles].map(([term, n]) => ({ term, n })).slice(0, 40);
}

/** Frequent lowercase content words — the concepts worth agreeing on once. */
function glossaryCandidates(strings) {
    const counts = new Map();
    for (const value of strings) {
        const words = value.toLowerCase().replace(/%(\d+\$)?[sdfx%]/g, ' ').split(/[^a-z']+/).filter(Boolean);
        for (const w of new Set(words)) {
            if (w.length < 4 || STOPWORDS.has(w)) continue;
            counts.set(w, (counts.get(w) || 0) + 1);
        }
    }
    return [...counts].filter(([, n]) => n >= 3).sort((a, b) => b[1] - a[1]).slice(0, 15)
        .map(([w, n]) => ({ term: w, n }));
}

/** `.locale` belongs at the module root (the parent of src/main/res), else beside res/. */
function targetDir(resDir) {
    const abs = path.resolve(resDir);
    const parts = abs.split(path.sep);
    const i = parts.lastIndexOf('src');
    if (i > 0 && parts[i + 1] === 'main') return parts.slice(0, i).join(path.sep);
    return path.dirname(abs);
}

function init(resDir, resFile, force) {
    if (!fs.existsSync(resDir) || !fs.statSync(resDir).isDirectory()) {
        console.error(`Not a directory: ${resDir}`);
        console.error('Pass the res/ directory, e.g. app/src/main/res.');
        return 1;
    }
    const defaultFile = path.join(resDir, 'values', resFile);
    if (!fs.existsSync(defaultFile)) {
        console.error(`Missing default resource file: ${defaultFile}`);
        return 1;
    }

    const outDir = targetDir(resDir);
    const outPath = path.join(outDir, FILE_NAME);
    if (fs.existsSync(outPath) && !force) {
        console.error(`✗ ${outPath} already exists.`);
        console.error('  Edit it, or pass --force to overwrite (you lose the prose you wrote).');
        return 1;
    }
    const inherited = resolveContextFile(outDir);
    if (inherited && inherited !== outPath) {
        console.error(`⚠ ${inherited} already covers this module (resolution takes the nearest).`);
        console.error(`  Writing ${outPath} will override it for ${resDir}, not extend it.\n`);
    }

    const all = readStrings(defaultFile);
    const translatable = [...all].filter(([, s]) => s.translatable);
    const excludedCount = all.size - translatable.length;
    const values = translatable.map(([, s]) => s.value);
    const plurals = [...readPlurals(defaultFile).keys()];
    const highlights = translatable.filter(([name]) => /_highlight$/.test(name)).map(([name]) => name);

    const locales = fs.readdirSync(resDir).sort().map(localeOfDir).filter(Boolean);
    const brands = brandCandidates(values);
    const terms = glossaryCandidates(values);

    const L = [];
    L.push('# What this module is');
    L.push('');
    L.push('TODO — what these screens do, who is on them, and what the user is trying to finish.');
    L.push('Two or three sentences. A translator who reads only the CSV cannot guess any of it.');
    L.push('Note anything that inverts the obvious reading of a string.');
    L.push('');
    L.push(`Derived: ${translatable.length} translatable string(s)` +
        (excludedCount ? ` (+${excludedCount} translatable="false")` : '') +
        (plurals.length ? `, ${plurals.length} <plurals> block(s)` : '') +
        ` across ${locales.length} locale(s), in \`${resFile}\`.`);
    L.push('');
    L.push('## Audience & tone');
    L.push('');
    L.push('TODO — register (formal / familiar), person, and how long a line may be.');
    L.push('Name the pronoun each language should use; that is the decision that drifts fastest.');
    L.push('');
    L.push('## Do not translate');
    L.push('');
    if (brands.length) {
        L.push('<!-- Candidates found capitalised mid-sentence. DELETE the ones that are ordinary words:');
        L.push('     everything left here becomes a hard error if a translation drops it. -->');
        for (const b of brands) L.push(`- ${b.term}${b.n > 2 ? `  <!-- ${b.n}x -->` : ''}`);
    } else {
        L.push('<!-- Brand and product names, verbatim in every locale. Nothing was auto-detected. -->');
        L.push('- TODO');
    }
    L.push('');
    L.push('## Glossary');
    L.push('');
    L.push('| term | locale | translation |');
    L.push('| --- | --- | --- |');
    if (terms.length) {
        L.push('<!-- Frequent words in the source, one row per locale. Uncomment and fill the');
        L.push('     translation you actually used, delete the rest. A commented row is not checked. -->');
        for (const t of terms) L.push(`<!-- | ${t.term} | ? | ? |  ${t.n}x -->`);
    } else {
        L.push('<!-- | session | vi | phien | -->');
    }
    L.push('');
    L.push('## Notes per locale');
    L.push('');
    if (locales.length) {
        for (const loc of locales) {
            const note = LOCALE_NOTES[loc];
            L.push(`- \`${loc}\`${note ? ` — ${note}` : ' — TODO'}`);
        }
    } else {
        L.push('- TODO — no values-<locale>/ directories yet.');
    }
    if (highlights.length) {
        L.push('');
        L.push('## Coupled strings');
        L.push('');
        L.push('<!-- A _highlight value must be a literal substring of its message, or the bolding');
        L.push('     falls through to plain text with no crash and no log. Translate the message');
        L.push('     first, then lift the highlight out of that sentence word for word. -->');
        for (const h of highlights) L.push(`- \`${h}\``);
    }
    L.push('');

    fs.writeFileSync(outPath, L.join('\n'));
    console.log(`✓ Wrote ${outPath}`);
    console.log(`  ${translatable.length} translatable string(s), ${locales.length} locale(s)` +
        (plurals.length ? `, ${plurals.length} plural block(s)` : ''));
    if (brands.length) console.log(`  ${brands.length} do-not-translate candidate(s) — prune them, they become errors`);
    if (terms.length) console.log(`  ${terms.length} glossary candidate(s), commented out — uncomment what you agree on`);
    if (highlights.length) console.log(`  ${highlights.length} _highlight key(s) listed under "Coupled strings"`);
    console.log('');
    console.log('  Now fill the TODO sections — a script cannot know what the module is for.');
    console.log(`  Then: node scripts/locale_context.cjs ${resDir}`);
    return 0;
}

const args = process.argv.slice(2);
const resDir = args[0];
const fileIdx = args.indexOf('--file');
if (!resDir || resDir.startsWith('--')) {
    console.log('Usage: node init_locale.cjs <res_dir> [--file <resource_file>] [--force]');
    console.log(`  Scaffolds ${FILE_NAME} at the module root, pre-filled from values/<file>:`);
    console.log('  locale list, brand-name candidates, frequent terms, _highlight couplings.');
    console.log('  Refuses to overwrite an existing file without --force.');
    process.exit(1);
}
process.exit(init(resDir, fileIdx !== -1 ? args[fileIdx + 1] : 'strings.xml', args.includes('--force')));
