#!/usr/bin/env node
/**
 * Split a CSV into chunks, repeating the header in each one.
 *
 * Usage:
 *   node split_csv.cjs <input> <output_prefix> <rows_per_chunk>
 *   node split_csv.cjs <input> <output_prefix> --by <column>
 *
 * Splitting happens per record, not per line, so fields containing newlines
 * survive the round trip. `--by locale` is usually what you want for Android
 * string exports: one chunk per language keeps terminology consistent within
 * a single translation pass.
 */

const fs = require('fs');
const { readCsvFile, toCsv } = require('./csv.cjs');

function write(prefix, suffix, header, records) {
    const fileName = `${prefix}_${suffix}.csv`;
    fs.writeFileSync(fileName, toCsv(header, records));
    console.log(`Created ${fileName} (${records.length} rows)`);
}

const args = process.argv.slice(2);
if (args.length < 3) {
    console.log('Usage: node split_csv.cjs <input> <output_prefix> <rows_per_chunk>');
    console.log('       node split_csv.cjs <input> <output_prefix> --by <column>');
    process.exit(1);
}

const [input, prefix] = args;
const { header, records } = readCsvFile(input);

if (args[2] === '--by') {
    const column = args[3];
    if (!header.includes(column)) {
        console.error(`Column "${column}" not found. Available: ${header.join(', ')}`);
        process.exit(1);
    }
    const groups = new Map();
    for (const rec of records) {
        const key = String(rec[column]).trim();
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(rec);
    }
    for (const [key, rows] of groups) {
        write(prefix, key.replace(/[^A-Za-z0-9._-]/g, '_'), header, rows);
    }
    console.log(`Split into ${groups.size} chunks by "${column}".`);
} else {
    const perChunk = parseInt(args[2], 10);
    if (!Number.isFinite(perChunk) || perChunk < 1) {
        console.error('rows_per_chunk must be a positive integer.');
        process.exit(1);
    }
    let chunk = 0;
    for (let i = 0; i < records.length; i += perChunk) {
        write(prefix, `chunk_${++chunk}`, header, records.slice(i, i + perChunk));
    }
    console.log(`Split ${records.length} rows into ${chunk} chunks.`);
}
