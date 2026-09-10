#!/usr/bin/env node
/**
 * Export all strings from values/<file> across all locales to a CSV.
 * Includes both default_value and translated_value if present.
 *
 * Usage:
 *   node export_all.cjs <res_dir> -o <out.csv> [--locales de,fr] [--file <resource_file>]
 */

const fs = require('fs');
const path = require('path');
const { toCsv } = require('./csv.cjs');
const { localeDirs, readStrings, androidLocale } = require('./res.cjs');

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

if (positional.length < 1 || !outPath) {
    console.log('Usage: node export_all.cjs <res_dir> -o <out.csv> [--locales de,fr] [--file <resource_file>]');
    process.exit(1);
}

const resDir = positional[0];
const defaultFile = path.join(resDir, 'values', resFile);
const defaults = [...readStrings(defaultFile)].filter(([, s]) => s.translatable);
if (!defaults.length) {
    console.error(`No translatable strings found in ${defaultFile}`);
    process.exit(1);
}

const targets = onlyLocales
    ? onlyLocales.map(locale => ({ locale, file: path.join(resDir, `values-${androidLocale(locale)}`, resFile) }))
    : localeDirs(resDir, resFile);

const rows = [];
for (const { locale, file } of targets) {
    const translated = fs.existsSync(file) ? readStrings(file) : new Map();
    for (const [name, entry] of defaults) {
        rows.push({
            locale,
            name,
            default_value: entry.value,
            translated_value: translated.get(name)?.value || ''
        });
    }
}

fs.writeFileSync(outPath, toCsv(['locale', 'name', 'default_value', 'translated_value'], rows));
console.log(`Wrote ${rows.length} rows (${defaults.length} strings × ${targets.length} locales) to ${outPath}`);
