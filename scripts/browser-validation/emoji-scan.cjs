// 웹 UI 소스(시험 제외)에 이모티콘·딩뱃·화살표 글자를 UI 아이콘으로 쓴 곳이 없는지 검사한다.
// 사용법: node scripts/browser-validation/emoji-scan.cjs [dir...]  (기본 web-ui/src). 발견하면 종료 코드 1.
// 가운뎃점(·)은 한국어 문장 부호라 허용하지만, 가운뎃점만으로 된 글자(예: "···")는 아이콘으로 보고 잡는다.
const fs = require('node:fs');
const path = require('node:path');
const roots = process.argv.slice(2).length ? process.argv.slice(2) : [path.join(__dirname, '../../web-ui/src')];
const pictographic = /[\p{Extended_Pictographic}\u{2190}-\u{21FF}\u{2300}-\u{23FF}\u{2460}-\u{24FF}\u{25A0}-\u{27BF}\u{2900}-\u{297F}\u{2B00}-\u{2BFF}\u{2039}\u{203A}\u{2022}\u{2713}-\u{2718}\u{FE0F}\u{20E3}\u{1F1E6}-\u{1F1FF}]/u;
const dotsOnly = /(['">`]|content:\s*")\s*[\u00B7\u2022.]{3}\s*(['"<`])/;
const hits = [];
const walk = dir => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) { walk(file); continue; }
    if (!/\.(tsx?|css|html)$/.test(entry.name) || /\.test\.tsx?$/.test(entry.name)) continue;
    fs.readFileSync(file, 'utf8').split(/\r?\n/).forEach((line, i) => {
      // \u{...} 이스케이프는 소스 글자가 아니므로 허용한다(스티커 대응표).
      const m = line.match(pictographic) || line.match(dotsOnly);
      if (m) hits.push(`${path.relative(process.cwd(), file)}:${i + 1}: ${JSON.stringify(m[0])} ${line.trim().slice(0, 100)}`);
    });
  }
};
roots.forEach(walk);
console.log(hits.length ? hits.join('\n') : 'emoji/dingbat scan: 0 hits');
console.log(`files scanned under: ${roots.map(r => path.relative(process.cwd(), r) || '.').join(', ')}`);
process.exit(hits.length ? 1 : 0);
