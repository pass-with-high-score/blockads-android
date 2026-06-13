const fs = require('fs');
const path = require('path');
const readline = require('readline');

async function splitCsv(inputPath, outputPrefix, linesPerChunk) {
    const fileStream = fs.createReadStream(inputPath);
    const rl = readline.createInterface({
        input: fileStream,
        crlfDelay: Infinity
    });

    let header = '';
    let lineCount = 0;
    let chunkCount = 1;
    let currentChunkLines = [];

    for await (const line of rl) {
        if (lineCount === 0) {
            header = line;
        } else {
            currentChunkLines.push(line);
            if (currentChunkLines.length >= linesPerChunk) {
                writeChunk(outputPrefix, chunkCount++, header, currentChunkLines);
                currentChunkLines = [];
            }
        }
        lineCount++;
    }

    if (currentChunkLines.length > 0) {
        writeChunk(outputPrefix, chunkCount++, header, currentChunkLines);
    }
    
    console.log(`Split into ${chunkCount - 1} chunks.`);
}

function writeChunk(prefix, index, header, lines) {
    const fileName = `${prefix}_chunk_${index}.csv`;
    const content = [header, ...lines].join('\n');
    fs.writeFileSync(fileName, content);
    console.log(`Created ${fileName}`);
}

const args = process.argv.slice(2);
if (args.length < 3) {
    console.log('Usage: node split_csv.cjs <input_path> <output_prefix> <lines_per_chunk>');
    process.exit(1);
}

splitCsv(args[0], args[1], parseInt(args[2]));
