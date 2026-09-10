/**
 * Which <item quantity="..."> keywords a locale must define, per CLDR — the same rule Android
 * Lint's MissingQuantity check enforces ("For locale "ar" (Arabic) the following quantities
 * should also be defined: few, many, two, zero").
 *
 * Keyed by ISO-639 language code, because the quantity set is a property of the LANGUAGE, not the
 * region: pt-rBR and pt need the same keywords.
 *
 * Deliberately NOT a guess-everything table. A language that is absent here is reported as
 * "unknown" by audit_plurals.cjs rather than silently assumed to be one/other — inventing a rule
 * for a language nobody verified is how a plural audit ends up worse than no audit.
 */

const ORDER = ['zero', 'one', 'two', 'few', 'many', 'other'];

const RULES = {
    // other only — no grammatical number distinction on the noun
    ja: ['other'], ko: ['other'], zh: ['other'], th: ['other'], vi: ['other'],
    in: ['other'], id: ['other'], ms: ['other'], my: ['other'], km: ['other'],
    lo: ['other'], yo: ['other'], ig: ['other'], to: ['other'], wo: ['other'],

    // one / other — the large majority
    en: ['one', 'other'], de: ['one', 'other'], nl: ['one', 'other'],
    sv: ['one', 'other'], da: ['one', 'other'], no: ['one', 'other'],
    nb: ['one', 'other'], nn: ['one', 'other'], fi: ['one', 'other'],
    et: ['one', 'other'], el: ['one', 'other'], hu: ['one', 'other'],
    tr: ['one', 'other'], az: ['one', 'other'], ka: ['one', 'other'],
    bg: ['one', 'other'], sq: ['one', 'other'], hy: ['one', 'other'],
    eu: ['one', 'other'], gl: ['one', 'other'], ca: ['one', 'other'],
    af: ['one', 'other'], sw: ['one', 'other'], zu: ['one', 'other'],
    ur: ['one', 'other'], ne: ['one', 'other'], mr: ['one', 'other'],
    bn: ['one', 'other'], gu: ['one', 'other'], kn: ['one', 'other'],
    ml: ['one', 'other'], ta: ['one', 'other'], te: ['one', 'other'],
    pa: ['one', 'other'], si: ['one', 'other'], hi: ['one', 'other'],
    fil: ['one', 'other'], tl: ['one', 'other'], uz: ['one', 'other'],
    kk: ['one', 'other'], ky: ['one', 'other'], mn: ['one', 'other'],
    is: ['one', 'other'], mk: ['one', 'other'], am: ['one', 'other'],

    // Romance languages that also carry a "many" class in current CLDR
    es: ['one', 'many', 'other'],
    fr: ['one', 'many', 'other'],
    pt: ['one', 'many', 'other'],
    it: ['one', 'many', 'other'],

    // Slavic one / few / many / other
    ru: ['one', 'few', 'many', 'other'], uk: ['one', 'few', 'many', 'other'],
    be: ['one', 'few', 'many', 'other'], pl: ['one', 'few', 'many', 'other'],
    cs: ['one', 'few', 'many', 'other'], sk: ['one', 'few', 'many', 'other'],
    hr: ['one', 'few', 'other'], sr: ['one', 'few', 'other'],
    bs: ['one', 'few', 'other'], lt: ['one', 'few', 'many', 'other'],
    lv: ['zero', 'one', 'other'], ro: ['one', 'few', 'other'],
    sl: ['one', 'two', 'few', 'other'],

    // Everything CLDR splits the most
    ar: ['zero', 'one', 'two', 'few', 'many', 'other'],
    cy: ['zero', 'one', 'two', 'few', 'many', 'other'],
    ga: ['one', 'two', 'few', 'many', 'other'],
    gd: ['one', 'two', 'few', 'other'],
    br: ['one', 'two', 'few', 'many', 'other'],
    he: ['one', 'two', 'other'], iw: ['one', 'two', 'other'],
    mt: ['one', 'few', 'many', 'other'],
};

/** `pt-rBR`, `zh-rCN`, `b+sr+Latn` -> `pt`, `zh`, `sr`. */
function languageOf(locale) {
    return String(locale)
        .replace(/^b\+/, '')
        .split(/[-_+]/)[0]
        .toLowerCase();
}

/** Required quantity keywords for a locale, or null when the language is not in the table. */
function requiredQuantities(locale) {
    return RULES[languageOf(locale)] ?? null;
}

/** Sort quantity keywords into CLDR reading order so generated XML is stable. */
function sortQuantities(list) {
    return [...list].sort((a, b) => ORDER.indexOf(a) - ORDER.indexOf(b));
}

module.exports = { ORDER, RULES, languageOf, requiredQuantities, sortQuantities };
