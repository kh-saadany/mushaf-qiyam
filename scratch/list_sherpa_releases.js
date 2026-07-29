const https = require('https');

const options = {
    hostname: 'api.github.com',
    path: '/repos/k2-fsa/sherpa-onnx/releases?per_page=10',
    headers: { 'User-Agent': 'Node.js' }
};

https.get(options, (res) => {
    let data = '';
    res.on('data', chunk => data += chunk);
    res.on('end', () => {
        try {
            const releases = JSON.parse(data);
            releases.forEach(r => {
                console.log("Tag:", r.tag_name);
                if (r.assets) {
                    r.assets.filter(a => a.name.includes('aar') || a.name.includes('android')).forEach(a => {
                        console.log(`  - ${a.name}: ${a.browser_download_url}`);
                    });
                }
            });
        } catch(e) {
            console.error(e);
        }
    });
});
