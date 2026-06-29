const fs = require('fs');
try {
  fs.mkdirSync('test_cp_arabic/مصحف/src', {recursive: true});
  fs.writeFileSync('test_cp_arabic/مصحف/src/file.txt', 'hello');
  fs.cpSync('test_cp_arabic', 'test_cp_arabic_dest', {recursive: true});
  console.log(fs.readdirSync('test_cp_arabic_dest/مصحف/src'));
} catch (e) {
  console.error(e);
}
