import type { RouteRecordRaw } from 'vue-router';
/** Not constant/public: existing Soybean authentication guards remain authoritative. */
export const fcpPluginRoutes: RouteRecordRaw[] = [{
  path:'/fcp/plugins/:pluginId([a-z][a-z0-9._-]*)/:pageId([a-z][a-z0-9._-]*)',
  component:() => import('@/layouts/base-layout/index.vue'),meta:{title:'配置插件',hideInMenu:true,fcpPluginContainer:true},children:[{
    path:'',name:'fcp_plugin_page',component:() => import('./index.vue'),
    meta:{title:'配置插件',hideInMenu:true,keepAlive:false,fcpPluginContainer:true}
  }]
}];
