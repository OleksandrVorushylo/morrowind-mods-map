import { spawnSync } from 'node:child_process'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const env = { ...process.env, ANDROID_BUILD: '1' }
function run(command, args) {
  const result = spawnSync(command, args, { cwd: root, env, stdio: 'inherit' })
  if (result.error) throw result.error
  if (result.status !== 0) process.exit(result.status ?? 1)
}
run(process.execPath, ['scripts/prepare-android-data.mjs'])
run(process.execPath, ['node_modules/typescript/bin/tsc', '--noEmit'])
run(process.execPath, ['node_modules/vite/bin/vite.js', 'build'])
run(process.execPath, ['node_modules/@capacitor/cli/bin/capacitor', 'sync', 'android'])
if (process.platform === 'win32') run('cmd.exe', ['/d', '/s', '/c', 'android\\gradlew.bat -p android --no-daemon --max-workers=2 assembleDebug'])
else run(join(root, 'android', 'gradlew'), ['-p', 'android', '--no-daemon', '--max-workers=2', 'assembleDebug'])
