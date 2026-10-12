import { writeFile } from 'node:fs/promises'

const groups = [
  'category',
  'dashboard',
  'drop',
  'order',
  'payment',
  'user',
  'wish',
]
const groupedOperations = new Set()

for (const group of groups) {
  const response = await fetch(`http://localhost:8080/v3/api-docs/${group}`)
  if (!response.ok)
    throw new Error(`${group} OpenAPI 조회 실패: ${response.status}`)
  const document = await response.json()
  if (!document.openapi || !Object.keys(document.paths ?? {}).length) {
    throw new Error(`${group} OpenAPI 경로가 비어 있습니다.`)
  }
  for (const [path, methods] of Object.entries(document.paths)) {
    for (const method of Object.keys(methods)) {
      groupedOperations.add(`${method} ${path}`)
    }
  }
  await writeFile(
    new URL(`../openapi/${group}.json`, import.meta.url),
    `${JSON.stringify(document, null, 2)}\n`,
  )
}

const response = await fetch('http://localhost:8080/v3/api-docs')
if (!response.ok) throw new Error(`전체 OpenAPI 조회 실패: ${response.status}`)
const { paths } = await response.json()
const missing = Object.entries(paths).flatMap(([path, methods]) =>
  Object.keys(methods)
    .filter((method) => !groupedOperations.has(`${method} ${path}`))
    .map((method) => `${method} ${path}`),
)
if (missing.length)
  throw new Error(`도메인 그룹에서 누락된 API: ${missing.join(', ')}`)
