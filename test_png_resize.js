const sharp = require('sharp');
const path = require('path');
const fs = require('fs');

async function test() {
  const inputFile = path.join(__dirname, 'assets', 'mushaf', '003.webp');
  const outputFile = path.join(__dirname, 'test_003.png');
  
  await sharp(inputFile)
    .resize({ width: 1200 })
    .png({ palette: true, colors: 16 })
    .toFile(outputFile);
    
  console.log('Result size:', fs.statSync(outputFile).size);
}
test();
