const fs = require('fs');

function mergeCsv(inputFiles, outputPath) {
    let header = '';
    let mergedContent = [];

    inputFiles.forEach((file, index) => {
        const content = fs.readFileSync(file, 'utf8').split('\n');
        if (index === 0) {
            header = content[0];
            mergedContent.push(header);
        }
        // Add all lines except the header
        mergedContent.push(...content.slice(1).filter(line => line.trim() !== ''));
    });

    fs.writeFileSync(outputPath, mergedContent.join('\n'));
    console.log(`Merged ${inputFiles.length} files into ${outputPath}`);
}

const args = process.argv.slice(2);
if (args.length < 2) {
    console.log('Usage: node merge_csv.cjs <output_path> <input_file1> <input_file2> ...');
    process.exit(1);
}

const outputPath = args[0];
const inputFiles = args.slice(1);
mergeCsv(inputFiles, outputPath);
