const QRCode = require('qrcode');
const path = require('path');

const url = "https://github.com/kh-saadany/mushaf-qiyam/releases/download/v4.5.0/mushaf-qiyam-lite.apk";
const dest = path.join("C:\\Users\\Khaled El_Saadany\\.gemini\\antigravity\\brain\\94762066-497a-4788-8685-8bd5d01e6b16", "v4_release_qr.png");

QRCode.toFile(dest, url, {
  errorCorrectionLevel: 'H',
  width: 400
}, function (err) {
  if (err) throw err;
  console.log('QR Code generated at', dest);
});
