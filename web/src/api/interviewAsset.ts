import { apiClient,type ApiResponse } from './client'
export interface InterviewAsset{id:number;interviewRecordId:number|null;questionText:string;originalAnswerText:string;suggestedAnswerText:string|null;feedbackJson:Record<string,unknown>|null;createdAt:string;updatedAt:string}
export function listInterviewAssets(params?:{jobDescriptionId?:number;keyword?:string}){return apiClient.get<ApiResponse<InterviewAsset[]>>('/api/interview-answer-assets',{params})}
export function createInterviewAsset(payload:{interviewRecordId?:number;questionText:string;originalAnswerText:string;suggestedAnswerText?:string;feedbackJson?:Record<string,unknown>}){return apiClient.post<ApiResponse<InterviewAsset>>('/api/interview-answer-assets',payload)}
export function updateInterviewAsset(id:number,payload:{interviewRecordId?:number;questionText:string;originalAnswerText:string;suggestedAnswerText?:string;feedbackJson?:Record<string,unknown>}){return apiClient.put<ApiResponse<InterviewAsset>>(`/api/interview-answer-assets/${id}`,payload)}
export function deleteInterviewAsset(id:number){return apiClient.delete<ApiResponse<void>>(`/api/interview-answer-assets/${id}`)}
