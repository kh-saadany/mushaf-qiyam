const https = require('https');
const fs = require('fs');
const execSync = require('child_process').execSync;
const path = require('path');

let token = process.env.GITHUB_TOKEN;
if (!token) {
    try {
        const remote = execSync('git remote get-url origin').toString().trim();
        const match = remote.match(/https:\/\/[^:]+:([^@]+)@/);
        if (match) {
            token = match[1];
        }
    } catch (e) {
        // Fallback or ignore
    }
}
const headers = { 'Authorization': `token ${token}`, 'User-Agent': 'Node.js', 'Accept': 'application/vnd.github.v3+json' };

https.get({ hostname: 'api.github.com', path: '/repos/kh-saadany/mushaf-qiyam/actions/artifacts', headers }, res => {
    let data = '';
    res.on('data', d => data += d);
    res.on('end', () => {
        const artifacts = JSON.parse(data).artifacts;
        const apkArtifact = artifacts.find(a => a.name === 'mushaf-qiyam-apk');
        if (!apkArtifact) return console.error('Artifact not found');

        const zipPath = path.join(__dirname, 'artifact.zip');
        const file = fs.createWriteStream(zipPath);
        console.log('Downloading artifact...');
        https.get({ hostname: 'api.github.com', path: `/repos/kh-saadany/mushaf-qiyam/actions/artifacts/${apkArtifact.id}/zip`, headers }, res2 => {
            if (res2.statusCode === 302) {
                https.get(res2.headers.location, res3 => {
                    res3.pipe(file);
                    file.on('finish', () => {
                        file.close();
                        console.log('Zip downloaded. Extracting...');
                        try {
                          execSync('powershell Expand-Archive -Force -Path artifact.zip -DestinationPath apk_folder');
                        } catch(e) { console.error('Unzip error:', e.message); }
                        
                        const apkPath = path.join(__dirname, 'apk_folder', 'mushaf-qiyam.apk');
                        console.log('Uploading to gofile...');
                        
                        // 1. Get gofile server
                        const serverInfo = execSync('curl.exe -s https://api.gofile.io/servers').toString();
                        const server = JSON.parse(serverInfo).data.servers[0].name;
                        
                        // 2. Upload
                        const out = execSync(`curl.exe -s -F "file=@${apkPath}" https://${server}.gofile.io/contents/uploadfile`).toString();
                        const link = JSON.parse(out).data.downloadPage;
                        console.log('Gofile link:', link);
                        
                        // 3. Generate QR
                        const qrPath = path.join('C:\\Users\\Khaled El_Saadany\\.gemini\\antigravity\\brain\\94762066-497a-4788-8685-8bd5d01e6b16', 'qr_apk.png');
                        const qrFile = fs.createWriteStream(qrPath);
                        https.get(`https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=${encodeURIComponent(link)}`, res4 => {
                            res4.pipe(qrFile);
                            qrFile.on('finish', () => {
                                qrFile.close();
                                console.log('QR Code generated at', qrPath);
                            });
                        });
                    });
                });
            } else {
                console.error('Expected 302, got', res2.statusCode);
            }
        });
    });
});
