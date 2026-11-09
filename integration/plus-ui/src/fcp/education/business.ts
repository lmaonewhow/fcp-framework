import { schema } from '@/api/fcp/education/generated/foundation.schema';
import { resource } from './generatedSchema';
import { uint64, businessDate, requiredText } from './scalars';
export type BusinessOperation =
  | 'orgList'
  | 'orgDetail'
  | 'orgCreate'
  | 'orgRename'
  | 'classList'
  | 'classDetail'
  | 'classCreate'
  | 'classRename'
  | 'termList';
export type Permissions = Partial<Record<BusinessOperation, boolean>>;
export interface School {
  id: string;
  name: string;
  rowVersion: string;
  readiness: 'READY' | 'EXECUTION_GRANT_MISSING' | 'UNKNOWN';
  operations: Permissions;
}
export interface Organization {
  id: string;
  parentId: string | null;
  orgCode: string;
  orgKind: string;
  name: string;
  validFrom: string;
  validTo: string | null;
  createdAt: string;
  rowVersion: string;
}
export interface SchoolClass {
  id: string;
  schoolId: string;
  termId: string;
  classCode: string;
  name: string;
  gradeCode: string;
  createdAt: string;
  rowVersion: string;
}
export interface Term {
  id: string;
  schoolId: string;
  termCode: string;
  startsOn: string;
  endsOn: string;
  status: string;
  rowVersion: string;
}
export interface Page<T> {
  items: T[];
  nextAfterId: string | null;
}
export type Entity = Organization | SchoolClass;
export type EntityKind = 'organizations' | 'classes';
const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('无效的数据响应。');
  return value as Record<string, unknown>;
};
const text = (value: unknown): string => {
  if (typeof value !== 'string') throw new Error('无效的数据字段。');
  return value;
};
const nullableText = (value: unknown): string | null => (value === null ? null : text(value));
export function school(value: unknown): School {
  const v = object(value);
  const raw = v.operations && typeof v.operations === 'object' ? (v.operations as Record<string, unknown>) : {};
  const operations: Permissions = {};
  for (const key of ['orgList', 'orgDetail', 'orgCreate', 'orgRename', 'classList', 'classDetail', 'classCreate', 'classRename', 'termList'] as const)
    operations[key] = v.readiness === 'READY' && raw[key] === true;
  return {
    id: uint64(v.id),
    name: text(v.name),
    rowVersion: uint64(v.rowVersion),
    readiness: v.readiness === 'READY' ? 'READY' : v.readiness === 'EXECUTION_GRANT_MISSING' ? 'EXECUTION_GRANT_MISSING' : 'UNKNOWN',
    operations
  };
}
export function organization(value: unknown): Organization {
  const v = object(value);
  return {
    id: uint64(v.id),
    parentId: v.parentId === null ? null : uint64(v.parentId),
    orgCode: text(v.orgCode),
    orgKind: text(v.orgKind),
    name: text(v.name),
    validFrom: text(v.validFrom),
    validTo: nullableText(v.validTo),
    createdAt: text(v.createdAt),
    rowVersion: uint64(v.rowVersion)
  };
}
export function schoolClass(value: unknown): SchoolClass {
  const v = object(value);
  return {
    id: uint64(v.id),
    schoolId: uint64(v.schoolId),
    termId: uint64(v.termId),
    classCode: text(v.classCode),
    name: text(v.name),
    gradeCode: text(v.gradeCode),
    createdAt: text(v.createdAt),
    rowVersion: uint64(v.rowVersion)
  };
}
export function term(value: unknown): Term {
  const v = object(value);
  return {
    id: uint64(v.id),
    schoolId: uint64(v.schoolId),
    termCode: text(v.termCode),
    startsOn: businessDate(v.startsOn),
    endsOn: businessDate(v.endsOn),
    status: text(v.status),
    rowVersion: uint64(v.rowVersion)
  };
}
export function page<T>(value: unknown, parse: (item: unknown) => T): Page<T> {
  const v = object(value);
  if (!Array.isArray(v.items)) throw new Error('无效的列表响应。');
  return {
    items: v.items.map(parse),
    nextAfterId: v.nextAfterId === null ? null : uint64(v.nextAfterId)
  };
}
export function commandName(value: string, kind: EntityKind) {
  return requiredText(value, resource(schema, kind).create.find((field) => field.name === 'name')!.maxLength!);
}
