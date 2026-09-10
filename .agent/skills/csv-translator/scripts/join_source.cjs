#!/usr/bin/env node
/**
 * Join a translated CSV (locale,name[,quantity],translated_value) onto the source CSV that
 * export_untranslated.cjs / audit_plurals.cjs produced, emitting the four-column CSV the importer
 * and the validator want — in the source's own row order.
 *
 * Usage: node join_source.cjs <source_csv> <translated_csv> <output_csv>
 *
 * The point is that `default_value` is never retyped. Writing translations means writing only the
 * columns that carry new information; copying the source text along by hand is both the dullest
 * part of the job and the one place where a silent drift makes validate_translations.cjs compare a
 * translation against the wrong original.
 *
 * A key present on one side but not the other is an error, not something to fill in with a blank:
 * a dropped row means a locale silently keeps falling back, and an extra row means a key that no
 * longer exists is about to be written into every locale file.
 */

const fs = require('fs');
const { readCsvFile, toCsv } = require('./csv.cjs');

/** locale + name + quantity is the identity of a row; quantity is absent for plain strings. */
function readRows(file, valueColumn) {
    const { header, records } = readCsvFile(file);
    for (const col of ['locale', 'name', valueColumn]) {
        if (header.includes(col)) continue;
        console.error(`${file} must have "locale", "name" and "${valueColumn}" columns.`);
        console.error(`  found: ${header.join(', ')}`);
        process.exit(1);
    }

    const rows = [];
    const seen = new Map();
    for (const rec of records) {
        const locale = rec.locale.trim();
        const name = rec.name.trim();
        const quantity = (rec.quantity || '').trim();
        const key = `${locale}\u0000${name}\u0000${quantity}`;
        if (seen.has(key)) {
            console.error(`${file}: line ${rec.__line} duplicates line ${seen.get(key)} ` +
                `(${locale} / ${name}${quantity ? ` / ${quantity}` : ''})`);
            process.exit(1);
        }
        seen.set(key, rec.__line);
        rows.push({ key, locale, name, quantity, value: rec[valueColumn] ?? '' });
    }
    return rows;
}

function join(sourcePath, translatedPath, outPath) {
    const source = readRows(sourcePath, 'default_value');
    const translated = readRows(translatedPath, 'translated_value');
    const byKey = new Map(translated.map(r => [r.key, r]));
    const sourceKeys = new Set(source.map(r => r.key));

    const describe = r => `${r.locale} / ${r.name}${r.quantity ? ` / ${r.quantity}` : ''}`;
    const problems = [];
    const list = (label, rows) => {
        if (!rows.length) return;
        problems.push(label.replace('{n}', rows.length));
        rows.slice(0, 10).forEach(r => problems.push(`    ${describe(r)}`));
        if (rows.length > 10) problems.push(`    ... and ${rows.length - 10} more`);
    };

    list('{n} source row(s) have no translation:', source.filter(r => !byKey.has(r.key)));
    list('{n} translated row(s) match no source row:', translated.filter(r => !sourceKeys.has(r.key)));
    list('{n} translation(s) are empty:', translated.filter(r => sourceKeys.has(r.key) && !r.value.trim()));

    if (problems.length) {
        console.error('✗ Cannot join:\n');
        problems.forEach(p => console.error(`  ${p}`));
        process.exit(1);
    }

    const withQuantity = source.some(r => r.quantity) || translated.some(r => r.quantity);
    const header = withQuantity
        ? ['locale', 'name', 'quantity', 'default_value', 'translated_value']
        : ['locale', 'name', 'default_value', 'translated_value'];

    const out = source.map(row => ({
        locale: row.locale,
        name: row.name,
        quantity: row.quantity,
        default_value: row.value,
        translated_value: byKey.get(row.key).value,
    }));
    fs.writeFileSync(outPath, toCsv(header, out));

    const locales = new Set(source.map(r => r.locale));
    console.log(`✓ Joined ${source.length} row(s) across ${locales.size} locale(s) into ${outPath}.`);
    console.log('  Next: validate_translations.cjs with the source, then import.');
}

const args = process.argv.slice(2);
if (args.length < 3) {
    console.log('Usage: node join_source.cjs <source_csv> <translated_csv> <output_csv>');
    console.log('  source_csv:     locale,name[,quantity],default_value  (from the discovery step)');
    console.log('  translated_csv: locale,name[,quantity],translated_value  (what you wrote)');
    console.log('  Exits non-zero if either side holds a key the other does not.');
    process.exit(1);
}
join(args[0], args[1], args[2]);
