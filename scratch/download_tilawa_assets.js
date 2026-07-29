const https = require('https');
const fs = require('fs');
const path = require('path');

const targetDir = 'c:/Users/Khaled El_Saadany/Desktop/webDevelopment/antigravity/مصحف القيام/app/src/main/assets/tilawa_model';
if (!fs.existsSync(targetDir)) {
    fs.mkdirSync(targetDir, { recursive: true });
}

const files = [
    { url: 'https://github.com/yazinsai/tilawa/releases/download/v0.2.0/fastconformer_full_mixed.onnx', name: 'model.onnx' },
    { url: 'https://github.com/yazinsai/tilawa/releases/download/v0.2.0/vocab.json', name: 'vocab.json' },
    { url: 'https://github.com/yazinsai/tilawa/releases/download/v0.2.0/quran.json', name: 'quran.json' },
    { url: 'https://github.com/yazinsai/tilawa/releases/download/v0.2.0/export_metadata.json', name: 'export_metadata.json' }
];

function downloadFile(url, dest, callback) {
    https.get(url, (res) => {
        if (res.statusCode === 301 || res.statusCode === 302) {
            return downloadFile(res.headers.location, dest, callback);
        }
        if (res.statusCode !== 200) {
            console.error(`Failed ${url}: ${res.statusCode}`);
            return callback(new Error(`HTTP ${res.statusCode}`));
        }
        const file = fs.createWriteStream(dest);
        let downloaded = 0;
        const total = parseInt(res.headers['content-length'], 10);
        res.on('data', chunk => {
            downloaded += chunk.length;
            if (total) {
                process.stdout.write(`${path.basename(dest)}: ${(downloaded / 1024 / 1024).toFixed(2)} MB / ${(total / 1024 / 1024).toFixed(2)} MB\r`);
            }
        });
        res.pipe(file);
        file.on('finish', () => {
            file.close();
            console.log(`\nFinished: ${path.basename(dest)}`);
            callback(null);
        });
    }).on('error', err => callback(err));
}

let idx = 0;
function processNext() {
    if (idx >= files.length) {
        console.log("All downloads completed!");
        return;
    }
    const item = files[idx++];
    const dest = path.join(targetDir, item.name);
    console.log(`Downloading ${item.name}...`);
    downloadFile(item.url, dest, (err) => {
        if (err) console.error(err);
        processNext();
    });
}

processNext();
