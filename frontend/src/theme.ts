import type { GlobalThemeOverrides } from 'naive-ui';

export const consoleTheme: GlobalThemeOverrides = {
  common: {
    primaryColor: '#245d4d', primaryColorHover: '#32715f', primaryColorPressed: '#194838',
    primaryColorSuppl: '#245d4d', textColorBase: '#243b35', textColor1: '#243b35',
    textColor2: '#465b54', textColor3: '#64756f', borderColor: '#dce5e0',
    placeholderColor: '#697970',
    borderRadius: '8px', fontSize: '14px', heightMedium: '38px',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
  },
  Button: { fontWeight: '550', borderRadiusMedium: '8px' },
  Tag: { borderRadius: '6px' },
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
