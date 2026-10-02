import { readFileSync, readdirSync, statSync } from 'node:fs'
import { resolve, dirname, extname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'
import { NodeTypes, parse as parseVueTemplate } from '@vue/compiler-dom'
import { parse as parseVueSfc } from '@vue/compiler-sfc'

const scriptPath = fileURLToPath(import.meta.url)
const projectRoot = resolve(dirname(scriptPath), '..')

function collectFilesWithExtensions(dir, extensions) {
  const results = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) results.push(...collectFilesWithExtensions(full, extensions))
    else if (extensions.includes(extname(full))) results.push(full)
  }
  return results
}

function collectVueFiles(dir) {
  return collectFilesWithExtensions(dir, ['.vue'])
}

function lineNumber(source, index) {
  return source.slice(0, index).split('\n').length
}

export function stripComments(source) {
  const withoutHtmlComments = source.replace(/<!--[\s\S]*?-->/g, comment => comment.replace(/[^\r\n]/g, ' '))
  const scanner = ts.createScanner(ts.ScriptTarget.Latest, false, ts.LanguageVariant.Standard, withoutHtmlComments)
  let output = ''
  let cursor = 0
  for (let token = scanner.scan(); token !== ts.SyntaxKind.EndOfFileToken; token = scanner.scan()) {
    const start = scanner.getTokenPos()
    const end = scanner.getTextPos()
    output += withoutHtmlComments.slice(cursor, start)
    const tokenText = withoutHtmlComments.slice(start, end)
    output += token === ts.SyntaxKind.SingleLineCommentTrivia || token === ts.SyntaxKind.MultiLineCommentTrivia
      ? tokenText.replace(/[^\r\n]/g, ' ')
      : tokenText
    cursor = end
  }
  return output + withoutHtmlComments.slice(cursor)
}

const visibleLiteralAllowlist = new Set([
  'AI', 'ATS', 'CSS', 'DOCX', 'HTML', 'HTTP', 'HTTPS', 'JD', 'JSON', 'PDF', 'STAR', 'TXT', 'UI', 'URL',
  'Amazon Web Services', 'GitHub', 'Java', 'LinkedIn', 'Spring Boot',
  'px', 'v',
])

function needsTranslation(value, { expression = false } = {}) {
  const text = value.trim()
  const literalText = text.replace(/\$\{[\s\S]*\}/g, '').replace(/\{[A-Za-z0-9_]+\}/g, '').trim()
  if (!literalText || !/[\p{L}]/u.test(literalText)) return false
  if (visibleLiteralAllowlist.has(text)) return false
  if (/^(?:https?:\/\/|mailto:|tel:)/i.test(text) || /^\S+@\S+\.\S+$/.test(text)) return false
  if (/^\d{4}(?:[-/.]\d{1,2}){1,2}$/.test(text)) return false
  // 全大写 + 下划线 = 错误码/语义键（如 TASK_STATUS_UNAVAILABLE），消费方负责映射文案
  if (/^[A-Z0-9][A-Z0-9+#./_-]{1,63}$/.test(text)) return false
  if (expression && /^[a-z_$][\w$.-]*$/.test(text)) return false
  return true
}

function collectExpressionLiterals(expression) {
  const sourceFile = ts.createSourceFile('template-expression.ts', `(${expression})`, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const literals = []
  function visit(node) {
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) {
      literals.push(node.text)
      return
    }
    if (ts.isTemplateExpression(node)) {
      literals.push(node.head.text)
      for (const span of node.templateSpans) {
        visit(span.expression)
        literals.push(span.literal.text)
      }
      return
    }
    ts.forEachChild(node, visit)
  }
  visit(sourceFile)
  return literals
}

export function findVisibleLiterals(source) {
  const template = parseVueSfc(source, { filename: 'audited.vue' }).descriptor.template?.content ?? ''
  const failures = []
  if (!template) return failures
  const visibleAttributes = new Set(['alt', 'aria-label', 'placeholder', 'title'])
  const root = parseVueTemplate(template, { comments: false })

  function visit(node) {
    if (node.type === NodeTypes.TEXT && needsTranslation(node.content)) {
      failures.push(`visible template text contains hard-coded user text near "${node.content.trim().slice(0, 40)}"`)
    }
    if (node.type === NodeTypes.INTERPOLATION) {
      const expression = node.content.loc.source
      const offending = collectExpressionLiterals(expression)
        .find(literal => needsTranslation(literal, { expression: true }))
      if (offending) {
        failures.push(`template expression contains hard-coded user text "${offending.slice(0, 40)}" at template line ${node.loc.start.line}`)
      }
    }
    if (node.type === NodeTypes.ELEMENT) {
      for (const property of node.props) {
        if (property.type === NodeTypes.ATTRIBUTE && visibleAttributes.has(property.name)
            && property.value && needsTranslation(property.value.content)) {
          failures.push(`visible attribute contains hard-coded user text at template line ${property.loc.start.line}`)
        }
        if (property.type === NodeTypes.DIRECTIVE && property.name === 'bind'
            && property.arg?.type === NodeTypes.SIMPLE_EXPRESSION && property.arg.isStatic
            && visibleAttributes.has(property.arg.content) && property.exp) {
          const offending = collectExpressionLiterals(property.exp.loc.source)
            .find(literal => needsTranslation(literal, { expression: true }))
          if (offending) {
            failures.push(`bound visible attribute contains hard-coded user text "${offending.slice(0, 40)}" at template line ${property.loc.start.line}`)
          }
        }
      }
    }
    if ('children' in node && Array.isArray(node.children)) node.children.forEach(visit)
    if (node.type === NodeTypes.IF) node.branches.forEach(visit)
    if (node.type === NodeTypes.IF_BRANCH) node.children.forEach(visit)
    if (node.type === NodeTypes.FOR) node.children.forEach(visit)
  }

  visit(root)
  return failures
}

export const findVisibleChinese = findVisibleLiterals

export function findRuntimeLiterals(source) {
  const executableSource = stripComments(source)
  const checks = [
    ['visible message assignment', /\b(?:error|success|warning|[A-Za-z_$][\w$]*(?:Error|Message|Status))\.value\s*=\s*(["'`])((?:(?!\1)[^\\]|\\.)*)\1/gu],
    ['window popup', /window\.(?:confirm|alert|prompt)\(\s*(["'`])((?:(?!\1)[^\\]|\\.)*)\1/gu],
  ]
  return checks.flatMap(([name, pattern]) => [...executableSource.matchAll(pattern)]
    .filter(match => needsTranslation(match[2]))
    .map(match => `${name} contains hard-coded user text at line ${lineNumber(executableSource, match.index)}`))
}

function propertyName(node) {
  if (ts.isIdentifier(node) || ts.isStringLiteral(node) || ts.isNumericLiteral(node)) return node.text
  return undefined
}

function findMessagesObject(source) {
  const sourceFile = ts.createSourceFile('index.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  for (const statement of sourceFile.statements) {
    if (!ts.isVariableStatement(statement)) continue
    for (const declaration of statement.declarationList.declarations) {
      if (ts.isIdentifier(declaration.name) && declaration.name.text === 'messages' && declaration.initializer && ts.isObjectLiteralExpression(declaration.initializer)) {
        return declaration.initializer
      }
    }
  }
  throw new Error('Unable to locate the messages object in src/i18n/index.ts')
}

function collectCatalog(object, prefix, keys, duplicates) {
  const seen = new Set()
  for (const property of object.properties) {
    if (!ts.isPropertyAssignment(property)) continue
    const name = propertyName(property.name)
    if (!name) continue
    const path = prefix ? `${prefix}.${name}` : name
    if (seen.has(name)) duplicates.push(path)
    seen.add(name)
    if (ts.isObjectLiteralExpression(property.initializer)) collectCatalog(property.initializer, path, keys, duplicates)
    else keys.add(path)
  }
}

export function inspectCatalog(source) {
  const root = findMessagesObject(source)
  const locales = new Map()
  const duplicates = []
  const seenLocales = new Set()
  for (const property of root.properties) {
    if (!ts.isPropertyAssignment(property) || !ts.isObjectLiteralExpression(property.initializer)) continue
    const locale = propertyName(property.name)
    if (!locale) continue
    if (seenLocales.has(locale)) duplicates.push(locale)
    seenLocales.add(locale)
    const keys = new Set()
    collectCatalog(property.initializer, '', keys, duplicates)
    locales.set(locale, keys)
  }
  return { locales, duplicates }
}

export function findLocaleKeyMismatches(locales, requiredLocales) {
  const allKeys = new Set(requiredLocales.flatMap(locale => [...(locales.get(locale) ?? [])]))
  return requiredLocales.flatMap(locale => [...allKeys]
    .filter(key => !locales.get(locale)?.has(key))
    .map(key => `${locale} is missing catalog key ${key}`))
}

export function collectStaticTranslationKeys(source) {
  return [...stripComments(source).matchAll(/\bt\(\s*(["'])([A-Za-z0-9_.-]+)\1/g)].map(match => match[2])
}

export function collectRegistryTranslationKeys(source) {
  return [...source.matchAll(/(?:labelKey|descriptionKey|stepKey|titleKey|descKey):\s*['"]([A-Za-z0-9_.-]+)['"]/g)]
    .map(match => match[1])
}

// 动态 t() 调用（如 errorMessage.ts 的 t(registeredKey ?? fallbackKey)）不被静态键校验覆盖：
// catalog 删键时 vue-tsc 不报（string 类型）、静态 t() 正则不匹配、运行时直接显示原始键名。
// 因此「动态键家族」必须有**静态注册表**，由本检查把注册表的每个键对账进两种语言的目录。
// 行首锚定不可省：注释行 `// 40001: 'errors.x',` 的行尾与真实键行完全相同，
// 无锚正则会把注释里的键照常解析（红判定实测假绿）——与 SourceText 的教训同源。
export function collectErrorCodeRegistryKeys(source) {
  return [...source.matchAll(/^\s*\d{5}:\s*'([A-Za-z0-9_.-]+)'\s*,?\s*$/gm)].map(match => match[1])
}

export function findDynamicKeyFailures(keys, locales, requiredLocales, { minKeys = 1 } = {}) {
  if (keys.length < minKeys) {
    return [`dynamic key registry parsed ${keys.length} keys (expected >= ${minKeys}) —— 解析可能已失效，本检查不得静默空转`]
  }
  return keys.flatMap(key => requiredLocales
    .filter(locale => !locales.get(locale)?.has(key))
    .map(locale => `missing ${locale} translation for ${key}（经动态 t() 调用消费，静态键校验覆盖不到）`))
}

function run() {
  // TC-2（2026-09-30）：审计范围从 .vue 扩展到 api/stores/composables 的 .ts ——
  // api 层硬编码中文曾 8+ 处绕过门禁。.ts 不是 SFC，findVisibleLiterals（模板检测）
  // 不适用；findRuntimeLiterals（message 赋值 / window popup）与 t() 静态键校验通用。
  const auditedVueFiles = [
    ...collectVueFiles(join(projectRoot, 'src', 'views')),
    ...collectVueFiles(join(projectRoot, 'src', 'components')),
  ]
  const auditedTsDirs = ['api', 'stores', 'composables']
  const auditedTsFiles = auditedTsDirs
    .flatMap(dir => collectFilesWithExtensions(join(projectRoot, 'src', dir), ['.ts']))
  const catalogSource = readFileSync(join(projectRoot, 'src', 'i18n', 'index.ts'), 'utf8')
  const navigationSource = readFileSync(join(projectRoot, 'src', 'navigation', 'registry.ts'), 'utf8')
  const { locales, duplicates } = inspectCatalog(catalogSource)
  const requiredLocales = ['zh-CN', 'en-US']
  const failures = duplicates.map(key => `src/i18n/index.ts: duplicate catalog key ${key}`)

  for (const locale of requiredLocales) {
    if (!locales.has(locale)) failures.push(`src/i18n/index.ts: missing locale ${locale}`)
  }
  failures.push(...findLocaleKeyMismatches(locales, requiredLocales)
    .map(message => `src/i18n/index.ts: ${message}`))

  for (const file of auditedVueFiles) {
    const source = readFileSync(file, 'utf8')
    const displayPath = relative(projectRoot, file)
    for (const message of [...findVisibleLiterals(source), ...findRuntimeLiterals(source)]) {
      failures.push(`${displayPath}: ${message}`)
    }
    for (const key of new Set(collectStaticTranslationKeys(source))) {
      for (const locale of requiredLocales) {
        if (!locales.get(locale)?.has(key)) failures.push(`${displayPath}: missing ${locale} translation for ${key}`)
      }
    }
  }

  for (const file of auditedTsFiles) {
    const source = readFileSync(file, 'utf8')
    const displayPath = relative(projectRoot, file)
    for (const message of findRuntimeLiterals(source)) {
      failures.push(`${displayPath}: ${message}`)
    }
    for (const key of new Set(collectStaticTranslationKeys(source))) {
      for (const locale of requiredLocales) {
        if (!locales.get(locale)?.has(key)) failures.push(`${displayPath}: missing ${locale} translation for ${key}`)
      }
    }
  }

  for (const key of new Set(collectRegistryTranslationKeys(navigationSource))) {
    for (const locale of requiredLocales) {
      if (!locales.get(locale)?.has(key)) failures.push(`src/navigation/registry.ts: missing ${locale} translation for ${key}`)
    }
  }

  // 动态键注册表对账（errorMessage.ts 的 t(registeredKey)）：错误文案是所有失败路径的必经出口，
  // 键缺失时用户看到的是原始键名而不是文案。ERROR_CODE_KEYS 目前 11 键，规模下限防解析失效假绿。
  const errorCodesSource = readFileSync(join(projectRoot, 'src', 'utils', 'errorCodes.ts'), 'utf8')
  const errorCodeKeys = collectErrorCodeRegistryKeys(errorCodesSource)
  for (const failure of findDynamicKeyFailures(errorCodeKeys, locales, requiredLocales, { minKeys: 10 })) {
    failures.push(`src/utils/errorCodes.ts: ${failure}`)
  }

  if (failures.length) {
    console.error(`i18n guard FAILED:\n${failures.slice(0, 60).join('\n')}`)
    if (failures.length > 60) console.error(`...and ${failures.length - 60} more`)
    process.exitCode = 1
    return
  }
  console.log(`i18n guard passed for ${auditedVueFiles.length} Vue files + ${auditedTsFiles.length} TS files and ${requiredLocales.length} locales`)
}

if (resolve(process.argv[1] ?? '') === resolve(scriptPath)) run()
