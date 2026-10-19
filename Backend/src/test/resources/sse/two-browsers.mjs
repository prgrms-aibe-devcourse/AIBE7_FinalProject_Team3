import { createRequire } from 'node:module'
import { resolve } from 'node:path'

const require = createRequire(resolve('../Frontend/package.json'))
const { chromium } = require('@playwright/test')
const [port, first, second] = process.argv.slice(2)
const base = `http://localhost:${port}`
const browsers = []
try {
  browsers.push(await chromium.launch({ headless: true }))
  browsers.push(await chromium.launch({ headless: true }))
  const pages = [await browsers[0].newPage(), await browsers[1].newPage(), await browsers[0].newPage()]
  await Promise.all(pages.map(page => page.goto(`${base}/actuator/health`)))
  await Promise.all(pages.map((page, index) => page.evaluate(dropId => new Promise((resolve, reject) => {
    window.received = []
    window.stream = new EventSource(`/api/v1/drops/stock-stream?dropIds=${dropId}`)
    const timeout = setTimeout(() => reject(new Error('ready timeout')), 5000)
    window.stream.addEventListener('ready', () => { clearTimeout(timeout); resolve() })
    window.stream.addEventListener('drop-changed', event => window.received.push(JSON.parse(event.data)))
  }), index === 2 ? second : first)))
  console.log('READY')
  await Promise.all(pages.slice(0, 2).map(page => page.waitForFunction(() => window.received.length === 1, null, { timeout: 5000 })))
  const results = await Promise.all(pages.map(page => page.evaluate(() => window.received)))
  for (const events of results.slice(0, 2)) {
    if (JSON.stringify(events) !== JSON.stringify([{ dropId: Number(first), changes: ['STOCK'] }])) {
      throw new Error(`unexpected signal: ${JSON.stringify(events)}`)
    }
  }
  if (results[2].length !== 0) throw new Error('unrelated subscriber received a signal')
  await Promise.all(pages.map(page => page.evaluate(() => window.stream.close())))
  console.log('PASS: two independent browsers received committed STOCK; unrelated DROP received none')
} finally {
  await Promise.all(browsers.map(browser => browser.close()))
}
