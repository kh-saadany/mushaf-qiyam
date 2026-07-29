const fs = require('fs');
const path = require('path');
const https = require('https');

const assetsDir = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
if (!fs.existsSync(assetsDir)) {
    fs.mkdirSync(assetsDir, { recursive: true });
}

function downloadFile(url, dest) {
    return new Promise((resolve, reject) => {
        const file = fs.createWriteStream(dest);
        https.get(url, (response) => {
            if ([301, 302, 307, 308].includes(response.statusCode)) {
                return downloadFile(response.headers.location, dest).then(resolve).catch(reject);
            }
            if (response.statusCode !== 200) {
                return reject(new Error(`Failed to download: ${response.statusCode}`));
            }
            response.pipe(file);
            file.on('finish', () => {
                file.close(resolve);
            });
        }).on('error', (err) => {
            fs.unlink(dest, () => reject(err));
        });
    });
}

async function main() {
    try {
        console.log("Downloading Silero VAD...");
        await downloadFile("https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx", path.join(assetsDir, "silero_vad.onnx"));
        console.log("VAD downloaded.");

        console.log("Downloading tokenizer.json...");
        await downloadFile("https://huggingface.co/onnx-community/moonshine-tiny-ar-ONNX/resolve/main/tokenizer.json", path.join(assetsDir, "tokenizer.json"));
        console.log("Tokenizer downloaded.");

        console.log("Downloading encoder_model.onnx...");
        await downloadFile("https://huggingface.co/onnx-community/moonshine-tiny-ar-ONNX/resolve/main/onnx/encoder_model.onnx", path.join(assetsDir, "encoder_model.onnx"));
        console.log("Encoder downloaded.");

        console.log("Downloading decoder_model_merged.onnx...");
        await downloadFile("https://huggingface.co/onnx-community/moonshine-tiny-ar-ONNX/resolve/main/onnx/decoder_model_merged.onnx", path.join(assetsDir, "decoder_model_merged.onnx"));
        console.log("Decoder downloaded.");

        console.log("All models downloaded successfully.");
    } catch (err) {
        console.error("Error:", err);
    }
}

main();
