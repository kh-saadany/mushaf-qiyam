// search_oob.js
function test(inputLen, outputLen) {
    const ratio = Math.fround(inputLen / outputLen);
    const i = outputLen - 1;
    const index = Math.fround(i * ratio);
    const low = Math.floor(index);
    if (low >= inputLen) {
        console.log(`FOUND! inputLen=${inputLen}, outputLen=${outputLen}, i=${i}, index=${index}, low=${low}`);
        return true;
    }
    return false;
}

console.log("Searching...");
let count = 0;
for (let inputLen = 2; inputLen < 100000; inputLen++) {
    for (let outputLen = 2; outputLen < 100000; outputLen++) {
        if (test(inputLen, outputLen)) {
            count++;
            if (count > 5) process.exit(0);
        }
    }
}
console.log("Finished searching.");
