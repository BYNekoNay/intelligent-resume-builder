import { apiClient, type ApiResponse } from './client'
export type CommunicationType='COVER_LETTER'|'EMAIL'|'OPENING_MESSAGE'
export function generateCommunication(resumeVersionId:number,jobDescriptionId:number,type:CommunicationType){return apiClient.post<ApiResponse<{type:CommunicationType;draft:string;sentAutomatically:boolean;requiresManualConfirmation:boolean}>>('/api/communications/generate',{resumeVersionId,jobDescriptionId,type})}
