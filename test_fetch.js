const https = require('https');

function checkUrl(url) {
  return new Promise((resolve) => {
    https.get(url, (res) => {
      console.log(`URL: ${url}`);
      console.log(`Status Code: ${res.statusCode}`);
      console.log(`Headers:`, res.headers);
      resolve(res.statusCode);
    }).on('error', (err) => {
      console.error(`Error for ${url}:`, err.message);
      resolve(500);
    });
  });
}

async function start() {
  await checkUrl('https://kh-saadany.github.io/mushaf-qiyam/whisper-worker.js');
  await checkUrl('https://kh-saadany.github.io/mushaf-qiyam/transformers.min.js');
  await checkUrl('https://kh-saadany.github.io/mushaf-qiyam/ort-wasm-simd-threaded.jsep.wasm');
}

start();
