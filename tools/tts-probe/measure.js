// Per phrase: sha256 of the WAV (first 12 hex digits), seconds of speech, the phrase.
// Speech = from the first to the last 10 ms frame louder than -45 dBFS.
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const [phrasesFile, dir] = process.argv.slice(2);
const phrases = fs.readFileSync(phrasesFile, 'utf8').split(/\r?\n/).filter(line => line.length);

function speechSeconds(wav) {
	const rate = wav.readUInt32LE(24);
	let offset = 12;
	while (offset < wav.length - 8) {
		const id = wav.toString('ascii', offset, offset + 4);
		const length = wav.readUInt32LE(offset + 4);
		if (id === 'data') {
			const samples = Math.floor(Math.min(length, wav.length - offset - 8) / 2);
			const frame = Math.round(rate / 100);
			let first = -1;
			let last = -1;
			for (let f = 0; f * frame < samples; f++) {
				let sum = 0;
				let count = 0;
				for (let k = f * frame; k < Math.min(samples, (f + 1) * frame); k++) {
					const v = wav.readInt16LE(offset + 8 + 2 * k) / 32768;
					sum += v * v;
					count++;
				}
				if (10 * Math.log10(sum / count + 1e-12) > -45) {
					if (first < 0) first = f;
					last = f;
				}
			}
			return first < 0 ? 0 : (last - first + 1) / 100;
		}
		offset += 8 + length;
	}
	return 0;
}

phrases.forEach((phrase, i) => {
	const wav = fs.readFileSync(path.join(dir, String(i).padStart(2, '0') + '.wav'));
	const sha = crypto.createHash('sha256').update(wav).digest('hex').slice(0, 12);
	console.log(`${sha}  ${speechSeconds(wav).toFixed(2)} s  ${phrase}`);
});
