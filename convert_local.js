const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

async function processLocalFiles() {
  const mushafDir = path.join(__dirname, 'assets', 'mushaf');
  const files = fs.readdirSync(mushafDir).filter(f => f.endsWith('.webp'));
  
  console.log(`Found ${files.length} .webp files. Starting conversion...`);
  
  let converted = 0;
  for (const file of files) {
    const webpPath = path.join(mushafDir, file);
    const pngPath = path.join(mushafDir, file.replace('.webp', '.png'));
    
    // Resize to 1200 and 16 colors
    await sharp(webpPath)
      .resize({ width: 1200 })
      .png({ palette: true, colors: 16 })
      .toFile(pngPath);
      
    // Delete old webp
    fs.unlinkSync(webpPath);
    
    converted++;
    if (converted % 50 === 0) {
      console.log(`Converted ${converted}/${files.length} files`);
    }
  }
  
  console.log('Conversion complete!');
}

processLocalFiles().catch(console.error);
