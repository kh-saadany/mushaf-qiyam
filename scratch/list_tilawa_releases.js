const https = require('https');

const options = {
    hostname: 'api.github.com',
    path: '/repos/yazinsai/tilawa/releases',
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
                    r.assets.forEach(a => {
                        console.log(`  - ${a.name}: ${a.browser_download_url} (${(a.size/1024/1024).toFixed(2)} MB)`);
                    });
                }
            });
        } catch(e) {
            console.error(e);
        }
    });
});
