#!/usr/bin/env node
/**
 * Import translated strings and plurals from a CSV into Android values-<locale>/<file>.
 *
 * Usage:
 *   node import_translations.cjs <csv_path> <res_dir> [--file <resource_file>]
 *                               [--dry-run] [--allow-placeholder-mismatch]
 *
 * The CSV needs "locale", "name" and "translated_value" columns. A "default_value" column is
 * optional; when present it is used to check that the translation kept the same format
 * placeholders. Set "quantity" on a row and it becomes a <plurals> <item> instead of a <string>.
 *
 * Values in the CSV are raw text. Android escaping is applied here, so do not pre-escape anything.
 */

const fs = require('fs');
const path = require('path');
const { readCsvFile } = require('./csv.cjs');
const {
    androidLocale, escapeAndroidString, placeholders, escapeRegExp, detectIndent, seedContent,
} = require('./res.cjs');
const { ORDER, sortQuantities } = require('./plural_rules.cjs');

/** Read the CSV into per-locale string and plural work, refusing the whole run on any problem. */
function collect(csvPath, opts) {
    const { header, records } = readCsvFile(csvPath);

    for (const required of ['locale', 'name', 'translated_value']) {
        if (!header.includes(required)) {
            console.error(`CSV must have a "${required}" column. Found: ${header.join(', ')}`);
            process.exit(1);
        }
    }
    const hasDefault = header.includes('default_value');

    const byLocale = new Map();
    const problems = [];
    const seen = new Set();
    const kinds = new Map();

    for (const rec of records) {
        const locale = rec.locale.trim();
        const name = rec.name.trim();
        const quantity = (rec.quantity || '').trim();
        const value = rec.translated_value;
        const at = `line ${rec.__line}: ${locale}/${name}${quantity ? `:${quantity}` : ''}`;

        if (!locale || !name) {
            problems.push(`${at} is missing locale or name`);
            continue;
        }
        if (value === undefined || value.trim() === '') {
            problems.push(`${at} has an empty translation`);
            continue;
        }
        if (quantity && !ORDER.includes(quantity)) {
            problems.push(`${at} has quantity "${quantity}", not one of ${ORDER.join(', ')}`);
            continue;
        }

        const nameKey = `${locale}\u0000${name}`;
        const key = quantity ? `${nameKey}:${quantity}` : nameKey;
        if (seen.has(key)) {
            problems.push(`${at} is duplicated`);
            continue;
        }
        seen.add(key);

        const kind = quantity ? 'plurals' : 'string';
        if (kinds.has(nameKey) && kinds.get(nameKey) !== kind) {
            // aapt would fail on the duplicate resource name, after the file was already written.
            problems.push(`${at} is a <${kind}> but the same name is also a <${kinds.get(nameKey)}>`);
            continue;
        }
        kinds.set(nameKey, kind);

        if (hasDefault && rec.default_value) {
            const want = placeholders(rec.default_value);
            const got = placeholders(value);
            // A plural form may legitimately drop the placeholder (a dual form carries the count
            // inside the word). Adding or renumbering one is still a bug.
            const differs = quantity
                ? got.some(s => !want.includes(s))
                : want.join(' ') !== got.join(' ');
            if (differs) {
                const msg = `${at} placeholders differ — source [${want.join(' ')}] vs translation [${got.join(' ')}]`;
                if (opts.allowPlaceholderMismatch) console.warn(`warning: ${msg}`);
                else problems.push(msg);
            }
        }

        if (!byLocale.has(locale)) byLocale.set(locale, { strings: [], plurals: new Map() });
        const work = byLocale.get(locale);
        if (quantity) {
            if (!work.plurals.has(name)) work.plurals.set(name, new Map());
            work.plurals.get(name).set(quantity, value);
        } else {
            work.strings.push({ name, value });
        }
    }

    if (problems.length) {
        console.error(`Refusing to import, ${problems.length} problem(s) found:`);
        problems.forEach(p => console.error(`  ${p}`));
        console.error('Fix the CSV, or pass --allow-placeholder-mismatch if the placeholder change is intended.');
        process.exit(1);
    }
    return byLocale;
}

function stringPattern(name) {
    return new RegExp(`([ \\t]*)<string(\\s[^>]*?)?\\sname="${escapeRegExp(name)}"([^>]*)>[\\s\\S]*?</string>`);
}

function pluralsPattern(name) {
    return new RegExp(`([ \\t]*)<plurals(\\s[^>]*?)?\\sname="${escapeRegExp(name)}"([^>]*)>([\\s\\S]*?)</plurals>`);
}

function insertBeforeClose(xml, indent, block) {
    return xml.replace(/([ \t]*)<\/resources>/, `${block.replace(/^/gm, indent)}\n$1</resources>`);
}

/** Render a whole <plurals> block from a quantity -> raw text map, in CLDR reading order. */
function renderPlurals(name, items, indent) {
    const lines = [`<plurals name="${name}">`];
    for (const q of sortQuantities([...items.keys()])) {
        lines.push(`${indent}<item quantity="${q}">${escapeAndroidString(items.get(q))}</item>`);
    }
    lines.push('</plurals>');
    return lines.join('\n');
}

function importTranslations(csvPath, resDir, resFile, opts) {
    const byLocale = collect(csvPath, opts);
    const defaultFile = path.join(resDir, 'values', resFile);

    let totalAdded = 0;
    let totalReplaced = 0;

    for (const [locale, work] of byLocale) {
        const targetDir = path.join(resDir, `values-${androidLocale(locale)}`);
        const targetFile = path.join(targetDir, resFile);

        let xml = fs.existsSync(targetFile)
            ? fs.readFileSync(targetFile, 'utf8')
            : seedContent(defaultFile);
        if (!/<\/resources>/.test(xml)) {
            console.error(`${targetFile} has no </resources> close tag, skipping.`);
            continue;
        }

        const indent = detectIndent(xml);
        let added = 0;
        let replaced = 0;

        for (const item of work.strings) {
            const escaped = escapeAndroidString(item.value);
            // Match at any indentation, and keep whatever attributes the entry already had.
            const existing = stringPattern(item.name);
            const m = xml.match(existing);
            if (m) {
                xml = xml.replace(existing,
                    `${m[1]}<string${m[2] || ''} name="${item.name}"${m[3] || ''}>${escaped}</string>`);
                replaced++;
            } else {
                xml = insertBeforeClose(xml, indent, `<string name="${item.name}">${escaped}</string>`);
                added++;
            }
        }

        for (const [name, items] of work.plurals) {
            const existing = pluralsPattern(name);
            const m = xml.match(existing);
            if (m) {
                // MERGE: quantities already in the file and absent from the CSV survive, which is
                // what makes it safe to feed back a CSV holding nothing but the missing ones.
                const kept = new Map();
                const itemRe = /<item\s+quantity="([^"]+)"\s*>([\s\S]*?)<\/item>/g;
                let old;
                while ((old = itemRe.exec(m[4])) !== null) kept.set(old[1], old[2]);

                const rendered = [`${m[1]}<plurals${m[2] || ''} name="${name}"${m[3] || ''}>`];
                for (const q of sortQuantities([...new Set([...kept.keys(), ...items.keys()])])) {
                    // Existing items are already escaped in the file; only CSV values need it.
                    const body = items.has(q) ? escapeAndroidString(items.get(q)) : kept.get(q);
                    rendered.push(`${m[1]}${indent}<item quantity="${q}">${body}</item>`);
                }
                rendered.push(`${m[1]}</plurals>`);
                xml = xml.replace(existing, rendered.join('\n'));
                replaced++;
            } else {
                xml = insertBeforeClose(xml, indent, renderPlurals(name, items, indent));
                added++;
            }
        }

        if (!opts.dryRun) {
            fs.mkdirSync(targetDir, { recursive: true });
            fs.writeFileSync(targetFile, xml);
        }
        totalAdded += added;
        totalReplaced += replaced;
        console.log(`${opts.dryRun ? '[dry-run] ' : ''}${targetFile}: ${added} added, ${replaced} replaced`);
    }

    console.log(`\n${opts.dryRun ? '[dry-run] ' : ''}${byLocale.size} locale(s), ${totalAdded} added, ${totalReplaced} replaced.`);
    console.log(`  Next: re-run the discovery script (same --file) — it must exit 0.`);
}

const args = process.argv.slice(2);
const positional = [];
const opts = { dryRun: false, allowPlaceholderMismatch: false };
let resFile = 'strings.xml';

for (let i = 0; i < args.length; i++) {
    if (args[i] === '--dry-run') opts.dryRun = true;
    else if (args[i] === '--allow-placeholder-mismatch') opts.allowPlaceholderMismatch = true;
    else if (args[i] === '--file') resFile = args[++i];
    else positional.push(args[i]);
}

if (positional.length < 2) {
    console.log('Usage: node import_translations.cjs <csv_path> <res_dir> [--file <resource_file>] [--dry-run] [--allow-placeholder-mismatch]');
    process.exit(1);
}
importTranslations(positional[0], positional[1], resFile, opts);
