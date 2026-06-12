const fs = require('fs');
const https = require('https');
const path = require('path');

const url = 'https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.0.1/dist/transformers.min.js';
const dest = path.join(__dirname, 'transformers.min.js');

function downloadFile(fileUrl, destPath) {
  return new Promise((resolve, reject) => {
    https.get(fileUrl, (res) => {
      if (res.statusCode === 302 || res.statusCode === 301) {
        downloadFile(res.headers.location, destPath).then(resolve).catch(reject);
        return;
      }
      if (res.statusCode !== 200) {
        reject(new Error(`Status: ${res.statusCode}`));
        return;
      }
      const stream = fs.createWriteStream(destPath);
      res.pipe(stream);
      stream.on('finish', () => {
        stream.close();
        resolve();
      });
    }).on('error', reject);
  });
}

console.log('Downloading transformers.min.js...');
downloadFile(url, dest)
  .then(() => {
    console.log('Successfully downloaded transformers.min.js.');
    // Delete transformers.min.mjs to keep space clean
    const mjsFile = path.join(__dirname, 'transformers.min.mjs');
    if (fs.existsSync(mjsFile)) {
      fs.unlinkSync(mjsFile);
      console.log('Removed transformers.min.mjs.');
    }
  })
  .catch((err) => {
    console.error('Error:', err.message);
  });
