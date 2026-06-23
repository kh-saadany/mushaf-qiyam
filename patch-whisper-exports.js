const fs = require('fs');
const path = require('path');

const pkgPath = path.join(__dirname, 'node_modules', 'whisper.rn', 'package.json');

if (fs.existsSync(pkgPath)) {
  try {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
    
    if (pkg.exports) {
      console.log('Patching whisper.rn package.json exports...');
      pkg.exports = {
        "./realtime-transcription": {
          "react-native": "./src/realtime-transcription/index.ts",
          "import": "./lib/module/realtime-transcription/index.js",
          "require": "./lib/commonjs/realtime-transcription/index.js",
          "types": "./lib/typescript/realtime-transcription/index.d.ts"
        },
        "./realtime-transcription/adapters/AudioPcmStreamAdapter": {
          "react-native": "./src/realtime-transcription/adapters/AudioPcmStreamAdapter.ts",
          "import": "./lib/module/realtime-transcription/adapters/AudioPcmStreamAdapter.js",
          "require": "./lib/commonjs/realtime-transcription/adapters/AudioPcmStreamAdapter.js",
          "types": "./lib/typescript/realtime-transcription/adapters/AudioPcmStreamAdapter.d.ts"
        },
        "./*": pkg.exports["./*"] || {
          "import": "./lib/module/*",
          "require": "./lib/commonjs/*",
          "types": "./lib/typescript/*",
          "react-native": "src/*"
        }
      };
      fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2), 'utf8');
      console.log('whisper.rn exports patched successfully!');
    }
  } catch (err) {
    console.error('Failed to patch whisper.rn exports:', err.message);
  }
} else {
  console.log('whisper.rn not found in node_modules, skipping patch.');
}
