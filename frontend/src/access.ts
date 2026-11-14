import { computed, inject, ref, type Ref } from 'vue';
export type Role='admin'|'operator'|'viewer';
export function useAccess(){const role=inject<Ref<Role>>('role',ref('admin'));return {role,admin:computed(()=>role.value==='admin'),operate:computed(()=>role.value!=='viewer')};}
