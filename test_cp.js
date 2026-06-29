const fs = require('fs');
try {
  fs.mkdirSync('test_src/android', {recursive: true});
  fs.writeFileSync('test_src/android/test.txt', 'hello');
  fs.cpSync('test_src', 'test_dest', {recursive: true, force: true});
  console.log(fs.readdirSync('test_dest'));
} catch (e) {
  console.error(e);
}
