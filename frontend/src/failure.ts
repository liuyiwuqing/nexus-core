import { errorNames } from './types';

// 未登记的失败码不该只显示一个状态码。给一句能照着做的说明，并把 HTTP 状态与
// 后端 error 码留在括号里，用户报障时能直接念出来。
export function failureText(status: number, code: string): string {
  const known = errorNames[code];
  if (known) return known;
  const hint =
    status === 400 ? '提交的内容未通过校验，请检查后重试'
    : status === 401 ? '登录状态已失效，请重新登录'
    : status === 403 ? '当前角色无权执行此操作'
    : status === 404 ? '当前版本没有这个接口，请确认控制台与 CORE 是同一版本'
    : status === 409 ? '数据已被其他操作修改，请刷新后重试'
    : status === 413 ? '提交的内容过大，请精简后重试'
    : status === 429 ? '操作过于频繁，请稍后再试'
    : status >= 500 ? 'CORE 处理失败，请稍后重试；若持续出现，请把括号内的编号提供给维护者'
    : '请求未被接受，请检查操作内容后重试';
  return `${hint}（HTTP ${status}${code ? ` · ${code}` : ''}）。`;
}

export class ApiFailure extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(failureText(status, code));
    this.name = 'ApiFailure';
  }
}

// 面板侧统一取文案：fetch 的网络层失败只会抛英文 TypeError，不能原样甩给用户。
export function readFailure(failure: unknown, fallback: string): string {
  if (failure instanceof ApiFailure) return failure.message;
  if (failure instanceof DOMException && failure.name === 'AbortError') return fallback;
  if (failure instanceof TypeError) return '无法连接 CORE，请确认服务正在运行后重试。';
  return fallback;
}
