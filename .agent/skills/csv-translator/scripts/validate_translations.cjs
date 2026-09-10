#!/usr/bin/env node
/**
 * Check a translated CSV for everything that would import wrong, BEFORE anything is written.
 *
 * Usage: node validate_translations.cjs <translated_csv> [source_csv] [--context <dir|.locale>]
 *                                       [--strict-glossary]
 *
 * Parsing and placeholder extraction go through the same csv.cjs / res.cjs the importer uses, so a
 * CSV that passes here is a CSV the importer reads identically. Validating with different code
 * would pass rows the importer then mangles, which is the whole failure this script exists to
 * catch.
 */

const fs = require('fs');
const { parseCsv } = require('./csv.cjs');
const { placeholders, androidLocale } = require('./res.cjs');
const { ORDER } = require('./plural_rules.cjs');
const { FILE_NAME, loadContext, mentions } = require('./locale_context.cjs');

/** Records that keep their raw cell count, so a shifted row is still visible. */
function readCsv(p) {
    if (!fs.existsSync(p)) {
        console.error(`No such file: ${p}`);
        console.error(`  (paths are relative to your cwd: ${process.cwd()})`);
        process.exit(1);
    }
    const rows = parseCsv(fs.readFileSync(p, 'utf8'))
        .filter(r => !(r.length === 1 && r[0].trim() === ''));
    if (!rows.length) return { header: [], records: [] };
    const header = rows[0].map(h => h.trim());
    const records = rows.slice(1).map((cells, i) => {
        const rec = { __line: i + 2, __cols: cells.length };
        header.forEach((name, col) => { rec[name] = cells[col] ?? ''; });
        return rec;
    });
    return { header, records };
}

/** Blank out every occurrence of `term` so a glossary term nested inside it stops matching. */
function redact(text, term) {
    if (!text || !term) return text || '';
    const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return text.replace(new RegExp(escaped, 'gi'), ' ');
}

function validate(translatedPath, sourcePath, contextTarget, strictGlossary) {
    const { header, records } = readCsv(translatedPath);
    const problems = [];
    const warnings = [];

    let context = null;
    if (contextTarget) {
        context = loadContext(contextTarget);
        if (!context) {
            console.error(`✗ No ${FILE_NAME} at or above ${contextTarget}.`);
            console.error('  Run init_locale.cjs to scaffold one, or drop --context.');
            process.exit(1);
        }
    }

    const missingColumns = ['locale', 'name', 'translated_value'].filter(c => !header.includes(c));
    if (missingColumns.length) {
        missingColumns.forEach(c => console.error(`  ✗ header: missing required column "${c}"`));
        console.error('\nimport_translations.cjs needs locale, name and translated_value.');
        console.error(`  found: ${header.join(', ')}`);
        process.exit(1);
    }

    const seen = new Map();
    const pluralQuantities = new Map();  // locale + NUL + name -> Set<quantity>
    const plainNames = new Set();

    for (const rec of records) {
        const locale = (rec.locale || '').trim();
        const name = (rec.name || '').trim();
        const quantity = (rec.quantity || '').trim();
        const value = rec.translated_value ?? '';
        const where = `line ${rec.__line} [${locale}/${name}${quantity ? `:${quantity}` : ''}]`;

        if (rec.__cols !== header.length) {
            // Nearly always an unquoted comma inside a translation: every column after it shifts,
            // and the importer writes the wrong text into strings.xml.
            problems.push(`${where}: ${rec.__cols} columns, expected ${header.length} — quote any value containing a comma`);
        }
        if (!locale || !name) {
            problems.push(`${where}: missing locale or name`);
        }
        if (!value.trim()) {
            problems.push(`${where}: empty translation`);
        }
        if (value.includes('\\')) {
            problems.push(`${where}: contains a backslash — provide raw text, the importer escapes for Android`);
        }
        if (locale && androidLocale(locale) !== locale.replace(/_/g, '-')) {
            // The importer maps it, but a CSV that says `id` or `pt_BR` hides which directory the
            // strings actually land in, and the next hand-written chunk copies the wrong code.
            warnings.push(`${where}: locale "${locale}" will be written as values-${androidLocale(locale)}`);
        }

        const nameKey = `${locale}\u0000${name}`;
        // Plural items legitimately share a name, so uniqueness must key on the quantity too.
        const key = quantity ? `${nameKey}:${quantity}` : nameKey;
        if (seen.has(key)) {
            problems.push(`${where}: duplicate of line ${seen.get(key)}`);
        }
        seen.set(key, rec.__line);

        if (quantity) {
            if (!ORDER.includes(quantity)) {
                problems.push(`${where}: quantity "${quantity}" is not one of ${ORDER.join(', ')}`);
            }
            if (!pluralQuantities.has(nameKey)) pluralQuantities.set(nameKey, new Set());
            pluralQuantities.get(nameKey).add(quantity);
        } else {
            plainNames.add(nameKey);
        }

        if (rec.default_value !== undefined) {
            const want = placeholders(rec.default_value);
            const got = placeholders(value);
            if (quantity) {
                // A plural form may legitimately DROP the placeholder — Arabic "two" is a dual
                // form with the count inside the word, so demanding %d back would render "2 ...".
                // Adding or renumbering one is still a bug.
                const extra = got.filter(s => !want.includes(s));
                if (extra.length) {
                    problems.push(`${where}: placeholders ${JSON.stringify(extra)} are not in the source ${JSON.stringify(want)}`);
                }
            } else if (want.join() !== got.join()) {
                problems.push(`${where}: placeholders ${JSON.stringify(want)} became ${JSON.stringify(got)}`);
            }
        }

        if (context && rec.default_value !== undefined) {
            for (const term of context.verbatim) {
                // A brand or product name has no translated form, so exact containment holds in
                // every locale — unlike a glossary term, which inflects.
                if (mentions(rec.default_value, term) && !value.includes(term)) {
                    problems.push(`${where}: "${term}" is marked do-not-translate but is not in the translation`);
                }
            }
            // A glossary term can sit INSIDE a do-not-translate one ("Store" in "Google Play
            // Store"). Matching against the raw source would then demand the agreed word inside a
            // phrase that is supposed to stay verbatim, so mask the verbatim spans out first.
            const searchable = context.verbatim.reduce(redact, rec.default_value);
            for (const entry of context.glossary) {
                if (entry.locale !== locale) continue;
                if (!mentions(searchable, entry.term)) continue;
                // Case-insensitive: a sentence-initial capital is not a terminology miss. The
                // do-not-translate check above stays exact — a brand name has one casing.
                if (value.toLocaleLowerCase().includes(entry.translation.toLocaleLowerCase())) continue;
                // A warning by default: Arabic and Russian inflect the glossary form, so the
                // agreed word can be genuinely present without matching character for character.
                const msg = `${where}: glossary says "${entry.term}" -> "${entry.translation}" in ${entry.locale}, not found in the translation`;
                (strictGlossary ? problems : warnings).push(msg);
            }
        }
    }

    if (sourcePath) {
        const src = readCsv(sourcePath).records;
        if (src.length !== records.length) {
            problems.push(`row count: source has ${src.length}, translated has ${records.length}`);
        }
        const n = Math.min(src.length, records.length);
        for (let i = 0; i < n; i++) {
            const a = src[i];
            const b = records[i];
            if (a.locale !== b.locale || a.name !== b.name || (a.quantity || '') !== (b.quantity || '')) {
                problems.push(`line ${b.__line}: expected ${a.locale}/${a.name}, found ${b.locale}/${b.name}`);
            } else if (a.default_value !== undefined && b.default_value !== undefined
                && a.default_value !== b.default_value) {
                problems.push(`line ${b.__line} [${b.locale}/${b.name}]: default_value was modified`);
            }
        }
    }

    for (const nameKey of pluralQuantities.keys()) {
        if (!plainNames.has(nameKey)) continue;
        const [locale, name] = nameKey.split("\u0000");
        // One resource name cannot be both <string> and <plurals>; the importer would write both
        // and aapt would fail on the duplicate.
        problems.push(`${locale}/${name}: has rows both with and without a quantity`);
    }

    if (warnings.length) {
        console.error(`⚠ ${warnings.length} warning(s) — check these, then import:\n`);
        warnings.forEach(w => console.error(`  ${w}`));
        console.error('');
    }
    if (problems.length) {
        console.error(`✗ ${problems.length} problem(s) in ${translatedPath}:\n`);
        problems.forEach(p => console.error(`  ${p}`));
        process.exit(1);
    }

    const locales = new Set(records.map(r => (r.locale || '').trim()));
    const plurals = [...pluralQuantities.keys()].length;
    console.log(`✓ ${records.length} rows, ${locales.size} locales` +
        (plurals ? `, ${plurals} plural block(s)` : '') + ', no problems.');
    console.log(`  locales: ${[...locales].join(', ')}`);
    if (context) {
        console.log(`  context: ${context.file} (${context.verbatim.length} verbatim, ${context.glossary.length} glossary)`);
    } else {
        console.log(`  no --context given: the ${FILE_NAME} do-not-translate list and glossary were not checked`);
    }
}

const args = process.argv.slice(2);
const ctxIdx = args.indexOf('--context');
const positional = args.filter((a, i) => !a.startsWith('--') && !(ctxIdx !== -1 && i === ctxIdx + 1));
if (positional.length < 1) {
    console.log('Usage: node validate_translations.cjs <translated_csv> [source_csv] [--context <dir|.locale>] [--strict-glossary]');
    console.log('  Run before import_translations.cjs. Exits non-zero if anything would import wrong.');
    console.log('  --context also checks the do-not-translate list (error) and glossary (warning).');
    console.log('  --strict-glossary promotes glossary misses to errors.');
    process.exit(1);
}
validate(positional[0], positional[1], ctxIdx !== -1 ? args[ctxIdx + 1] : null, args.includes('--strict-glossary'));
