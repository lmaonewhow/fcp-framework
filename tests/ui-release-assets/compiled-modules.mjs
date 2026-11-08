// Actual strict compiler output and resolved ESM imports, not stripped source strings.
import {mkdtempSync, writeFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
export const compiled = mkdtempSync(join(tmpdir(), 'fcp-release-modules-'));
process.on('exit',()=>rmSync(compiled,{recursive:true,force:true}));
execFileSync(process.env.TSC || 'tsc', ['integration/frontend-shared/release-session.ts',
  'integration/frontend-shared/trace.ts','--target','ES2022','--module','ES2022',
  '--lib','ES2022,DOM','--strict','--outDir',compiled], {cwd:resolve('.'),stdio:'inherit'});
writeFileSync(join(compiled,'package.json'),'{"type":"module"}');
export const compiledUrl = name => pathToFileURL(join(compiled,name)).href;
