/**
 * 文案树与语言类型的共享定义。
 *
 * 单独成文件是为了避免循环依赖：`locales/*.ts` 需要 `MessageTree`，
 * 而 `index.ts` 又要动态 import 那些文件。类型放中间层，两边都只依赖它。
 */
export type Locale = 'zh-CN' | 'en-US'

export interface MessageTree {
  [key: string]: string | MessageTree
}
