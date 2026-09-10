/**
 * Android resource helpers shared by every script in this skill.
 *
 * Locale detection, <string>/<plurals> reading, qualifier mapping and Android escaping all used to
 * be re-implemented per script, which is how the same directory came to be treated as a locale by
 * one script and skipped by another.
 */

const fs = require('fs');
const path = require('path');

/** Qualifiers that look like a locale suffix but are not one. */
const NON_LOCALE = /^(night|notnight|land|port|ldrtl|ldltr|round|notround|car|television|watch|desk|appliance|vrheadset|v\d+|sw\d+dp|w\d+dp|h\d+dp|small|normal|large|xlarge|long|notlong|.*dpi|touchscreen|notouch|finger|keysexposed|keyshidden|keyssoft|nokeys|qwerty|12key|navexposed|navhidden|nonav|dpad|trackball|wheel)$/;

/** `values-pt-rBR` -> `pt-BR`, `values-b+zh+Hans` -> `zh-Hans`, `values-night` -> null. */
function localeOfDir(dirName) {
    if (!dirName.startsWith('values-')) return null;
    const suffix = dirName.slice('values-'.length);
    if (!suffix || NON_LOCALE.test(suffix)) return null;
    if (suffix.startsWith('b+')) return suffix.slice(2).split('+').join('-');
    const m = suffix.match(/^([a-z]{2,3})(?:-r([A-Z]{2}))?$/);
    if (!m) return null;
    return m[2] ? `${m[1]}-${m[2]}` : m[1];
}

/** Map a BCP 47 style tag onto an Android resource qualifier. */
function androidLocale(locale) {
    const tag = String(locale).trim().replace(/_/g, '-');
    // Legacy codes Android still expects. `id` in a CSV would land in values-id, which Android
    // never resolves, and nothing about the source tree would show it.
    const legacy = { id: 'in', he: 'iw', yi: 'ji' };
    const parts = tag.split('-');
    const lang = legacy[parts[0].toLowerCase()] || parts[0].toLowerCase();
    if (parts.length === 1) return lang;
    if (/^r[A-Z]{2}$/.test(parts[1])) return `${lang}-${parts[1]}`;
    if (/^[A-Za-z]{2}$/.test(parts[1])) return `${lang}-r${parts[1].toUpperCase()}`;
    // Script or BCP47 extension (zh-Hans) needs the b+ form.
    return `b+${[lang, ...parts.slice(1)].join('+')}`;
}

/** Every locale directory in `resDir` that actually holds `resFile`, in sorted order. */
function localeDirs(resDir, resFile = 'strings.xml') {
    return fs.readdirSync(resDir).sort()
        .map(dirName => ({ dirName, locale: localeOfDir(dirName) }))
        .filter(e => e.locale)
        .map(e => ({ ...e, file: path.join(resDir, e.dirName, resFile) }))
        .filter(e => fs.existsSync(e.file));
}

const ENTITIES = {
    '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'",
    '&#39;': "'", '&#x27;': "'", '&#34;': '"', '&nbsp;': ' ',
};

/**
 * Reverse of escapeAndroidString, so everything read out of a resource file is RAW text.
 *
 * Without it a discovery CSV carries the file's own escapes into default_value ("Couldn\'t share"),
 * the validator rejects its own input as pre-escaped, and a translator who copies that style gets
 * double-escaped strings on import.
 */
function unescapeAndroidString(value) {
    const backslashes = { n: '\n', t: '\t' };
    return String(value)
        .replace(/\\(.)/g, (_, c) => backslashes[c] ?? c)
        .replace(/&(?:amp|lt|gt|quot|apos|nbsp|#39|#x27|#34);/g, e => ENTITIES[e] ?? e);
}

/** name -> { value, attrs, translatable } for every <string> in one resource file. Value is raw. */
function readStrings(file) {
    if (!fs.existsSync(file)) return new Map();
    const xml = fs.readFileSync(file, 'utf8');
    const out = new Map();
    const re = /<string(\s[^>]*?)?\sname="([^"]+)"([^>]*)>([\s\S]*?)<\/string>/g;
    let m;
    while ((m = re.exec(xml)) !== null) {
        const attrs = (m[1] || '') + (m[3] || '');
        out.set(m[2], {
            value: unescapeAndroidString(m[4]),
            attrs,
            translatable: !/translatable\s*=\s*"false"/.test(attrs),
        });
    }
    return out;
}

/** name -> Map<quantity, raw text> for every <plurals> in one resource file. */
function readPlurals(file) {
    if (!fs.existsSync(file)) return new Map();
    const xml = fs.readFileSync(file, 'utf8');
    const out = new Map();
    const blockRe = /<plurals(\s[^>]*?)?\sname="([^"]+)"([^>]*)>([\s\S]*?)<\/plurals>/g;
    let block;
    while ((block = blockRe.exec(xml)) !== null) {
        const items = new Map();
        const itemRe = /<item\s+quantity="([^"]+)"\s*>([\s\S]*?)<\/item>/g;
        let item;
        while ((item = itemRe.exec(block[4])) !== null) {
            items.set(item[1], unescapeAndroidString(item[2]));
        }
        out.set(block[2], items);
    }
    return out;
}

/** Escape raw text for use as an Android resource value. */
function escapeAndroidString(value) {
    let s = String(value);
    // Escape bare ampersands but leave existing entities (&amp; &#39; &#x27;) alone.
    s = s.replace(/&(?!(?:[a-zA-Z][a-zA-Z0-9]*|#\d+|#x[0-9a-fA-F]+);)/g, '&amp;');
    s = s.replace(/</g, '&lt;').replace(/>/g, '&gt;');
    s = s.replace(/'/g, "\\'").replace(/"/g, '\\"');
    s = s.replace(/\r\n|\r|\n/g, '\\n').replace(/\t/g, '\\t');
    // A leading @ or ? would be read as a resource reference.
    s = s.replace(/^([@?])/, '\\$1');
    return s;
}

/** Format placeholders, as a sorted multiset, for comparing source against translation. */
function placeholders(value) {
    const found = String(value ?? '').match(/%(?:\d+\$)?[-+ 0#,(]*\d*(?:\.\d+)?[a-zA-Z]|%%/g) || [];
    return found.sort();
}

function escapeRegExp(s) {
    return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Indentation the file already uses for its entries. */
function detectIndent(xml) {
    const m = xml.match(/^([ \t]+)<(?:string|plurals)\b/m);
    return m ? m[1] : '    ';
}

/**
 * Header for a locale file that does not exist yet, copied from the default file's XML declaration
 * and leading comment so the copies of a resource file read alike. <resources> is re-emitted plain:
 * attributes like tools:ignore="MissingTranslation" answer for values/ and would be a lie here.
 */
function seedContent(defaultFile) {
    let head = '<?xml version="1.0" encoding="utf-8"?>\n';
    if (fs.existsSync(defaultFile)) {
        const xml = fs.readFileSync(defaultFile, 'utf8');
        const upToResources = xml.slice(0, xml.indexOf('<resources'));
        if (upToResources.trim()) head = upToResources.replace(/\s*$/, '\n');
    }
    return `${head}<resources>\n</resources>\n`;
}

module.exports = {
    NON_LOCALE, localeOfDir, androidLocale, localeDirs,
    readStrings, readPlurals,
    escapeAndroidString, unescapeAndroidString,
    placeholders, escapeRegExp, detectIndent, seedContent,
};
