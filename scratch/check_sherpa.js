const https = require('https');

const options = {
    hostname: 'api.github.com',
    path: '/repos/k2-fsa/sherpa-onnx/releases/latest',
    headers: { 'User-Agent': 'Node.js' }
};

https.get(options, (res) => {
    let data = '';
    res.on('data', chunk => data += chunk);
    res.on('end', () => {
        try {
            const release = JSON.parse(data);
            console.log("Tag:", release.tag_name);
            console.log("Assets:");
            if (release.assets) {
                release.assets.filter(a => a.name.includes('android') || a.name.includes('aar')).forEach(a => {
                    console.log(`- ${a.name}: ${a.browser_download_url}`);
                });
            }
        } catch(e) {
            console.error(e);
        }
    });
});
