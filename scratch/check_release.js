const https = require('https');

const options = {
    hostname: 'api.github.com',
    path: '/repos/kh-saadany/mushaf-qiyam/releases/tags/latest-native-android',
    headers: { 'User-Agent': 'Node.js' }
};

https.get(options, (res) => {
    let data = '';
    res.on('data', chunk => data += chunk);
    res.on('end', () => {
        try {
            const rel = JSON.parse(data);
            console.log("Release Tag:", rel.tag_name);
            console.log("Published At:", rel.published_at);
            if (rel.assets) {
                rel.assets.forEach(a => {
                    console.log(`Asset: ${a.name} | URL: ${a.browser_download_url} | Size: ${(a.size/1024/1024).toFixed(2)} MB`);
                });
            }
        } catch(e) {
            console.error(e);
        }
    });
});
