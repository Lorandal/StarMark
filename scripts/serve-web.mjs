import fs from 'node:fs/promises';
import { watch as watchDirectory } from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { assertNoLinksInPath, assertWithin, DESIGN_SOURCE, snapshotTree, syncWebDesign, WORKSPACE_ROOT } from './sync-web-design.mjs';

const MIME = new Map(Object.entries({
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.map': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.webp': 'image/webp', '.avif': 'image/avif', '.gif': 'image/gif', '.ico': 'image/x-icon',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.otf': 'font/otf',
  '.txt': 'text/plain; charset=utf-8', '.md': 'text/plain; charset=utf-8',
  '.pdf': 'application/pdf', '.zip': 'application/zip', '.staruw': 'application/zip',
  '.apk': 'application/vnd.android.package-archive', '.wasm': 'application/wasm',
  '.mp4': 'video/mp4', '.webm': 'video/webm',
}));
const ALIASES = new Map([
  ['/editor', '/editor.html'], ['/editor/', '/editor.html'],
  ['/about', '/app.html'], ['/about/', '/app.html'],
  ['/realize', '/index.html'], ['/realize/', '/index.html'],
]);

function respond(response, status, message, method, extra = {}) {
  const body = Buffer.from(message);
  response.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8', 'Content-Length': body.length, ...extra });
  response.end(method === 'HEAD' ? undefined : body);
}

export async function startPreview(options = {}) {
  const workspaceRoot = path.resolve(options.workspaceRoot || WORKSPACE_ROOT);
  const rootDir = assertWithin(workspaceRoot, options.rootDir || path.join(workspaceRoot, 'web-editor'));
  const sourceDir = path.resolve(options.sourceDir || DESIGN_SOURCE);
  const port = options.port ?? 5173;
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('Port must be an integer between 0 and 65535');
  if (options.syncOnStart !== false) await syncWebDesign({ workspaceRoot, sourceDir, targetDir: rootDir });
  // Reject a redirected tree before it becomes a web root.
  await snapshotTree(rootDir);
  const server = http.createServer(async (request, response) => {
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('Referrer-Policy', 'same-origin');
    const method = request.method;
    if (!['GET', 'HEAD'].includes(method)) return respond(response, 405, 'Method not allowed', method, { Allow: 'GET, HEAD' });
    const raw = request.url || '/';
    const queryAt = raw.indexOf('?');
    const query = queryAt < 0 ? '' : raw.slice(queryAt);
    let pathname;
    try { pathname = decodeURIComponent(queryAt < 0 ? raw : raw.slice(0, queryAt)); }
    catch { return respond(response, 400, 'Invalid URL encoding', method); }
    if (!pathname.startsWith('/') || /[\\\x00-\x1f\x7f:%]/.test(pathname)) return respond(response, 400, 'Invalid path', method);
    const segments = pathname.split('/').filter(Boolean);
    if (segments.some((part) => part === '.' || part === '..' || part.startsWith('.') || part.toLowerCase() === 'node_modules')) return respond(response, 403, 'Path is not public', method);
    const alias = ALIASES.get(pathname);
    // Canonical URLs keep the source HTML's relative assets and navigation valid.
    if (alias) return respond(response, 302, 'Redirecting', method, { Location: alias + query });
    const name = pathname === '/' ? 'index.html' : segments.join('/');
    const mime = MIME.get(path.extname(name).toLowerCase());
    if (!mime) return respond(response, 415, 'Unsupported media type', method);
    try {
      const filename = assertWithin(rootDir, path.resolve(rootDir, name));
      await assertNoLinksInPath(filename);
      const stat = await fs.lstat(filename);
      if (!stat.isFile()) return respond(response, 404, 'File not found', method);
      const real = await fs.realpath(filename);
      assertWithin(rootDir, real);
      const body = method === 'HEAD' ? null : await fs.readFile(filename);
      response.writeHead(200, { 'Content-Type': mime, 'Content-Length': body?.length ?? stat.size });
      response.end(body);
    } catch (error) {
      respond(response, error.code === 'ENOENT' || error.code === 'ENOTDIR' ? 404 : 403, error.code === 'ENOENT' || error.code === 'ENOTDIR' ? 'File not found' : 'Path is not public', method);
    }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => { server.off('error', reject); resolve(); });
  });
  let watcher;
  let timer;
  let running = false;
  let activeSync = Promise.resolve();
  let dirty = false;
  let closed = false;
  const log = options.log || console.log;
  const syncLatest = () => {
    if (closed) return;
    if (running) { dirty = true; return; }
    running = true;
    activeSync = (async () => {
      try {
        const result = await syncWebDesign({ workspaceRoot, sourceDir, targetDir: rootDir });
        if (result.changed) log(`Design synced: ${result.writes} written, ${result.deletes} removed; source unchanged: ${result.sourceUnchanged}`);
      } catch (error) { log(`Design sync stopped safely: ${error.message}`); }
      finally {
        running = false;
        if (dirty && !closed) { dirty = false; timer = setTimeout(syncLatest, 400); }
      }
    })();
    return activeSync;
  };
  if (options.watch) {
    try {
      watcher = watchDirectory(sourceDir, { recursive: true }, () => {
        clearTimeout(timer);
        timer = setTimeout(syncLatest, 400);
      });
      watcher.on('error', (error) => log(`Design watcher stopped: ${error.message}`));
    } catch (error) { await new Promise((resolve) => server.close(resolve)); throw error; }
  }
  return {
    server,
    url: `http://127.0.0.1:${server.address().port}`,
    async close() {
      closed = true;
      clearTimeout(timer);
      watcher?.close();
      await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
      await activeSync;
    },
  };
}

if (process.argv[1] && path.resolve(process.argv[1]).toLowerCase() === fileURLToPath(import.meta.url).toLowerCase()) {
  const args = process.argv.slice(2);
  const options = { watch: false, syncOnStart: true, port: 5173 };
  try {
    for (let index = 0; index < args.length; index++) {
      if (args[index] === '--watch') options.watch = true;
      else if (args[index] === '--no-sync') options.syncOnStart = false;
      else if (args[index] === '--port') options.port = Number(args[++index]);
      else throw new Error('Usage: node scripts/serve-web.mjs [--watch] [--no-sync] [--port 5173]');
    }
    const preview = await startPreview(options);
    console.log(`StarMark preview: ${preview.url} (source watch ${options.watch ? 'on' : 'off'})`);
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => preview.close().then(() => process.exit(0)));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
