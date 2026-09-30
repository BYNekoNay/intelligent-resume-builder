import { apiClient, type ApiResponse } from './client'
export interface ResumeImportResponse { fileName:string;mediaType:string;extractedText:string;normalizedResumeInput:Record<string,unknown>;originalFileStored:boolean }
/**
 * 简历文件解析（同步长耗时端点）。
 *
 * <p>显式放宽超时：服务端单次解析预算为 `app.resume-import.extract-timeout-ms`（默认 15s），
 * 全局 10s 会让慢但成功的解析被客户端先判失败（用户看到泛化的解析错误，服务端仍在校验）。
 * 与 exportAccountData(30s) / 面试步进(60s) 同一惯例：客户端死线必须晚于服务端预算。
 * 契约由 UploadPathContractTest 守护。
 */
export function parseResumeFile(file:File){const form=new FormData();form.append('file',file);return apiClient.post<ApiResponse<ResumeImportResponse>>('/api/resume-imports/parse',form,{headers:{'Content-Type':'multipart/form-data'},timeout:30_000})}
