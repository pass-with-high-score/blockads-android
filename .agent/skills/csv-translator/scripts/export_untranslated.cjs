#!/usr/bin/env node
/**
 * Export strings that exist in values/<file> but are missing from one or more
 * values-<locale>/<file> files.
 *
 * Usage:
 *   node export_untranslated.cjs <res_dir> [-o out.csv] [--locales de,fr,ja] [--file <resource_file>]
 *
 * Writes locale,name,default_value rows — exactly the shape import_translations.cjs reads back once
 * a translated_value column is added. This answers "is locale X done yet?" without eyeballing a
 * diff, and re-running it after an import is the check that the strings landed where Android will
 * actually read them.
 *
 * It only catches MISSING keys. A key that exists but was translated stale or wrong needs a manual
 * diff. Strings marked translatable="false" are skipped; so are non-locale qualifiers such as
 * values-night and values-v31.
 */

const fs = require('fs');
const path = require('path');
const { toCsv } = require('./csv.cjs');
const { localeDirs, readStrings } = require('./res.cjs');
const { FILE_NAME, resolveContextFile } = require('./locale_context.cjs');

const args = process.argv.slice(2);
const positional = [];
let outPath = null;
let onlyLocales = null;
let resFile = 'strings.xml';

for (let i = 0; i < args.length; i++) {
    if (args[i] === '-o' || args[i] === '--csv' || args[i] === '--out') outPath = args[++i];
    else if (args[i] === '--locales') onlyLocales = args[++i].split(',').map(s => s.trim()).filter(Boolean);
    else if (args[i] === '--file') resFile = args[++i];
    else positional.push(args[i]);
}

if (positional.length < 1) {
    console.log('Usage: node export_untranslated.cjs <res_dir> [-o out.csv] [--locales de,fr] [--file <resource_file>]');
    console.log('  Diffs every values-<locale>/<file> against values/<file>.');
    console.log('  Exits non-zero while any locale is still missing a key.');
    process.exit(1);
}

const resDir = positional[0];
const defaultFile = path.join(resDir, 'values', resFile);
const defaults = [...readStrings(defaultFile)].filter(([, s]) => s.translatable);
if (!defaults.length) {
    console.error(`No translatable strings found in ${defaultFile}`);
    // A run against the wrong --file comes back clean, which is the one failure mode here that
    // looks like success — so an empty default file is an error, not a pass.
    process.exit(1);
}

// --locales may name a locale that has no directory yet; that is how a new language gets started.
const targets = onlyLocales
    ? onlyLocales.map(locale => ({ locale, file: path.join(resDir, `values-${locale}`, resFile) }))
    : localeDirs(resDir, resFile);

const rows = [];
const summary = [];

for (const { locale, file } of targets) {
    const translated = readStrings(file);
    let missing = 0;
    for (const [name, entry] of defaults) {
        if (translated.has(name)) continue;
        rows.push({ locale, name, default_value: entry.value });
        missing++;
    }
    summary.push(`  ${locale}: ${missing} missing of ${defaults.length}`);
}

console.log(summary.join('\n'));

if (!rows.length) {
    console.log(`\n✓ ${defaults.length} string(s) present in all ${targets.length} locale(s) of ${resFile}.`);
    process.exit(0);
}

if (outPath) {
    fs.writeFileSync(outPath, toCsv(['locale', 'name', 'default_value'], rows));
    console.log(`\nWrote ${rows.length} rows across ${targets.length} locales to ${outPath}`);
    console.log('Add a "translated_value" column, fill it in, then run import_translations.cjs.');
    const ctxFile = resolveContextFile(resDir);
    if (ctxFile) {
        console.log(`Context: ${ctxFile} — read it before translating.`);
    } else {
        // A gap CSV alone says nothing about what the module is for or what the app already calls
        // a concept, which is where invented terminology comes from.
        console.log(`No ${FILE_NAME} at or above ${resDir} — consider running init_locale.cjs so this`);
        console.log(`module's terminology is decided once instead of per translation run.`);
    }
} else {
    console.log(`\n${rows.length} row(s) missing. Re-run with -o <path> to emit them as a translation CSV.`);
}
process.exit(1);
