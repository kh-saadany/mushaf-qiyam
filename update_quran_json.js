const fs = require('fs');
const path = require('path');

const normalizeArabic = (text) => {
    if (!text) return '';
    return text
      .replace(/[\u064B-\u0652]/g, '')
      .replace(/[\u0653-\u0655]/g, '')
      .replace(/[\u0656-\u065F\u0670]/g, '')
      .replace(/[\u0610-\u061A]/g, '')
      .replace(/[\u06D6-\u06ED]/g, '')
      .replace(/\u0640/g, '')
      .replace(/[أإآ\u0671]/g, 'ا')
      .replace(/ى/g, 'ي')
      .replace(/ة/g, 'ه')
      .replace(/[\u06DE\u06E9\uFDFA\uFDFB\uFDFC]/g, '')
      .replace(/[^\u0621-\u064A\s]/g, '')
      .replace(/\s+/g, ' ')
      .trim();
};

const filePath = path.join(__dirname, 'assets', 'quran-pages.json');
const data = JSON.parse(fs.readFileSync(filePath, 'utf8'));

data.forEach(page => {
  page.verses.forEach(verse => {
    verse.cleanText = normalizeArabic(verse.text);
  });
});

fs.writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf8');
console.log('quran-pages.json updated with cleanText!');
