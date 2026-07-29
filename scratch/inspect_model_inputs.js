const fs = require('fs');
const path = require('path');

// Read export_metadata.json if downloaded, or inspect model
const metadataPath = path.join(__dirname, '../app/src/main/assets/tilawa_model/export_metadata.json');
if (fs.existsSync(metadataPath)) {
  console.log('Metadata:', fs.readFileSync(metadataPath, 'utf8'));
} else {
  console.log('Metadata file not downloaded locally yet.');
}
