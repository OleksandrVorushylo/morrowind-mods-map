import { cp, mkdir, rm, writeFile } from 'node:fs/promises'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const stage = join(root, 'android-assets')
const data = join(stage, 'data')
const base = new URL('https://tesmap.tesall.club/data/')

async function request(path, method = 'GET') {
  const url = new URL(path, base)
  if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) throw new Error(`Invalid data URL: ${path}`)
  const response = await fetch(url, { method, redirect: 'error', cache: 'no-store', headers: { 'accept-encoding': 'identity' }, signal: AbortSignal.timeout(30000) })
  if (!response.ok) throw new Error(`Could not download ${path}: ${response.status}`)
  return response
}

async function save(path, text) {
  const target = resolve(data, path)
  if (!target.startsWith(data + sep)) throw new Error(`Invalid data path: ${path}`)
  await mkdir(dirname(target), { recursive: true })
  await writeFile(target, text)
}

await rm(stage, { recursive: true, force: true })
await cp(join(root, 'public'), stage, { recursive: true, filter: (source) => source !== join(root, 'public', 'data') })
await mkdir(data, { recursive: true })
const response = await request('manifest.json')
const etag = response.headers.get('etag')
const manifestText = await response.text()
const manifest = JSON.parse(manifestText)
if (!etag || !Number.isSafeInteger(manifest.build) || !Array.isArray(manifest.sets)) throw new Error('Invalid map manifest or missing ETag')
for (const set of manifest.sets) {
  if (!set.locations) continue
  const text = await (await request(set.locations)).text()
  const parsed = JSON.parse(text)
  if (!Array.isArray(parsed.settlements) || !Array.isArray(parsed.entrances) || !Array.isArray(parsed.regions)) throw new Error(`Invalid locations: ${set.id}`)
  await save(set.locations, text)
  console.log(`Locations: ${set.id}`)
}
if (manifest.transport) {
  const text = await (await request(manifest.transport)).text()
  const index = JSON.parse(text)
  if (index.schemaVersion !== 2 || !Array.isArray(index.sets)) throw new Error('Unsupported transport data version')
  await save(manifest.transport, text)
  const prefix = manifest.transport.slice(0, manifest.transport.lastIndexOf('/') + 1)
  for (const id of index.sets) {
    if (!/^[a-z0-9_-]+$/.test(id)) throw new Error(`Invalid transport set: ${id}`)
    const path = `${prefix}${id}.json`
    const contents = await (await request(path)).text()
    const group = JSON.parse(contents)
    if (!Array.isArray(group.actors) || !Array.isArray(group.references)) throw new Error(`Invalid transport data: ${id}`)
    await save(path, contents)
    console.log(`Transport: ${id}`)
  }
}
const current = await request('manifest.json', 'HEAD')
if (current.headers.get('etag') !== etag) throw new Error('The map changed during preparation; run the build again')
await save('manifest.json', manifestText)
await save('snapshot.json', JSON.stringify({ build: manifest.build, etag }))
console.log(`Prepared map build ${manifest.build}; tiles are downloaded when viewed.`)
