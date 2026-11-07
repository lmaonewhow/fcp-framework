import type { RouteRecordRaw } from 'vue-router';
import Layout from '@/layout/index.vue';
/** Precompiled container only. Catalog authorization determines whether any page is rendered. */
export const fcpPluginRoutes: RouteRecordRaw[] = [{
  path:'/fcp/plugins/:pluginId([a-z][a-z0-9._-]*)/:pageId([a-z][a-z0-9._-]*)',
  component:Layout,hidden:true,meta:{fcpPluginContainer:true},children:[{
    path:'',name:'FcpPluginPage',component:() => import('./index.vue'),
    meta:{title:'配置插件',noCache:true,activeMenu:'/fcp/plugins',fcpPluginContainer:true}
  }]
}];
