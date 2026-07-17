import { apiClient,type ApiResponse } from './client'
export interface InterviewAsset{id:number;interviewRecordId:number|null;questionText:string;originalAnswerText:string;suggestedAnswerText:string|null;feedbackJson:Record<string,unknown>|null;createdAt:string;updatedAt:string}
export function listInterviewAssets(){return apiClient.get<ApiResponse<InterviewAsset[]>>('/api/interview-answer-assets')}
export function createInterviewAsset(payload:{interviewRecordId?:number;questionText:string;originalAnswerText:string;suggestedAnswerText?:string;feedbackJson?:Record<string,unknown>}){return apiClient.post<ApiResponse<InterviewAsset>>('/api/interview-answer-assets',payload)}
