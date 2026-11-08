/** Hand-maintained runtime contracts and safe extension helpers, never generated output. */
import { uint64, requiredText } from './scalars';
export interface EducationField {
  name: string;
  column: string;
  table?: string;
  label: string;
  sqlType: string;
  scalar: 'uint64_string' | 'utc_timestamp' | 'business_date' | 'text';
  nullable: boolean;
  timeSemantics?: string;
  maxLength?: number;
  required?: boolean;
  options?: string[];
}
export interface EducationResource {
  id: string;
  table: string;
  label: string;
  list: EducationField[];
  detail: EducationField[];
  create: EducationField[];
  rename: EducationField[];
  allowedOperations: string[];
}
export interface EducationEndpoint {
  name: string;
  method: 'GET' | 'POST';
  path: string;
  resource?: string;
  operation?: string;
}
export interface EducationModuleSchema {
  module: string;
  profileId: string;
  schemaVersion: string;
  sourceId: string;
  personDisplayPolicy?: string;
  resources: EducationResource[];
  endpoints: EducationEndpoint[];
}
export function resource(schema: EducationModuleSchema, id: string): EducationResource {
  const value = schema.resources.find((item) => item.id === id);
  if (!value) throw new Error('未生成受审资源：' + id);
  return value;
}
export function generatedOperation(schema: EducationModuleSchema, operation: string): boolean {
  return schema.resources.some((item) => item.allowedOperations.includes(operation));
}
export function endpoint(endpoints: EducationEndpoint[], name: string, method: 'GET' | 'POST'): EducationEndpoint {
  const value = endpoints.find((item) => item.name === name && item.method === method);
  if (!value) throw new Error('未生成受审接口：' + name);
  return value;
}
export function endpointPath(value: EducationEndpoint, parameters: Record<string, string> = {}): string {
  return value.path.replace(/\{([a-zA-Z]+)\}/g, (_token, name: string) => {
    if (!Object.hasOwn(parameters, name)) throw new Error('缺少接口参数：' + name);
    return encodeURIComponent(parameters[name]);
  });
}
export function commandFields(schema: EducationModuleSchema, id: string, action: 'create' | 'rename', body: Record<string, unknown>) {
  const fields = resource(schema, id)[action];
  if (!fields.length || Object.keys(body).some((key) => !fields.some((field) => field.name === key))) throw new Error('命令字段未获生成规则许可。');
  for (const field of fields) {
    const value = body[field.name];
    if (value === undefined) {
      if (field.required) throw new Error('缺少必填字段：' + field.label);
      continue;
    }
    if (value === null) {
      if (!field.nullable) throw new Error('字段不能为空：' + field.label);
      continue;
    }
    if (field.scalar === 'uint64_string') uint64(value);
    if (field.maxLength !== undefined) requiredText(value as string, field.maxLength);
    if (field.options && !field.options.includes(value as string)) throw new Error('字段选项不受支持：' + field.label);
  }
}
