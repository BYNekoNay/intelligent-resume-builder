import { apiClient, type ApiResponse } from './client'
export interface ResumeImportResponse { fileName:string;mediaType:string;extractedText:string;normalizedResumeInput:Record<string,unknown>;originalFileStored:boolean }
export function parseResumeFile(file:File){const form=new FormData();form.append('file',file);return apiClient.post<ApiResponse<ResumeImportResponse>>('/api/resume-imports/parse',form,{headers:{'Content-Type':'multipart/form-data'}})}
