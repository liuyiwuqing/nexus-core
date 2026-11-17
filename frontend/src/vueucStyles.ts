import { CssRender } from 'css-render';
import { styleMountTarget } from './theme';

// vueuc 的浮层、虚拟列表绕过 NConfigProvider；只适配它自己的样式工厂。
// 不改全局 DOM 方法，也不放宽服务端 CSP。Vite 只把 vueuc/shared 的导出路由到这里。
const { c: createStyle } = CssRender();
export const c: typeof createStyle = new Proxy(createStyle, {
  apply(target, receiver, args) {
    const node = Reflect.apply(target, receiver, args);
    const mount = node.mount;
    node.mount = (options = {}) => mount.call(node, { ...options, parent: styleMountTarget() });
    return node;
  },
});
export const cssrAnchorMetaName = 'vueuc-style';
