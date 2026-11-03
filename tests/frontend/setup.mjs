import { getCurrentInstance, ref, reactive, computed, watch, toRaw, nextTick } from 'vue';

// The native project injects these via unplugin-auto-import. The unit fixture
// supplies the same Vue APIs without generating or modifying application files.
Object.assign(globalThis, { getCurrentInstance, ref, reactive, computed, watch, toRaw, nextTick });
