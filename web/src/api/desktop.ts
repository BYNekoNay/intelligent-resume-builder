import { apiClient, type ApiResponse } from './client'

/** 一条本地工作区快照（ADR-012 阶段三）。 */
export interface DesktopBackupInfo {
  id: string
  fileName: string
  sizeBytes: number
  /** 形如 `20261023-101010`，不是 ISO 时间 —— 展示前需自行格式化。 */
  createdAt: string
  /** 恢复前自动生成的安全备份，列表里会被标出来。 */
  safetyBackup: boolean
}

/**
 * 恢复结果。
 *
 * <p>注意：失败时 HTTP 是 **409**，但信封里的 `code` 仍是 0 —— 真实结果在 `data.ok`。
 * 所以调用方不能用 `code !== 0` 判断失败，必须读 `ok`。
 */
export interface DesktopRestoreResult {
  restoredId: string
  safetyBackupId: string | null
  ok: boolean
  message: string
}

export type DesktopImportDecision = 'ADD' | 'SKIP' | 'CONFLICT'

export interface DesktopPreviewItem {
  /** 集合路径：顶层是集合名，嵌套记录是 `resumes.versions` / `applications.statusHistory`。 */
  collection: string
  stableId: string
  decision: DesktopImportDecision
  entityType: string
  localId: number | null
  reason: string
  localSnapshot: Record<string, unknown> | null
  importedSnapshot: Record<string, unknown> | null
}

export interface DesktopImportPreview {
  packageId: string
  added: number
  skipped: number
  conflicts: number
  unsupportedCollections: string[]
  /** 顺序即写入顺序（父记录先于其子记录），渲染时不要重排。 */
  items: DesktopPreviewItem[]
}

export interface DesktopImportResult {
  inserted: number
  updated: number
  skipped: number
  keptLocal: number
  insertedStableIds: string[]
}

/** 备份与恢复是同步长请求（恢复要「校验 + 安全备份 + 应用」三遍），全局 10s 超时会误判失败。 */
const LONG_OPERATION_TIMEOUT = 60_000

/** 能力探测用的短超时：在线版这一步会拿到 404/401，不该把首屏拖住。 */
const PROBE_TIMEOUT = 5_000

export function listDesktopBackups() {
  return apiClient.get<ApiResponse<DesktopBackupInfo[]>>('/api/desktop/backups', { timeout: PROBE_TIMEOUT })
}

export function createDesktopBackup(label?: string) {
  return apiClient.post<ApiResponse<DesktopBackupInfo>>('/api/desktop/backups', { label },
    { timeout: LONG_OPERATION_TIMEOUT })
}

export function restoreDesktopBackup(backupId: string) {
  return apiClient.post<ApiResponse<DesktopRestoreResult>>(`/api/desktop/backups/${backupId}/restore`, undefined,
    { timeout: LONG_OPERATION_TIMEOUT })
}

/** 请求体是**包本身**（不是 `{ package }` 套壳）。 */
export function previewDesktopImport(workspacePackage: unknown) {
  return apiClient.post<ApiResponse<DesktopImportPreview>>('/api/desktop/import/preview', workspacePackage,
    { timeout: LONG_OPERATION_TIMEOUT })
}

/**
 * 应用导入。请求体是 `{ package, resolutions }`；
 * `resolutions` 的键是**记录自己的 stableId**（嵌套记录用嵌套记录的 stableId）。
 * 返回字段与预览不同名：`inserted / updated / skipped / keptLocal`（没有 added / conflicts）。
 */
export function applyDesktopImport(workspacePackage: unknown, resolutions: Record<string, string>) {
  return apiClient.post<ApiResponse<DesktopImportResult>>('/api/desktop/import/apply',
    { package: workspacePackage, resolutions },
    { timeout: LONG_OPERATION_TIMEOUT })
}
