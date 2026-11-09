/** Safe, hand-maintained presentation extensions. This file is never in the generated manifest.
 * Change labels/order/widths here; generated allowlists still decide which fields may appear.
 * Permission grants, write rules and response projection remain server-owned.
 */
import type { EducationField } from './generatedSchema';
const labels: Record<string, string> = {
  orgCode: '稳定编码',
  classCode: '稳定编码',
  parentId: '父组织',
  admissionKey: '入学周期键',
  enrollmentId: '学籍编号',
  roleCode: '任教角色编码'
};
export const fieldLabel = (field: EducationField) => labels[field.name] || field.label;
export const columnWidth = (field: EducationField) =>
  field.scalar === 'uint64_string' && field.name !== 'rowVersion' ? 190 : field.name === 'rowVersion' ? 90 : 150;
const formOrder = ['name', 'orgCode', 'classCode', 'parentId', 'orgKind', 'validFrom', 'gradeCode'];
export type FormKey = 'name' | 'code' | 'parentId' | 'orgKind' | 'validFrom' | 'gradeCode';
export const formKey = (field: EducationField): FormKey => (field.name === 'orgCode' || field.name === 'classCode' ? 'code' : field.name) as FormKey;
export function visibleFormFields(fields: EducationField[], newSchool: boolean): EducationField[] {
  return fields
    .filter((field) => formOrder.includes(field.name) && !(newSchool && ['parentId', 'orgKind'].includes(field.name)))
    .sort((left, right) => formOrder.indexOf(left.name) - formOrder.indexOf(right.name));
}
export const formLabel = (field: EducationField) =>
  ['orgCode', 'classCode'].includes(field.name) ? '稳定编码（创建后不可修改）' : fieldLabel(field);
const optionLabels: Record<string, string> = {
  CAMPUS: '校区',
  DEPARTMENT: '部门',
  SCHOOL: '学校'
};
export const optionLabel = (value: string) => optionLabels[value] || value;
