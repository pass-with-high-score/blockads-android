const fs = require('fs');
const path = require('path');

function escapeAndroidString(val) {
    if (!val) return '';
    return val
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/'/g, "\\'")
        .replace(/"/g, '\\"');
}

function importTranslations(csvPath, resDir) {
    const content = fs.readFileSync(csvPath, 'utf8');
    const lines = content.split('\n');
    const header = lines[0].split(',');
    
    // Assume columns: locale, name, default_value, translated_value
    const localeIdx = header.indexOf('locale');
    const nameIdx = header.indexOf('name');
    const transIdx = header.indexOf('translated_value');

    if (localeIdx === -1 || nameIdx === -1 || transIdx === -1) {
        console.error('CSV must have "locale", "name", and "translated_value" columns.');
        process.exit(1);
    }

    const translations = {};

    for (let i = 1; i < lines.length; i++) {
        const line = lines[i].trim();
        if (!line) continue;
        
        const parts = [];
        let current = '';
        let inQuotes = false;
        for (let char of line) {
            if (char === '"') inQuotes = !inQuotes;
            else if (char === ',' && !inQuotes) {
                parts.push(current);
                current = '';
            } else {
                current += char;
            }
        }
        parts.push(current);
        
        const locale = parts[localeIdx];
        const name = parts[nameIdx];
        const value = parts[transIdx];

        if (!translations[locale]) translations[locale] = [];
        translations[locale].push({ name, value });
    }

    for (const [locale, items] of Object.entries(translations)) {
        // Handle Android locale mapping (e.g., pt-rBR)
        let androidLocale = locale;
        if (locale === 'pt-BR' || locale === 'pt-rBR') androidLocale = 'pt-rBR';
        
        const targetDir = path.join(resDir, `values-${androidLocale}`);
        const targetFile = path.join(targetDir, 'strings.xml');

        if (!fs.existsSync(targetDir)) {
            console.log(`Creating directory: ${targetDir}`);
            fs.mkdirSync(targetDir, { recursive: true });
        }

        let fileContent = '';
        if (fs.existsSync(targetFile)) {
            fileContent = fs.readFileSync(targetFile, 'utf8');
        } else {
            fileContent = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>';
        }

        items.forEach(item => {
            const escapedValue = escapeAndroidString(item.value);
            const entry = `    <string name="${item.name}">${escapedValue}</string>`;
            
            // If entry already exists, replace it; otherwise, insert before </resources>
            const regex = new RegExp(`    <string name="${item.name}">[\\s\\S]*?</string>`);
            if (regex.test(fileContent)) {
                fileContent = fileContent.replace(regex, entry);
            } else {
                fileContent = fileContent.replace('</resources>', `${entry}\n</resources>`);
            }
        });

        fs.writeFileSync(targetFile, fileContent);
        console.log(`Updated ${targetFile} with ${items.length} translations.`);
    }
}

const args = process.argv.slice(2);
if (args.length < 2) {
    console.log('Usage: node import_translations.cjs <csv_path> <res_dir>');
    process.exit(1);
}

importTranslations(args[0], args[1]);
