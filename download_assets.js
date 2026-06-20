const https = require('https');
const fs = require('fs');
const path = require('path');

function downloadFile(url, destPath) {
  return new Promise((resolve, reject) => {
    https.get(url, (res) => {
      if (res.statusCode === 301 || res.statusCode === 302 || res.statusCode === 307 || res.statusCode === 308) {
        downloadFile(res.headers.location, destPath).then(resolve).catch(reject);
        return;
      }
      if (res.statusCode !== 200) {
        reject(new Error(`Status code: ${res.statusCode} for URL: ${url}`));
        return;
      }
      const fileStream = fs.createWriteStream(destPath);
      res.pipe(fileStream);
      fileStream.on('finish', () => {
        fileStream.close();
        resolve();
      });
      fileStream.on('error', (err) => {
        fs.unlink(destPath, () => reject(err));
      });
    }).on('error', reject);
  });
}

async function downloadAllImages(concurrency = 25) {
  const tasks = [];
  for (let i = 1; i <= 604; i++) {
    const pageStr = String(i).padStart(3, '0');
    const imgUrl = `https://raw.githubusercontent.com/GovarJabbar/Quran-PNG/master/${pageStr}.png`;
    const destPath = path.join(__dirname, 'assets', 'mushaf', `${pageStr}.png`);
    tasks.push({ url: imgUrl, dest: destPath, id: i });
  }

  let index = 0;
  async function worker() {
    while (index < tasks.length) {
      const task = tasks[index++];
      if (!task) break;
      
      // If file already exists and has size > 10KB, skip download
      try {
        if (fs.existsSync(task.dest)) {
          const stats = fs.statSync(task.dest);
          if (stats.size > 10 * 1024) {
            continue;
          }
        }
      } catch (e) {}

      let retries = 3;
      while (retries > 0) {
        try {
          await downloadFile(task.url, task.dest);
          console.log(`Downloaded image ${task.id}/604`);
          break;
        } catch (err) {
          retries--;
          if (retries === 0) {
            console.error(`Failed to download image ${task.id}:`, err.message);
            throw err;
          }
          await new Promise(r => setTimeout(r, 1000));
        }
      }
    }
  }

  const workers = Array(concurrency).fill(null).map(() => worker());
  await Promise.all(workers);
}

async function downloadModel() {
  const modelUrl = 'https://huggingface.co/B1uqa/whisper-base-ar-quran-ggml/resolve/main/ggml-model.bin';
  const destPath = path.join(__dirname, 'assets', 'ggml-model.bin');
  
  // Skip if already exists and is large enough
  if (fs.existsSync(destPath)) {
    const stats = fs.statSync(destPath);
    if (stats.size > 100 * 1024 * 1024) {
      console.log('Model file already exists. Skipping download.');
      return;
    }
  }

  console.log('Downloading model file (148MB)...');
  await downloadFile(modelUrl, destPath);
  console.log('Model file downloaded successfully!');
}

function generateImagesIndex() {
  const indexPath = path.join(__dirname, 'assets', 'quran-images.js');
  let content = 'export const quranImages = {\n';
  for (let i = 1; i <= 604; i++) {
    const pageStr = String(i).padStart(3, '0');
    content += `  ${i}: require('./mushaf/${pageStr}.png'),\n`;
  }
  content += '};\n';
  fs.writeFileSync(indexPath, content);
  console.log('Generated assets/quran-images.js');
}

async function main() {
  try {
    // Ensure assets and assets/mushaf directories exist
    const assetsDir = path.join(__dirname, 'assets');
    const mushafDir = path.join(assetsDir, 'mushaf');
    if (!fs.existsSync(assetsDir)) {
      fs.mkdirSync(assetsDir);
    }
    if (!fs.existsSync(mushafDir)) {
      fs.mkdirSync(mushafDir);
    }

    console.log('Starting assets pre-download and setup...');
    await downloadModel();
    console.log('Downloading Quran images...');
    await downloadAllImages(30); // 30 concurrent downloads
    console.log('Generating code files...');
    generateImagesIndex();
    console.log('Assets setup completed successfully!');
  } catch (err) {
    console.error('Fatal error setting up assets:', err);
    process.exit(1);
  }
}

main();
