// Compares two ways of filling the area a shrunken launcher artwork no longer covers.
//
// The question: when the artwork is scaled down so the horns survive the launcher mask, something
// has to fill the ring around it. A flat colour matches the artwork's own background only if that
// background is uniform, and the eye is good at spotting a seam there even when the averages agree.
// This draws both candidates and a magnified crop of the artwork's edge, which is where a seam would
// show.
//
// Usage: node fill-options.mjs
// Then open http://127.0.0.1:8793/ and wait for the title to become DONE.

import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// Resolved from this file so the pipeline works in whichever project it is copied into.
const BRAND_DIR = path.dirname(fileURLToPath(import.meta.url))
const SOURCE = path.join(BRAND_DIR, 'source.png')
const OUTPUT_DIR = path.join(BRAND_DIR, 'fills')
const PORT = 8793

/** The scale chosen for the launcher artwork, so the horns clear the mask. */
const SCALE = 0.667

/** Diameter of the visible mask, as a share of the 108dp layer. */
const SAFE_FRACTION = 0.72

/** The artwork's average edge colour, sampled earlier from the four corners. */
const EDGE_COLOUR = '#faf8fc'

const page = `<!doctype html>
<meta charset="utf-8">
<title>filling</title>
<body style="font:14px system-ui;padding:16px">
<p id="status">loading artwork…</p>
<script>
const SCALE = ${SCALE};
const SAFE_FRACTION = ${SAFE_FRACTION};
const EDGE_COLOUR = '${EDGE_COLOUR}';

let artwork;

function makeCanvas(size) {
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  return canvas;
}

/** Paints the full launcher layer: a filling, then the artwork scaled about the centre. */
function makeIcon(size, filling) {
  const canvas = makeCanvas(size);
  const context = canvas.getContext('2d');
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = 'high';

  if (filling === 'blur') {
    // A heavily blurred, enlarged copy of the artwork. Its colours come from the artwork itself, so
    // whatever reaches the ring continues the edge instead of meeting it.
    context.save();
    context.filter = 'blur(' + Math.round(size * 0.08) + 'px)';
    const overscan = size * 1.7;
    context.drawImage(artwork, (size - overscan) / 2, (size - overscan) / 2, overscan, overscan);
    context.restore();
  } else {
    context.fillStyle = EDGE_COLOUR;
    context.fillRect(0, 0, size, size);
  }

  const drawn = size * SCALE;
  const inset = (size - drawn) / 2;
  context.drawImage(artwork, inset, inset, drawn, drawn);
  return canvas;
}

/** Greys out whatever the launcher mask removes, so the crop is visible. */
function applyMask(canvas) {
  const size = canvas.width;
  const context = canvas.getContext('2d');
  const radius = size * SAFE_FRACTION / 2;
  context.save();
  context.beginPath();
  context.rect(0, 0, size, size);
  context.arc(size / 2, size / 2, radius, 0, Math.PI * 2);
  context.fillStyle = '#9aa0ab';
  context.fill('evenodd');
  context.restore();
  return canvas;
}

/**
 * Blows up a small region straddling the artwork's edge.
 *
 * A seam is a one or two pixel brightness step, which a launcher-sized view hides. Magnifying the
 * boundary about four times is the only way to see whether the filling actually continues the
 * artwork.
 */
function makeBoundaryZoom(size, filling) {
  const icon = makeIcon(size, filling);
  const edge = (size - size * SCALE) / 2;
  const window = size * 0.14;
  const from = Math.max(0, edge - window / 2);

  const zoom = makeCanvas(360);
  const context = zoom.getContext('2d');
  context.imageSmoothingEnabled = false;
  context.drawImage(icon, from, edge + size * 0.18, window, window, 0, 0, 360, 360);
  // A crosshair on where the artwork's own edge sits inside the crop.
  context.strokeStyle = '#e5484d';
  context.lineWidth = 2;
  context.beginPath();
  context.moveTo(180, 0);
  context.lineTo(180, 360);
  context.stroke();
  return zoom;
}

async function save(name, canvas) {
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
  await fetch('/save?name=' + encodeURIComponent(name), { method: 'POST', body: blob });
}

async function run() {
  artwork = new Image();
  artwork.src = '/src.png';
  await artwork.decode();

  for (const filling of ['flat', 'blur']) {
    await save('fill-' + filling + '-circle-384.png', applyMask(makeIcon(384, filling)));
    await save('fill-' + filling + '-circle-96.png', applyMask(makeIcon(96, filling)));
    await save('fill-' + filling + '-boundary-zoom.png', makeBoundaryZoom(384, filling));
  }

  // What actually ships as the launcher layer: no mask applied, because the launcher supplies its
  // own. 432px is 108dp at 4x, the largest density the layer needs.
  await save('apk-icon-432.png', makeIcon(432, 'blur'));

  const sheet = makeCanvas(1);
  sheet.width = 880;
  sheet.height = 620;
  const context = sheet.getContext('2d');
  context.fillStyle = '#eef0f4';
  context.fillRect(0, 0, sheet.width, sheet.height);
  context.font = '600 20px sans-serif';
  context.fillStyle = '#1c1f26';
  context.textAlign = 'center';

  context.fillText('flat edge colour', 220, 34);
  context.fillText('blurred artwork', 660, 34);

  context.drawImage(applyMask(makeIcon(300, 'flat')), 70, 54);
  context.drawImage(applyMask(makeIcon(300, 'blur')), 510, 54);

  context.drawImage(makeBoundaryZoom(384, 'flat'), 40, 380, 360, 200);
  context.drawImage(makeBoundaryZoom(384, 'blur'), 480, 380, 360, 200);

  context.font = '400 15px sans-serif';
  context.fillStyle = '#5b6270';
  context.fillText('edge magnified, red line marks the artwork boundary', 440, 606);

  await save('fill-sheet.png', sheet);

  document.title = 'DONE';
  document.getElementById('status').textContent = 'done';
}

run().catch((error) => {
  document.title = 'ERROR: ' + error;
  document.getElementById('status').textContent = String(error);
});
</script>
</body>`;

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => resolve(Buffer.concat(chunks)));
    request.on('error', reject);
  });
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1:' + PORT);
  if (url.pathname === '/') {
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    response.end(page);
    return;
  }
  if (url.pathname === '/src.png') {
    response.writeHead(200, { 'content-type': 'image/png' });
    fs.createReadStream(SOURCE).pipe(response);
    return;
  }
  if (url.pathname === '/save' && request.method === 'POST') {
    const target = path.join(OUTPUT_DIR, path.basename(url.searchParams.get('name') ?? ''));
    fs.writeFileSync(target, await readBody(request));
    response.writeHead(204).end();
    return;
  }
  response.writeHead(404).end();
});

fs.mkdirSync(OUTPUT_DIR, { recursive: true })
server.listen(PORT, '127.0.0.1', () => console.log('listening on http://127.0.0.1:' + PORT + '/'))
