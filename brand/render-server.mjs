// Renders the app artwork into every icon size the APK and the web console need.
//
// Why a browser instead of an image library: this host has no SVG rasteriser and no Pillow, and
// the browser canvas already does high-quality downscaling. Serving the source over loopback keeps
// the page free of file:// restrictions.
//
// Usage: node render-server.mjs
// Then open http://127.0.0.1:8791/ and wait for the title to become DONE.

import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// Resolved from this file so the pipeline works in whichever project it is copied into.
const BRAND_DIR = path.dirname(fileURLToPath(import.meta.url))
const SOURCE = path.join(BRAND_DIR, 'source.png')
const OUTPUT_DIR = path.join(BRAND_DIR, 'out')
const PORT = 8791

// Sizes the APK adaptive icon needs. Adaptive layers are 108dp, and the largest common density is
// 4x, so 432px is the biggest useful layer bitmap.
const APK_JOBS = [{ name: 'apk-foreground-432.png', size: 432 }]

// Sizes the web console needs: the tab icon, the apple-touch icons, and the eight sizes its
// manifest declares for installable use on a phone.
const WEB_JOBS = [
  { name: 'favicon-64.png', size: 64 },
  { name: 'favicon-128.png', size: 128 },
  { name: 'icon-72x72.png', size: 72 },
  { name: 'icon-96x96.png', size: 96 },
  { name: 'icon-128x128.png', size: 128 },
  { name: 'icon-144x144.png', size: 144 },
  { name: 'icon-152x152.png', size: 152 },
  { name: 'icon-192x192.png', size: 192 },
  { name: 'icon-384x384.png', size: 384 },
  { name: 'icon-512x512.png', size: 512 },
]

// Not installed anywhere. These exist so a human can judge the result before it ships: the masked
// previews show what a launcher crops away, and the small sizes show whether the face survives.
const PREVIEW_JOBS = [
  { name: 'preview-circle-512.png', size: 512, mask: 'circle' },
  { name: 'preview-rounded-512.png', size: 512, mask: 'rounded' },
  { name: 'preview-48.png', size: 48 },
  { name: 'preview-192-masked.png', size: 192, mask: 'circle' },
]

// The console draws its own logo in the sidebar, on the login card and on the loading screen. It
// keeps the rounded-square shape the previous blue bubble had, so the swap reads as a new logo
// rather than a broken image.
const LOGO_JOBS = [32, 64, 128, 256, 512].map((size) => ({
  name: `logo-${size}.png`,
  size,
  round: true,
}))

const jobs = [...APK_JOBS, ...WEB_JOBS, ...LOGO_JOBS, ...PREVIEW_JOBS]

const page = `<!doctype html>
<meta charset="utf-8">
<title>rendering</title>
<body style="font:14px system-ui;padding:16px">
<p id="status">loading artwork…</p>
<script>
const jobs = ${JSON.stringify(jobs)};

// The share of the 108dp adaptive layer that a launcher is guaranteed to keep. The visible mask is
// a shape inscribed in the square whose diameter is about this much.
const SAFE_FRACTION = 0.72;

function drawMasked(context, size, mask) {
  const radius = size * SAFE_FRACTION / 2;
  context.save();
  context.beginPath();
  context.rect(0, 0, size, size);
  if (mask === 'circle') {
    context.arc(size / 2, size / 2, radius, 0, Math.PI * 2);
  } else {
    // A squircle is close enough to the shapes launchers actually use.
    context.roundRect(size / 2 - radius, size / 2 - radius, radius * 2, radius * 2, radius * 0.45);
  }
  context.fillStyle = '#c9ccd4';
  context.fill('evenodd');
  context.restore();
}

async function render() {
  const artwork = new Image();
  artwork.src = '/src.png';
  await artwork.decode();

  const probe = document.createElement('canvas');
  probe.width = artwork.naturalWidth;
  probe.height = artwork.naturalHeight;
  const probeContext = probe.getContext('2d', { willReadFrequently: true });
  probeContext.drawImage(artwork, 0, 0);
  const pixels = probeContext.getImageData(0, 0, probe.width, probe.height).data;

  // Averaged over the corners, because that is what sits behind the character and therefore what a
  // launcher shows wherever the artwork does not reach.
  const patch = 24;
  let red = 0, green = 0, blue = 0, counted = 0;
  const corners = [
    [0, 0], [probe.width - patch, 0],
    [0, probe.height - patch], [probe.width - patch, probe.height - patch],
  ];
  for (const [originX, originY] of corners) {
    for (let y = originY; y < originY + patch; y++) {
      for (let x = originX; x < originX + patch; x++) {
        const at = (y * probe.width + x) * 4;
        red += pixels[at]; green += pixels[at + 1]; blue += pixels[at + 2]; counted++;
      }
    }
  }
  const background = [Math.round(red / counted), Math.round(green / counted), Math.round(blue / counted)];
  await fetch('/note', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ background, sourceWidth: probe.width, sourceHeight: probe.height }),
  });

  for (const job of jobs) {
    document.getElementById('status').textContent = job.name;
    const canvas = document.createElement('canvas');
    canvas.width = job.size;
    canvas.height = job.size;
    const context = canvas.getContext('2d');
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = 'high';
    if (job.round) {
      // The console's previous logo was a rounded square, so the artwork is clipped to the same
      // shape and the swap reads as a new logo rather than a pasted-in rectangle.
      context.beginPath();
      context.roundRect(0, 0, job.size, job.size, job.size * 0.25);
      context.clip();
    }
    context.drawImage(artwork, 0, 0, job.size, job.size);
    if (job.mask) drawMasked(context, job.size, job.mask);
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
    await fetch('/save?name=' + encodeURIComponent(job.name), { method: 'POST', body: blob });
  }

  // The console links its tab icon as SVG, so the SVG has to keep working. It embeds the raster
  // artwork rather than tracing it, which is the only option without a vector source.
  const wrapper = document.createElement('canvas');
  wrapper.width = 128;
  wrapper.height = 128;
  wrapper.getContext('2d').drawImage(artwork, 0, 0, 128, 128);
  const embedded = wrapper.toDataURL('image/png');
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" width="128" height="128">'
    + '<image width="128" height="128" href="' + embedded + '"/></svg>';
  await fetch('/save?name=' + encodeURIComponent('favicon.svg'), {
    method: 'POST',
    headers: { 'content-type': 'image/svg+xml' },
    body: svg,
  });

  // The console also references /logo.svg for the logo it draws inside the page. Same treatment as
  // the tab icon, but clipped to the rounded square the old logo used.
  const logoSource = document.createElement('canvas');
  logoSource.width = 128;
  logoSource.height = 128;
  const logoContext = logoSource.getContext('2d');
  logoContext.beginPath();
  logoContext.roundRect(0, 0, 128, 128, 32);
  logoContext.clip();
  logoContext.drawImage(artwork, 0, 0, 128, 128);
  const logoEmbedded = logoSource.toDataURL('image/png');
  const logoSvg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" width="128" height="128">'
    + '<defs><clipPath id="logoRound"><rect width="128" height="128" rx="32" ry="32"/></clipPath></defs>'
    + '<image width="128" height="128" clip-path="url(#logoRound)" href="' + logoEmbedded + '"/></svg>';
  await fetch('/save?name=' + encodeURIComponent('logo.svg'), {
    method: 'POST',
    headers: { 'content-type': 'image/svg+xml' },
    body: logoSvg,
  });

  document.title = 'DONE';
  document.getElementById('status').textContent = 'done';
}

render().catch((error) => {
  document.title = 'ERROR: ' + error;
  document.getElementById('status').textContent = String(error);
});
</script>
</body>`;

/** @returns {void} */
function serveSource(response) {
  fs.createReadStream(SOURCE).pipe(response)
}

/** Collects a request body into one buffer. */
function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = []
    request.on('data', (chunk) => chunks.push(chunk))
    request.on('end', () => resolve(Buffer.concat(chunks)))
    request.on('error', reject)
  })
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, `http://127.0.0.1:${PORT}`)
  if (url.pathname === '/') {
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
    response.end(page)
    return
  }
  if (url.pathname === '/src.png') {
    response.writeHead(200, { 'content-type': 'image/png' })
    serveSource(response)
    return
  }
  if (url.pathname === '/note' && request.method === 'POST') {
    fs.writeFileSync(path.join(OUTPUT_DIR, 'artwork-notes.json'), await readBody(request))
    response.writeHead(204).end()
    return
  }
  if (url.pathname === '/save' && request.method === 'POST') {
    // The page sends only names it was given, but the name still arrives from the network, so
    // resolve it against the output directory and refuse anything that escapes.
    const target = path.join(OUTPUT_DIR, path.basename(url.searchParams.get('name') ?? ''))
    fs.writeFileSync(target, await readBody(request))
    console.log(`wrote ${target}`)
    response.writeHead(204).end()
    return
  }
  response.writeHead(404).end()
})

fs.mkdirSync(OUTPUT_DIR, { recursive: true })
server.listen(PORT, '127.0.0.1', () => console.log(`listening on http://127.0.0.1:${PORT}/`))
