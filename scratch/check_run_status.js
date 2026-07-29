const https = require('https');
const { execSync } = require('child_process');

let token = '';
try {
    const remote = execSync('git remote get-url origin', {cwd: 'c:/Users/Khaled El_Saadany/Desktop/webDevelopment/antigravity/مصحف القيام'}).toString().trim();
    const match = remote.match(/https:\/\/[^:]+:([^@]+)@/);
    if (match) token = match[1];
} catch(e){}

const opts = {headers:{'User-Agent':'Node.js','Accept':'application/vnd.github.v3+json'}};
if (token) opts.headers['Authorization'] = 'token ' + token;

https.get('https://api.github.com/repos/kh-saadany/mushaf-qiyam/actions/runs?per_page=5', opts, res => {
    let d = '';
    res.on('data', c => d += c);
    res.on('end', () => {
        try {
            const j = JSON.parse(d);
            if (j.workflow_runs) {
                j.workflow_runs.forEach(r => {
                    console.log(`Run ID: ${r.id} | Status: ${r.status} | Conclusion: ${r.conclusion} | Commit: ${r.head_commit.message}`);
                });
            } else {
                console.log(j);
            }
        } catch(e) {
            console.error(e);
        }
    });
});
