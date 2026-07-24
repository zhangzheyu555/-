import { apiGet, apiPost, apiPut } from './client'
export interface MobileEmployee { id:string; storeId:string; storeName?:string; name:string; phone?:string; position?:string; employmentType?:string; status?:string; hireDate?:string; birthday?:string; idCardNo?:string; remark?:string; accountUsername?:string; accountEnabled?:boolean }
export interface EmployeePayload { storeId:string; name:string; phone?:string; position?:string; employmentType?:string; status?:string; hireDate?:string; birthday?:string; idCardNo?:string; remark?:string }
export function getMobileEmployees(query:{storeId?:string;status?:string}={}){return apiGet<MobileEmployee[]>('/api/employees',query)}
export function createMobileEmployee(payload:EmployeePayload){return apiPost<MobileEmployee,EmployeePayload>('/api/employees',payload)}
export function updateMobileEmployee(id:string,payload:EmployeePayload){return apiPut<MobileEmployee,EmployeePayload>(`/api/employees/${encodeURIComponent(id)}`,payload)}
export function createMobileEmployeeAccount(id:string){return apiPost<{employeeId:string;employeeName:string;username:string;initialPassword:string}>(`/api/employees/${encodeURIComponent(id)}/account`)}
