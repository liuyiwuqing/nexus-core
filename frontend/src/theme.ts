import type { GlobalThemeOverrides } from 'naive-ui';

/** 控件描边与原生 input/select 共用一套语言：静止 --border-strong / 悬停 --border-hover / 焦点 --focus。 */
const inputField = {
  border: '1px solid #b3c6bb',
  borderHover: '1px solid #7f9e8d',
  borderFocus: '1px solid #467b69',
  /* 显式给中性色，避免禁用态比可用态更深。Input 无 borderActive 键，不要加。 */
  borderDisabled: '1px solid #dce5e0',
  boxShadowFocus: '0 0 0 2px #467b69',
  heightMedium: '38px',
  fontSizeMedium: '13px',
};

/** Select 的描边不在 selectLight.self 里，而是由 peers.InternalSelection 提供。 */
const selectionField = { ...inputField, borderActive: '1px solid #245d4d' };

export const consoleTheme: GlobalThemeOverrides = {
  common: {
    primaryColor: '#245d4d', primaryColorHover: '#32715f', primaryColorPressed: '#194838',
    primaryColorSuppl: '#245d4d', textColorBase: '#243b35', textColor1: '#243b35',
    textColor2: '#465b54', textColor3: '#57675f',
    borderColor: '#dce5e0',
    dividerColor: '#e7ede8', hoverColor: '#f0f5f1',
    bodyColor: '#f4f7f5', cardColor: '#fff',
    placeholderColor: '#697970',
    errorColor: '#9b3f35', errorColorHover: '#8a352c', errorColorPressed: '#722b23', errorColorSuppl: '#9b3f35',
    borderRadius: '8px', borderRadiusSmall: '6px',
    fontSize: '14px', fontSizeSmall: '12px', fontSizeMedium: '14px', heightMedium: '38px',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
  },
  Input: { ...inputField },
  Select: { peers: { InternalSelection: { ...selectionField } } },
  DatePicker: { peers: { Input: { ...inputField } } },
  Button: { fontWeight: '550', borderRadiusMedium: '8px', heightMedium: '38px', heightSmall: '30px', paddingMedium: '0 16px' },
  Tag: { borderRadius: '6px', heightSmall: '20px' },
  Switch: { railColorActive: '#245d4d' },
  Skeleton: { color: '#edf4ef' },
  Drawer: { color: '#fff' },
};

/** Stamp only the style nodes mounted by Naive UI; never patch global DOM methods. */
export function styleMountTarget(): HTMLElement {
  const nonce = document.querySelector<HTMLMetaElement>('meta[name="nexus-style-nonce"]')?.content;
  if (!nonce || nonce === '__NEXUS_STYLE_NONCE__') return document.head;
  return new Proxy(document.head, {
    get(target, property) {
      if (property === 'appendChild' || property === 'insertBefore') {
        return (node: Node, reference?: Node | null) => {
          if (node instanceof HTMLStyleElement) node.nonce = nonce;
          return property === 'appendChild' ? target.appendChild(node) : target.insertBefore(node, reference ?? null);
        };
      }
      const value = Reflect.get(target, property, target);
      return typeof value === 'function' ? value.bind(target) : value;
    },
  });
}
