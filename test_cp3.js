const fs = require('fs');
fs.mkdirSync('test_src3/android', {recursive:true});
fs.writeFileSync('test_src3/android/test.txt', 'hello');
fs.cpSync('test_src3', 'test_dest3', {recursive:true, force:true});
console.log('success');
