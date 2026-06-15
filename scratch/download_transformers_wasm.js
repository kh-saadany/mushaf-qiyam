const fs = require('fs');
const https = require('https');
const path = require('path');

const destDir = 'c:\\Users\\Khaled El_Saadany\\Desktop\\webDevelopment\\antigravity\\مصحف القيام\\www';
const version = '3.0.1';

const files = [
  'ort-wasm-simd-threaded.jsep.wasm',
  'ort-wasm-simd.jsep.wasm',
  'ort-wasm-threaded.jsep.wasm',
  'ort-wasm.wasm',
  'ort-wasm-simd.wasm',
  'ort-wasm-threaded.wasm'
];

async function downloadFile(fileName) {
  const url = `https://cdn.jsdelivr.net/npm/@huggingface/transformers@${version}/dist/${fileName}`;
  const destPath = path.join(destDir, fileName);
  console.log(`Checking/Downloading ${url} to ${destPath}...`);

  return new Promise((resolve, reject) => {
    https.get(url, (response) => {
      if (response.statusCode === 404) {
        console.log(`File ${fileName} not found on server (404), skipping.`);
        resolve();
        return;
      }
      if (response.statusCode !== 200) {
        reject(new Error(`Failed to download ${fileName}: Status code ${response.statusCode}`));
        return;
      }
      const file = fs.createWriteStream(destPath);
      response.pipe(file);
      file.on('finish', () => {
        file.close();
        console.log(`Successfully downloaded ${fileName}`);
        resolve();
      });
    }).on('error', (err) => {
      fs.unlink(destPath, () => {});
      reject(err);
    });
  });
}

async function main() {
  try {
    if (!fs.existsSync(destDir)) {
      fs.mkdirSync(destDir, { recursive: true });
    }
    for (const file of files) {
      await downloadFile(file);
    }
    console.log('All available downloads completed successfully!');
  } catch (err) {
    console.error('Download failed:', err);
    process.exit(1);
  }
}

main();
