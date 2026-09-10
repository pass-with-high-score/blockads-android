const fs = require("fs");

/**
 * RFC 4180 CSV parsing and serialising.
 *
 * The previous hand-rolled parsers in this skill split on '\n' and toggled a
 * quote flag on every '"', which silently dropped every double quote in the
 * data and corrupted any field containing a newline. Everything here operates
 * on records, not lines, so both cases round-trip correctly.
 */

/** Parse a whole CSV document into an array of string arrays. */
function parseCsv(text) {
    // Strip a UTF-8 BOM and normalise line endings.
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);

    const rows = [];
    let row = [];
    let field = '';
    let inQuotes = false;
    let sawAnyChar = false;

    for (let i = 0; i < text.length; i++) {
        const c = text[i];

        if (inQuotes) {
            if (c === '"') {
                if (text[i + 1] === '"') {
                    field += '"';   // "" is an escaped quote
                    i++;
                } else {
                    inQuotes = false;
                }
            } else {
                field += c;
            }
            continue;
        }

        if (c === '"' && field === '') {
            inQuotes = true;
            sawAnyChar = true;
        } else if (c === ',') {
            row.push(field);
            field = '';
            sawAnyChar = true;
        } else if (c === '\r') {
            // handled by the '\n' branch; a lone \r also ends the record
            if (text[i + 1] !== '\n') {
                row.push(field);
                rows.push(row);
                row = [];
                field = '';
                sawAnyChar = false;
            }
        } else if (c === '\n') {
            row.push(field);
            rows.push(row);
            row = [];
            field = '';
            sawAnyChar = false;
        } else {
            field += c;
            sawAnyChar = true;
        }
    }

    if (sawAnyChar || field !== '' || row.length > 0) {
        row.push(field);
        rows.push(row);
    }

    return rows;
}

/** Parse into objects keyed by the header row. Returns { header, records }. */
function parseCsvRecords(text) {
    const rows = parseCsv(text).filter(r => !(r.length === 1 && r[0].trim() === ''));
    if (rows.length === 0) return { header: [], records: [] };

    const header = rows[0].map(h => h.trim());
    const records = rows.slice(1).map((cells, i) => {
        const rec = { __line: i + 2 };
        header.forEach((name, col) => { rec[name] = cells[col] !== undefined ? cells[col] : ''; });
        return rec;
    });
    return { header, records };
}

/** Quote a single field only when it needs it. */
function quoteField(value) {
    const s = value === null || value === undefined ? '' : String(value);
    return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}

/** Serialise rows (arrays) or records (objects + header) back to CSV text. */
function toCsv(header, records) {
    const lines = [header.map(quoteField).join(',')];
    for (const rec of records) {
        lines.push(header.map(h => quoteField(rec[h])).join(','));
    }
    return lines.join('\n') + '\n';
}

/** Read a CSV with a one-line error instead of a Node stack trace when it is not there. */
function readCsvFile(file) {
    if (!fs.existsSync(file)) {
        console.error(`No such file: ${file}`);
        console.error(`  (paths are relative to your cwd: ${process.cwd()})`);
        process.exit(1);
    }
    return parseCsvRecords(fs.readFileSync(file, "utf8"));
}

module.exports = { parseCsv, parseCsvRecords, toCsv, quoteField, readCsvFile };
