export type FileStatus = 'create' | 'update' | 'unchanged' | 'preserve_manual' | 'adopt_generated' | 'conflict';
export interface PlanFile {
  path: string;
  status: FileStatus;
  currentSha256?: string;
  candidateSha256?: string;
  currentPreview?: string;
  candidatePreview?: string;
  currentPreviewTruncated?: boolean;
  candidatePreviewTruncated?: boolean;
  currentBytes?: number;
  candidateBytes?: number;
}
export interface GeneratorPlan {
  planId: string;
  expiresAt: string;
  applicable: boolean;
  files: PlanFile[];
  conflicts: Array<string | { path: string; reason: string }>;
}
export interface GeneratorOperation {
  operationId: string;
  planId: string;
  status: string;
  createdAt?: string;
  files?: string[];
}

export type EducationModule = 'foundation' | 'roster';
export interface EducationProfile {
  profileId: 'education-p042-v1';
  version: string;
  modules: Array<{
    module: EducationModule;
    sourceId: string;
    resources: Array<{ id: string; table: string; label: string; allowedOperations: string[] }>;
  }>;
  inputDigests: Record<string, string>;
  provenance: unknown;
}
