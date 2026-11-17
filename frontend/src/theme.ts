import type { GlobalThemeOverrides } from 'naive-ui';

/**
 * Naive UI 覆盖层。取值与 style.css 的 token 逐项对应：
 * 浅色对应 `:root`，深色对应 `:root[data-theme="dark"]`。
 * 只在这里声明 Naive 组件的键，页面样式一律走 CSS token，不从组件反推。
 */
interface Palette {
  primary: string; primaryHover: string; primaryPressed: string;
  ink: string; ink2: string; ink3: string;
  border: string; borderStrong: string; borderHover: string; divider: string;
  canvas: string; surface: string; surfaceSubtle: string; hover: string;
  placeholder: string;
  success: string; warning: string; error: string; info: string;
  focus: string; popover: string; skeleton: string; floatingShadow: string;
}

const light: Palette = {
  primary: '#245d4d', primaryHover: '#2f715d', primaryPressed: '#1c4a3d',
  ink: '#1f332d', ink2: '#42574f', ink3: '#55655e',
  border: '#dce5e0', borderStrong: '#b3c6bb', borderHover: '#7f9e8d', divider: '#e7ede8',
  canvas: '#f4f7f5', surface: '#ffffff', surfaceSubtle: '#fafcfb', hover: '#f0f5f1',
  placeholder: '#6b7b73',
  success: '#25734a', warning: '#7a5715', error: '#9b3f35', info: '#2b5f9e',
  focus: '#3f7462', popover: '#ffffff', skeleton: '#edf4ef', floatingShadow: '0 18px 48px #173f2c1a',
};

const dark: Palette = {
  primary: '#3f977a', primaryHover: '#4aa889', primaryPressed: '#348469',
  ink: '#e8efea', ink2: '#bcc9c2', ink3: '#98a79f',
  border: '#293530', borderStrong: '#394842', borderHover: '#4d5f57', divider: '#222d28',
  canvas: '#0e1412', surface: '#151c19', surfaceSubtle: '#121815', hover: '#1d2622',
  placeholder: '#7e8d85',
  success: '#48b07c', warning: '#d6a94f', error: '#e07a70', info: '#7aa8e6',
  focus: '#7ec9a8', popover: '#1b2320', skeleton: '#1a2220', floatingShadow: '0 18px 48px #000000a6',
};

/** 控件描边与原生 input/select 共用一套语言：静止 --border-strong / 悬停 --border-hover / 焦点 --focus。 */
function inputField(p: Palette) {
  return {
    border: `1px solid ${p.borderStrong}`,
    borderHover: `1px solid ${p.borderHover}`,
    borderFocus: `1px solid ${p.focus}`,
    /* 显式给中性色，避免禁用态比可用态更深。Input 无 borderActive 键，不要加。 */
    borderDisabled: `1px solid ${p.border}`,
    boxShadowFocus: `0 0 0 3px ${p.focus}33`,
    heightMedium: '34px',
    fontSizeMedium: '13px',
  };
}

function build(p: Palette): GlobalThemeOverrides {
  const field = inputField(p);
  /* Select 的描边不在 selectLight.self 里，而是由 peers.InternalSelection 提供。 */
  const selection = { ...field, borderActive: `1px solid ${p.primaryPressed}` };
  return {
    common: {
      primaryColor: p.primary, primaryColorHover: p.primaryHover, primaryColorPressed: p.primaryPressed,
      primaryColorSuppl: p.primary,
      infoColor: p.info, infoColorHover: p.info, infoColorPressed: p.info, infoColorSuppl: p.info,
      successColor: p.success, successColorHover: p.success, successColorPressed: p.success, successColorSuppl: p.success,
      warningColor: p.warning, warningColorHover: p.warning, warningColorPressed: p.warning, warningColorSuppl: p.warning,
      errorColor: p.error, errorColorHover: p.error, errorColorPressed: p.error, errorColorSuppl: p.error,
      textColorBase: p.ink, textColor1: p.ink, textColor2: p.ink2, textColor3: p.ink3,
      borderColor: p.border, dividerColor: p.divider, hoverColor: p.hover,
      bodyColor: p.canvas, cardColor: p.surface, modalColor: p.surface, popoverColor: p.popover,
      tableColor: p.surface, inputColor: p.surface, actionColor: p.surfaceSubtle,
      placeholderColor: p.placeholder,
      borderRadius: '8px', borderRadiusSmall: '6px',
      fontSize: '14px', fontSizeSmall: '12px', fontSizeMedium: '14px',
      heightMedium: '34px', heightSmall: '30px', heightLarge: '44px',
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
    },
    Input: { ...field },
    Select: { peers: { InternalSelection: { ...selection } } },
    Form: { labelFontSizeTopMedium: '13px', labelHeightMedium: '20px', labelPaddingVertical: '0 0 4px 0', blankHeightMedium: '34px' },
    InputNumber: { peers: { Input: { ...field } } },
    TimePicker: { peers: { Input: { ...field } } },
    AutoComplete: { peers: { Input: { ...field } } },
    DatePicker: { peers: { Input: { ...field } } },
    Button: { fontWeight: '550', borderRadiusMedium: '8px', heightMedium: '34px', heightSmall: '30px', heightLarge: '44px', paddingMedium: '0 16px' },
    Tag: { borderRadius: '6px', heightSmall: '22px', fontSizeSmall: '12px' },
    Switch: { railColorActive: p.primary },
    Skeleton: { color: p.skeleton },
    Drawer: { color: p.surface },
    /* 浮层与 --shadow-2 / --radius-lg — --radius-md 对应；默认值偏 Material 的深阴影，会和页面层级冲突。 */
    Dropdown: { color: p.popover, borderRadius: '12px', boxShadow: p.floatingShadow },
    Popover: { color: p.popover, borderRadius: '12px', boxShadow: p.floatingShadow },
    Tooltip: { color: p.popover, textColor: p.ink, borderRadius: '8px', boxShadow: p.floatingShadow },
    Pagination: { itemTextColor: p.ink2 },
  };
}

export const lightConsoleTheme: GlobalThemeOverrides = build(light);
export const darkConsoleTheme: GlobalThemeOverrides = build(dark);
/** 兼容存量引用：未指定模式时按浅色。 */
export const consoleTheme: GlobalThemeOverrides = lightConsoleTheme;

/** Stamp only the style nodes mounted by Naive UI; never patch global DOM methods. */
function documentNonce(): string | null {
  const nonce = document.querySelector<HTMLMetaElement>('meta[name="nexus-style-nonce"]')?.content;
  return nonce && nonce !== '__NEXUS_STYLE_NONCE__' ? nonce : null;
}

export function styleMountTarget(): HTMLElement {
  const nonce = documentNonce();
  if (!nonce) return document.head;
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
