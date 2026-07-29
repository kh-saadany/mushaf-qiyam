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

https.get('https://api.github.com/repos/kh-saadany/mushaf-qiyam/actions/runs/30361381640/jobs', opts, res => {
    let d = '';
    res.on('data', c => d += c);
    res.on('end', () => {
        try {
            const j = JSON.parse(d);
            const job = j.jobs[0];
            const jobId = job.id;
            console.log("Job ID:", jobId);
            
            https.get(`https://api.github.com/repos/kh-saadany/mushaf-qiyam/actions/jobs/${jobId}/logs`, opts, resLog => {
                if (resLog.statusCode === 302 || resLog.statusCode === 301) {
                    https.get(resLog.headers.location, resLog2 => {
                        let logText = '';
                        resLog2.on('data', chunk => logText += chunk);
                        resLog2.on('end', () => {
                            const lines = logText.split('\n').filter(l => l.includes('ERROR') || l.includes('FAILED') || l.includes('exception') || l.includes('Unresolved reference'));
                            console.log("Log highlights:");
                            console.log(lines.slice(-30).join('\n'));
                        });
                    });
                }
            });
        } catch(e) {
            console.error(e);
        }
    });
});
