#!/usr/bin/env node
/**
 * Merge translated chunks back into one CSV.
 *
 * Usage: node merge_csv.cjs <output_path> <input1> <input2> ...
 *
 * Chunks are merged per record, so quoted fields containing commas or
 * newlines are preserved. Column sets must match across chunks; a mismatch is
 * an error rather than a silently misaligned row.
 */

const fs = require('fs');
const { readCsvFile, toCsv } = require('./csv.cjs');

const args = process.argv.slice(2);
if (args.length < 2) {
    console.log('Usage: node merge_csv.cjs <output_path> <input1> <input2> ...');
    process.exit(1);
}

const outputPath = args[0];
const inputFiles = args.slice(1);

let header = null;
const merged = [];

for (const file of inputFiles) {
    const parsed = readCsvFile(file);
    if (parsed.records.length === 0) {
        console.warn(`warning: ${file} has no data rows`);
        continue;
    }
    if (header === null) {
        header = parsed.header;
    } else if (header.join(',') !== parsed.header.join(',')) {
        console.error(`Header mismatch in ${file}`);
        console.error(`  expected: ${header.join(', ')}`);
        console.error(`  found:    ${parsed.header.join(', ')}`);
        process.exit(1);
    }
    merged.push(...parsed.records);
}

if (header === null) {
    console.error('No input rows found.');
    process.exit(1);
}

fs.writeFileSync(outputPath, toCsv(header, merged));
console.log(`Merged ${inputFiles.length} files into ${outputPath} (${merged.length} rows).`);
