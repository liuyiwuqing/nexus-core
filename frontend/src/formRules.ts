import type { FormItemRule } from 'naive-ui';

type Constraints = {
  required?: boolean;
  maxlength?: number | string;
  pattern?: string;
  multiple?: boolean;
  number?: boolean;
  min?: number | string;
  max?: number | string;
  step?: number | string;
  disabled?: () => boolean;
};

/** NSelect/日期等复合控件不参加浏览器原生校验，统一在 NForm 提交前检查当前草稿。 */
export function fieldRule(read: () => unknown, constraints: Constraints): FormItemRule {
  return {
    trigger: ['input', 'change', 'blur'],
    validator() {
      if (constraints.disabled?.()) return true;
      const value = read();
      if (value === null || value === undefined || value === '' || (Array.isArray(value) && !value.length))
        return constraints.required ? new Error(constraints.multiple ? '请至少选择一项。' : '请填写此项。') : true;
      if (constraints.number) {
        const n = Number(value),
          min = Number(constraints.min),
          max = Number(constraints.max);
        if (!Number.isFinite(n) || (constraints.min !== undefined && n < min) || (constraints.max !== undefined && n > max))
          return new Error(`请输入 ${min} 到 ${max} 之间的数值。`);
        // number 输入默认步长为 1；显示时长显式使用 any，继续允许小数秒。
        if (constraints.step !== 'any') {
          const steps = (n - (constraints.min === undefined ? 0 : min)) / Number(constraints.step ?? 1);
          if (Math.abs(steps - Math.round(steps)) > 1e-8) return new Error('请输入符合步长的数值。');
        }
      } else {
        if (
          typeof constraints.min === 'string' &&
          constraints.min.includes('T') &&
          (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(String(value)) ||
            !Number.isFinite(new Date(String(value)).valueOf()) ||
            String(value) < constraints.min ||
            String(value) > String(constraints.max))
        )
          return new Error('请选择 0001 到 9999 年之间的有效日期时间。');
        if (constraints.maxlength !== undefined && String(value).length > Number(constraints.maxlength))
          return new Error(`最多填写 ${constraints.maxlength} 个字符。`);
        if (constraints.pattern && !new RegExp(`^(?:${constraints.pattern})$`, 'u').test(String(value)))
          return new Error('格式不正确，请检查输入。');
      }
      return true;
    },
  };
}
