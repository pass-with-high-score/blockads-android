#!/usr/bin/env node
/**
 * Report every <plurals> block that is missing quantity keywords its locale requires — the
 * MissingQuantity error Android Lint raises, but runnable in a second and able to emit the gaps
 * straight back out as a translation CSV.
 *
 * Usage: node audit_plurals.cjs <res_dir> [--csv out.csv] [--file <resource_file>] [--locales de,fr]
 *
 * Run it BEFORE translating (to generate the work) and AFTER importing (to confirm the gap closed).
 *
 * Unlike Lint, it also catches a block that is absent from a locale ENTIRELY. Auditing only the
 * blocks a locale already declares reports a clean run for a locale that translated no plurals at
 * all, which is the one failure mode here that looks like success.
 */

const fs = require('fs');
const path = require('path');
const { toCsv } = require('./csv.cjs');
const { localeDirs, readPlurals } = require('./res.cjs');
const { FILE_NAME, resolveContextFile } = require('./locale_context.cjs');
const { requiredQuantities, sortQuantities, languageOf } = require('./plural_rules.cjs');

/** Best source text a translator can be given for a quantity: the default locale's own wording. */
function sourceText(defaultItems, localeItems) {
    for (const items of [defaultItems, localeItems]) {
        if (!items) continue;
        for (const q of ['other', 'many', 'one']) {
            if (items.has(q)) return items.get(q);
        }
    }
    return '';
}

function audit(resDir, csvOut, resFile, onlyLocales) {
    if (!fs.existsSync(resDir) || !fs.statSync(resDir).isDirectory()) {
        console.error(`Not a directory: ${resDir}`);
        console.error('Pass the res/ directory, e.g. app/src/main/res (paths are relative to your cwd).');
        return 1;
    }
    const defaultFile = path.join(resDir, 'values', resFile);
    const defaults = readPlurals(defaultFile);

    const gaps = [];
    const unknown = new Set();
    let checkedLocales = 0;
    let checkedBlocks = 0;

    for (const entry of localeDirs(resDir, resFile)) {
        const { locale, file } = entry;
        if (onlyLocales && !onlyLocales.includes(locale)) continue;

        const required = requiredQuantities(locale);
        if (!required) {
            unknown.add(`${locale} (${languageOf(locale)})`);
            continue;
        }
        checkedLocales++;

        const translated = readPlurals(file);
        // Union of the default file's blocks and this locale's own, so neither a block the locale
        // never started nor a locale-only block escapes the audit.
        const names = new Set([...defaults.keys(), ...translated.keys()]);

        for (const name of names) {
            checkedBlocks++;
            const items = translated.get(name);
            const missing = required.filter(q => !items?.has(q));
            if (!missing.length) continue;
            gaps.push({
                locale,
                name,
                missing: sortQuantities(missing),
                absent: !items,
                source: sourceText(defaults.get(name), items),
            });
        }
    }

    if (unknown.size) {
        console.log(`! ${unknown.size} locale(s) skipped — language not in plural_rules.cjs: ${[...unknown].join(', ')}`);
        console.log('  Add them to the RULES table if you need them audited.\n');
    }

    if (!gaps.length) {
        console.log(`✓ ${checkedBlocks} <plurals> block(s) across ${checkedLocales} locale(s) — all required quantities present.`);
        return 0;
    }

    const absent = gaps.filter(g => g.absent).length;
    console.error(`✗ ${gaps.length} incomplete <plurals> block(s)` +
        (absent ? `, ${absent} of them not declared in the locale at all` : '') + ':\n');
    for (const g of gaps) {
        console.error(`  ${g.locale}/${g.name}: missing ${g.missing.join(', ')}` +
            `${g.absent ? ' (whole block absent)' : ''} (required: ${requiredQuantities(g.locale).join(', ')})`);
    }

    if (!csvOut) {
        console.error('\nRe-run with --csv <path> to emit these gaps as a translation CSV.');
        return 1;
    }

    const rows = [];
    for (const g of gaps) {
        for (const quantity of g.missing) {
            rows.push({ locale: g.locale, name: g.name, quantity, default_value: g.source });
        }
    }
    fs.writeFileSync(csvOut, toCsv(['locale', 'name', 'quantity', 'default_value'], rows));
    console.error(`\nWrote ${rows.length} row(s) to ${csvOut} — add a translated_value column, then import.`);

    const ctxFile = resolveContextFile(resDir);
    if (ctxFile) {
        console.error(`Context: ${ctxFile} — read it before translating.`);
    } else {
        // A gap CSV alone says nothing about what the module is for or what the app already calls
        // a concept, which is where invented terminology comes from.
        console.error(`No ${FILE_NAME} at or above ${resDir} — consider running init_locale.cjs so this`);
        console.error(`module's terminology is decided once instead of per translation run.`);
    }
    return 1;
}

const args = process.argv.slice(2);
const positional = [];
let csvOut = null;
let resFile = 'strings.xml';
let onlyLocales = null;

for (let i = 0; i < args.length; i++) {
    if (args[i] === '--csv') csvOut = args[++i];
    else if (args[i] === '--file') resFile = args[++i];
    else if (args[i] === '--locales') onlyLocales = args[++i].split(',').map(s => s.trim()).filter(Boolean);
    else positional.push(args[i]);
}

if (positional.length < 1) {
    console.log('Usage: node audit_plurals.cjs <res_dir> [--csv out.csv] [--file <resource_file>] [--locales de,fr]');
    console.log('  Lists <plurals> blocks missing quantities their locale requires (CLDR).');
    console.log('  Exits non-zero when any gap is found.');
    process.exit(1);
}
process.exit(audit(positional[0], csvOut, resFile, onlyLocales));
