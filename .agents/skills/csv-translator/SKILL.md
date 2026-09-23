---
name: csv-translator
description: Translate CSV files of Android string resources and import them into res/values-*/strings.xml. Plans, splits large files into chunks, translates, merges, validates and imports. Also audits and fills <plurals> quantity gaps (Lint MissingQuantity, e.g. "For locale ar the following quantities should also be defined: few, many, two, zero"), and finds unused strings. Use when translating a CSV, adding missing translations for a locale, or fixing missing plural quantities.
---

# CSV Translator Workflow

Translate large CSV files without blowing the context window, then import the results into Android
string resources.

All scripts live in `scripts/` and share two modules: `csv.cjs`, an RFC 4180 parser, and `res.cjs`,
the resource reader. Fields keep their commas, quotes and newlines through every step, and values
read out of a resource file come back as **raw text** — Android escaping is applied only on write.

## Invocation

| Argument | Do this |
| --- | --- |
| `init` | Scaffold the module's `.locale`, then **fill its TODO sections yourself** |
| anything else, or nothing | The translation workflow, from step 1 |

### `init`

```
node scripts/init_locale.cjs <res_dir>
```

It writes `.locale` at the module root (the parent of `src/main/res`), pre-filled with what the
resources already state: locale count and string count, brand-name candidates, frequent terms, and
any `_highlight` couplings. It refuses to overwrite an existing file without `--force`, and warns
when a `.locale` further up already covers the module — resolution takes the nearest, so a new file
overrides rather than extends it.

**The scaffold is not the deliverable.** It leaves `TODO` in the sections a script cannot derive,
and finishing them is the point of running `init` at all:

1. **`# What this module is`** — read the module before writing this. Two or three sentences of what
   the user is doing on these screens is what a translator cannot get from a CSV. Note anything that
   inverts the obvious reading of a string.
2. **`## Audience & tone`** — register, person, and line length. Name the pronoun each language
   should use (`vi`: `bạn`, not `quý khách`); that is the decision that drifts fastest between runs.
3. **`## Do not translate`** — the candidates are only capitalised repeats. **Prune them**: every
   term left becomes a hard error, so an ordinary word left in the list fails a correct translation.
   Multi-word names are detected as one phrase (`microG Companion`, `Google Play Store`), because no
   translation of the phrase keeps each half separately.
4. **`## Glossary`** — candidates ship **commented out**, which is why they are not yet enforced.
   Uncomment and fill only the terms you actually settled, one row per locale. Filling this in as you
   translate is what makes the next run inherit the terminology instead of re-deciding it.
5. **`## Notes per locale`** — the ambiguous codes are pre-annotated (`in` vs `id`, `pt-BR`, `zh`
   variant, `ar` plurals). Answer them by grepping the existing files, and delete the note once
   answered.

Anything still saying `TODO` when you finish means `init` did not do its job.

## Scripts

| Script | Usage |
| --- | --- |
| `locale_context.cjs` | `node scripts/locale_context.cjs <res_dir>` |
| `init_locale.cjs` | `node scripts/init_locale.cjs <res_dir> [--file <resource_file>] [--force]` |
| `export_untranslated.cjs` | `node scripts/export_untranslated.cjs <res_dir> [-o out.csv] [--locales de,fr] [--file <f>]` |
| `audit_plurals.cjs` | `node scripts/audit_plurals.cjs <res_dir> [--csv out.csv] [--locales de,fr] [--file <f>]` |
| `find_unused_strings.cjs` | `node scripts/find_unused_strings.cjs <module_dir> [<extra_dir> ...]` |
| `split_csv.cjs` | `node scripts/split_csv.cjs <input> <prefix> --by locale` \| `<rows_per_chunk>` |
| `merge_csv.cjs` | `node scripts/merge_csv.cjs <output> <input1> <input2> ...` |
| `join_source.cjs` | `node scripts/join_source.cjs <source_csv> <translated_csv> <output_csv>` |
| `validate_translations.cjs` | `node scripts/validate_translations.cjs <translated_csv> [source_csv] [--context <dir>] [--strict-glossary]` |
| `import_translations.cjs` | `node scripts/import_translations.cjs <csv> <res_dir> [--file <f>] [--dry-run] [--allow-placeholder-mismatch]` |
| `csv.cjs` / `res.cjs` / `plural_rules.cjs` | libraries — the CSV parser, the resource reader, the CLDR quantity table |

**`--file` for strings kept outside `strings.xml`.** The res-directory scripts default to
`strings.xml` and take any filename instead. A feature whose strings live in their own file is
invisible to a run without the flag: the gap report comes back clean because it compared a file that
has no gaps, which is the one failure mode here that looks like success. Pass `--file` to *every*
step of such a run, discovery and import alike.

**Modules in this repo have separate resource trees:** `app/src/main/res`, `core/src/main/res`,
`tv/src/main/res`, `wearos/src/main/res`. Their locale sets are not identical (`values-pl` exists in
`app` and `wearos` but not in `core` or `tv`), so run discovery per module rather than assuming one
answer covers all four.

## `.locale` — what the module is for

A gap CSV is a list of keys and English sentences. It says nothing about what the screen does, who is
reading it, or what the app already calls a concept — so every translation run re-decides the
terminology, which is exactly how the same idea ends up named two ways. `.locale` is where a module
states that once, in Markdown, next to the resources.

**Read it before translating anything:**

```
node scripts/locale_context.cjs app/src/main/res
```

No file yet? `init` scaffolds one — see **Invocation** above.

Resolution walks **up** from the res directory and stops at the directory holding `.git`, so the file
can sit beside `res/`, at the module root or at the repo root. Nearest wins, which lets one module
override a repo-wide file. The discovery scripts print where they found it — or that there is none.

### Format

Free prose everywhere, except two headings that are parsed and checked:

```markdown
# What this module is

An APK/XAPK sideloader. `install` is always the act of writing an app onto THIS device, never
"set up an account".

## Audience & tone

Second person, direct, no hedging. Fits one line in a dialog button at 360dp.

## Do not translate

- Shizuku
- VirusTotal
- microG Companion

## Glossary

| term | locale | translation |
| --- | --- | --- |
| install | vi | cài đặt |
| package | vi | gói |
| tracker | ru | трекер |

## Notes per locale

- `in` is Indonesian — Android's legacy code, never write `id`.
- `vi` uses "bạn", not "quý khách".
```

- **`## Do not translate`** — a bullet list. Heading also matches "Keep verbatim".
- **`## Glossary`** — a 3-column table; heading also matches "Terminology". The `locale` cell takes
  several codes (`vi, ru`) when the term happens to share a translation.
- HTML comments are ignored, which is how `init` ships candidates that are not decisions yet.
- Every other section is prose for you to read; nothing parses it, so write whatever the next person
  translating this module needs to know.

### What gets checked

`validate_translations.cjs --context <res_dir | .locale>` turns those two sections into checks:

| Section | Severity | Why |
| --- | --- | --- |
| Do not translate | **error** | A brand or product name has no translated form, so exact containment holds in every locale |
| Glossary | **warning** | Russian and Arabic inflect the agreed word (`трекер` → `трекеров`), so a character-for-character match would be a false positive |

Casing differs between the two on purpose: a glossary term matches case-insensitively, because a
sentence-initial capital is not a terminology miss, while a do-not-translate term matches exactly — a
brand name has one casing, and `SHIZUKU` is a bug.

`--strict-glossary` promotes the warnings to errors — safe for locales that do not inflect (`vi`,
CJK), noisy otherwise. A glossary term nested inside a do-not-translate one ("Store" inside "Google
Play Store") is masked out before matching, so the verbatim phrase does not trip its own entry.

Both checks need `default_value` in the CSV, which is what `join_source.cjs` puts back — a CSV of
only `locale,name,translated_value` is checked for everything else but skips these two.

## CSV format

`import_translations.cjs` requires these column names in the header:

| Column | Required | Purpose |
| --- | --- | --- |
| `locale` | yes | Mapped onto an Android qualifier: `pt-BR` → `values-pt-rBR`, `id` → `values-in`, `zh-Hans` → `values-b+zh+Hans` |
| `name` | yes | The `<string name="...">` / `<plurals name="...">` key |
| `translated_value` | yes | The text that gets written |
| `quantity` | no | Set it and the row becomes a `<plurals>` `<item>` instead of a `<string>` |
| `default_value` | no | Source text; used to check placeholders and the `.locale` sections |

A CSV holding only `locale,name,default_value` is an *input* to translate, not something the importer
accepts — add a `translated_value` column before importing.

## Strings (`<string>`)

To find out which locales are missing which keys — "is translation X done yet?" — run the discovery
script instead of eyeballing diffs:

```
node scripts/locale_context.cjs app/src/main/res                            # read the module context
node scripts/export_untranslated.cjs app/src/main/res -o gaps.csv           # find gaps, emit CSV
node scripts/split_csv.cjs gaps.csv chunk --by locale                       # one chunk per language
# translate into chunk CSVs holding locale,name,translated_value
node scripts/merge_csv.cjs merged.csv chunk_vi.csv chunk_de.csv
node scripts/join_source.cjs gaps.csv merged.csv translated.csv
node scripts/validate_translations.cjs translated.csv gaps.csv --context app/src/main/res
node scripts/import_translations.cjs translated.csv app/src/main/res --dry-run
node scripts/import_translations.cjs translated.csv app/src/main/res
node scripts/export_untranslated.cjs app/src/main/res                       # confirm: exits 0
```

Restrict to one locale with `--locales vi`. A `<string translatable="false">` in the default file (an
ID, a brand name) is skipped — it isn't meant to be translated. Discovery only catches *missing
keys*; a key that exists but was translated stale or wrong needs a manual diff.

**Write only the columns that carry new information, then join.** `join_source.cjs` puts
`default_value` back from the discovery CSV, so the source text is never retyped. Copying it along by
hand is the dullest part of the job and the one place a silent drift makes
`validate_translations.cjs` compare a translation against the wrong original. The join is also the
row-set check: a key on one side and not the other is an error, not a blank to fill, because a
dropped row means a locale quietly keeps falling back and an extra row means a dead key is about to
be written into every locale file.

**Unused strings:** `node scripts/find_unused_strings.cjs app core tv wearos` — pass every module
that might reference them, since a string declared in one module is often used from another.
Heuristic; names built at runtime cannot be detected, so review before deleting. Deleting a dead key
before a translation run is cheaper than translating it into 19 languages.

## Plurals (`<plurals>`)

A locale must define every quantity keyword CLDR gives its language, or Lint fails the build with
`MissingQuantity`:

> For locale "ar" (Arabic) the following quantities should also be defined: few, many, two, zero

Translating a plural is therefore not "one row per key" — it is **one row per quantity**, and the
required set differs per language (`vi`, `ja`, `zh`, `in` need only `other`; `ru` and `pl` need four;
`ar` needs all six).

```
node scripts/locale_context.cjs app/src/main/res                  # read the module context
node scripts/audit_plurals.cjs app/src/main/res --csv gaps.csv    # find gaps, emit CSV
# translate: fill a translated_value column (or write a chunk and join_source.cjs it back)
node scripts/validate_translations.cjs gaps.csv --context app/src/main/res
node scripts/import_translations.cjs gaps.csv app/src/main/res
node scripts/audit_plurals.cjs app/src/main/res                   # confirm: exits 0
```

- **The audit also catches a block that is absent from a locale entirely** — not just an incomplete
  one. Auditing only the blocks a locale already declares reports a clean run for a locale that
  translated no plurals at all. `app` has exactly this gap today:
  `dialog_menu_trackers_found` exists in `values/` and `values-vi/` and in no other locale.
- **The importer MERGES a plural block.** Only the quantities in the CSV are written; items already
  in the file survive. This is what makes it safe to feed back a CSV containing nothing but the
  missing quantities. Items are re-emitted in CLDR order (zero, one, two, few, many, other).
- **A plural form may drop the placeholder.** Arabic `two` is a dual form carrying the count inside
  the word, so `%1$d` there would render "2 صورتان". `validate_translations.cjs` allows a plural row
  to omit placeholders but still rejects ones the source never had. Plain `<string>` rows keep the
  strict exact-match check.
- **`quantity` must be** `zero`, `one`, `two`, `few`, `many` or `other`; a name cannot have both
  plural and non-plural rows. Both the validator and the importer refuse that.
- **`plural_rules.cjs` follows CLDR, which is occasionally stricter than Lint.** A language missing
  from the table is reported as skipped rather than guessed — `fil` is the known case where CLDR
  requires `one` and Lint has no rule at all, so only this audit catches the gap.

## Workflow Steps

### 1. Planning & Analysis

- **Read `.locale` first:** `node scripts/locale_context.cjs <res_dir>`. It is the module's
  terminology and tone decided once; skipping it is what makes two runs name one concept two ways.
  If there is none, run `init` and fill it in — translating is the moment you know enough to.
- **Identify target:** which module's res directory, which resource file, which locales.
- **Size it up:** `wc -l` for a rough count.
- **Pick a chunking strategy** (below).

### 2. Splitting

```
node scripts/split_csv.cjs <input> <prefix> --by locale     # one file per language
node scripts/split_csv.cjs <input> <prefix> <rows_per_chunk>
```

Prefer `--by locale`. One chunk per language means each translation pass sees a whole language at
once, which keeps terminology consistent — that matters more than an even row count, and a split
through the middle of a locale invites the same concept being named two ways. Fall back to
`<rows_per_chunk>` (100–200) when a single locale is still too large, and split again if a chunk
remains too big for one turn. Below ~100 rows, translate in one pass — splitting a small file only
adds merge steps that can drop rows.

### 3. Translation

- **`.locale` outranks your instinct.** Its glossary is the app's agreed word for a concept and its
  do-not-translate list is exact. Where the file and a dictionary disagree, the file wins; where the
  file is silent, grep as below and then consider *adding* what you decided to the glossary.
- **Look for the phrase before translating it.** Grep the *default* file for the English source text
  — if another key already says it, that key is already translated in every locale, and its wording
  is the answer:
  ```
  grep -in 'allow downgrade' app/src/main/res/values/strings.xml
  for d in app/src/main/res/values-*/; do
      printf '%-28s %s\n' "$d" "$(grep -o '<string name="setting_shizuku_downgrade">[^<]*' "$d/strings.xml" | sed 's/.*">//')"
  done
  ```
  Inventing a fresh word for a concept the app already names is the most common quality bug this
  workflow produces, and it is usually one grep away from being avoided. The same applies when help
  text quotes a UI label: point at the string the user actually sees.
- **Check which variant of a language the locale holds** before writing it. `values-pt-rBR` says
  Brazilian, but a bare `values-pt` would not; grep for marker words (`você`/`celular` vs
  `telemóvel`/`autocarro`) and match what is already there. Same for `values-zh` — Simplified or
  Traditional is not in the directory name.
- **Placeholders are literal:** `%1$s`, `%1$d`, `%%` must survive verbatim, index included. Their
  *position* in the sentence may move to suit the grammar (`%2$d 個中 %1$d 個`), but the set must
  match — the validator and the importer both enforce this.
- **Write raw text, not Android escapes.** Do **not** type `\'` or `\"`; the importer escapes `'`,
  `"`, bare `&`, `<`, `>`, newlines and a leading `@`/`?` for you, and pre-escaping double-escapes.
  A backslash in the CSV is a validation error for exactly this reason.
- **CSV quoting still applies.** A field containing a comma, a newline or a double quote must be
  wrapped in `"`, and a literal `"` inside it doubled: `"He said ""Allow downgrade"" here"`. Only
  U+002C splits a field, so CJK `、`/`，` and Arabic `،` need no quoting. Using the locale's own
  quotation marks (« », „ ", 「」) usually reads better anyway.

#### Strings that constrain each other

Two keys can be independent as text and coupled at runtime. Nothing in the CSV shows it, no script
checks it, and getting it wrong degrades silently — so find the coupling in the code that reads them
before translating:

- **A `_highlight` key is a substring of its message.** The usual pattern is
  `full.indexOf(highlight)` to bold a span; a highlight not literally present in the translated
  message falls through to plain text with no crash and no log. Translate the message first, then
  lift the highlight out of *that sentence*, word for word — never translate the highlight alone.
- **A string quoted inside another string must match it.** `setting_dhizuku_ready` and
  `help_modes_body` both quote *"Allow downgrade"*, which is `setting_shizuku_downgrade`'s own label.
  All three have to agree in a locale, or the instruction points at nothing the user can find.
- **A short label shares a row with its siblings.** Read the neighbouring keys before translating a
  one-word string, so two adjacent menu items do not both become the same word.

### 4. Merging

```
node scripts/merge_csv.cjs <output> <input1> <input2> ...
```

Keeps exactly one header row and refuses to merge chunks whose headers disagree — hand-written chunks
make a reordered or renamed column easy to produce and impossible to spot afterwards, since every row
of that chunk would import into the wrong field. It reports the row count; trust that over `wc -l`,
which counts lines and so includes the header.

### 5. Validation (before importing)

```
node scripts/validate_translations.cjs <translated_csv> <source_csv> --context <res_dir>
```

It parses with the same `csv.cjs` and extracts placeholders with the same `res.cjs` the importer
uses, so a CSV that passes here is a CSV the importer reads identically. It catches: missing columns,
unquoted commas (a shifted column count), empty translations, pre-escaped backslashes, duplicate
`locale`+`name`(+`quantity`), invalid quantity keywords, a name used both as string and plural,
dropped or renumbered placeholders, a locale code that will be rewritten (`id` → `in`), the `.locale`
do-not-translate list (error) and glossary (warning), and — when given the source — row-count,
ordering and `default_value` drift.

Exits non-zero on any problem. Fix the CSV and re-run before importing.

### 6. Importing

```
node scripts/import_translations.cjs <csv> <res_dir> [--file <f>] [--dry-run]
```

Run `--dry-run` first to preview the per-file counts. The import refuses to write **anything** if any
row has an empty translation, a duplicate key, a bad quantity, a string/plural name clash or
mismatched placeholders — a partial import is worse than none. Pass `--allow-placeholder-mismatch`
only when the change is intentional.

On write it:
- maps the locale onto Android's qualifier, including the legacy codes (`id` → `in`, `he` → `iw`);
- replaces an existing entry in place at whatever indentation the file uses, keeping whatever
  attributes it already had (`formatted="false"` and the like), instead of appending a duplicate key;
- merges a `<plurals>` block rather than replacing it, re-emitting items in CLDR order;
- seeds a locale file that does not exist yet from the default file's header, so the copies of a
  resource file read alike. `<resources>` is re-emitted plain: attributes like
  `tools:ignore="MissingTranslation"` answer for `values/` and would be a lie in a locale directory.

### 7. Post-import Validation

- Re-run the discovery script with the same `--file` — it must now exit 0. This is the check that the
  strings landed where Android will actually read them.
- `node scripts/audit_plurals.cjs <res_dir>` — must exit 0 if the CSV touched any plural.
- Confirm the diff touches only resource files and the count matches (`git diff --stat`). Plural
  imports rewrite their whole block, so expect a few changed lines per block rather than one per row.
- Spot-check one RTL locale (`values-ar`) and one CJK locale (`values-zh`, `values-ja`) in the file
  itself, plus one apostrophe-bearing value to confirm the importer escaped it (`l\'app`) and you
  did not.
- Build the resources. `app` and `tv` carry the `opensource`/`play` flavours, `wearos` has none, and
  `core` is a library whose resources are validated by whichever app builds:
  ```
  ./gradlew :app:processOpensourceDebugResources :tv:processOpensourceDebugResources \
            :wearos:processDebugResources
  ```
  Malformed XML or a bad placeholder fails resource linking, and these tasks isolate resource
  problems from unrelated compile errors far faster than `assembleDebug`.
- **Then confirm every locale reached the APK**, which a passing build does not prove: a file in a
  directory Android does not resolve still compiles.
  ```
  aapt2 dump resources <apk> | grep -A20 '<one_key_you_added>'
  ```
  One row per locale, values non-empty. A locale silently absent here is the `id`-vs-`in` class of
  mistake, and it is invisible in the source tree.

### 8. Cleanup & Git

- **`.locale` is committed** — it is the module's terminology, not a working file. Commit it with the
  resource changes, including any glossary line the run just settled.
- Do NOT commit intermediate CSVs (source export, chunks, merged, translated). Write them to a
  scratch directory outside the repo, or delete them once the import validates.
- Commit only the resource changes plus `.locale`.

## Guardrails

- **Preserve structure:** never change the column count or the meaning of `locale` / `name`.
- **Header integrity:** exactly one header row after merging.
- **Encoding:** UTF-8 throughout.
- **No double-escaping:** raw text in the CSV, Android escapes applied on import.
- **Never invent keys:** the key set must match the source export exactly; `join_source.cjs` is what
  proves it.
