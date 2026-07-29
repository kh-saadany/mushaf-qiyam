const https = require('https');
const fs = require('fs');
const path = require('path');

const libsDir = 'c:/Users/Khaled El_Saadany/Desktop/webDevelopment/antigravity/مصحف القيام/app/libs';
if (!fs.existsSync(libsDir)) {
    fs.mkdirSync(libsDir, { recursive: true });
}

const fileUrl = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.40/sherpa-onnx-1.12.40.aar';
const destPath = path.join(libsDir, 'sherpa-onnx-1.12.40.aar');

function download(url, dest) {
    https.get(url, (res) => {
        if (res.statusCode === 301 || res.statusCode === 302) {
            console.log('Redirecting to:', res.headers.location);
            return download(res.headers.location, dest);
        }
        if (res.statusCode !== 200) {
            console.error('Failed to download:', res.statusCode);
            return;
        }
        const file = fs.createWriteStream(dest);
        let downloaded = 0;
        const total = parseInt(res.headers['content-length'], 10);
        res.on('data', chunk => {
            downloaded += chunk.length;
            if (total) {
                process.stdout.write(`Downloaded ${(downloaded / 1024 / 1024).toFixed(2)} MB / ${(total / 1024 / 1024).toFixed(2)} MB\r`);
            }
        });
        res.pipe(file);
        file.on('finish', () => {
            file.close();
            console.log('\nDownload complete:', dest);
        });
    }).on('error', err => {
        fs.unlink(dest, () => {});
        console.error('Error:', err.message);
    });
}

console.log('Downloading sherpa-onnx AAR...');
download(fileUrl, destPath);
