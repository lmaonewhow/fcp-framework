import base from '../education/vitest.config.mjs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
export default { ...base, test: { ...base.test, setupFiles: [...base.test.setupFiles, path.join(root, 'tests/education_roster/setup.mjs')], include: [path.join(root, 'tests/education_roster/*.test.mjs')] } };
