// Calculates a SkyBlock networth with the SkyHelper-Networth library (MIT, https://github.com/Altpapier/SkyHelper-Networth).
//
// Run by server/app.py, never by the mod. It reads ONE json object from stdin:
//   { "member": <profile.members[uuid]>, "museum": <museum.members[uuid]> or null, "bank": <number> }
// and writes ONE json object to stdout: { "networth": <number> } or { "error": "<why>" }, then exits.
// It holds no Hypixel API key: the website downloads the profile and hands the data over.

const { ProfileNetworthCalculator, UpdateManager } = require('skyhelper-networth');

function finish(result) {
    process.stdout.write(JSON.stringify(result));
    process.exit(0);          // the library keeps timers running, so end the process ourselves
}

let input = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => (input += chunk));
process.stdin.on('end', async () => {
    try {
        const { member, museum, bank } = JSON.parse(input);
        if (!member || typeof member !== 'object') {
            finish({ error: 'no profile data' });
        }
        const bankBalance = Number.isFinite(Number(bank)) ? Number(bank) : 0;
        const calculator = new ProfileNetworthCalculator(member, museum || undefined, bankBalance);
        const result = await calculator.getNetworth({ onlyNetworth: true });
        if (!result || !Number.isFinite(result.networth)) {
            finish({ error: 'the calculator gave no number' });
        }
        finish({ networth: Math.round(result.networth) });
    } catch (error) {
        finish({ error: String((error && error.message) || error).slice(0, 200) });
    }
});

// The library checks npm for updates in the background; a one-shot process has no use for that.
try {
    new UpdateManager().disable();
} catch (ignored) {
    // not important
}
