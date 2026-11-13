export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface MessageSubmission { readonly eventId: string; readonly title: string; readonly content: string }
export interface Message {
  messageId: string; connectorId: string; source: string; eventType: string;
  title: string; content: string; summary: string; sender: string; originalUrl: string;
  priority: 'low' | 'normal' | 'high' | 'critical'; receivedAt: string; createdAt: string;
  expiresAt: string; readAt: string | null; readStatus: 'read' | 'unread'; dataClass: string;
  contentPurgedAt?: string | null;
}
export interface Device { deviceId: string; name: string; enabled: boolean; online: boolean; lastSeen: string | null }
export interface DeviceConfig {
  schemaVersion: 1; timezone: string; language: 'zh-CN'; volume: number; brightness: number; muted: boolean;
  dnd: { enabled: boolean; manual: boolean; startMinute: number; endMinute: number; allowCritical: boolean };
  message: { queueDepth: number; durationMs: number };
}
export interface DeviceTelemetry {
  observedAt: number; firmwareVersion: string; configRevision: number; configWritable: boolean;
  muted: boolean; dnd: boolean; volume: number; brightness: number; unread: number;
  freeHeap: number; minFreeHeap: number; audioUnderruns?: number; audioFaulted?: boolean; config?: DeviceConfig;
}
export interface ConfigRequest {
  requestId: string; expectedRevision: number; status: 'pending' | 'sent' | 'applied' | 'rejected' | 'expired' | 'unconfirmed';
  attempts: number; reportedRevision: number | null; deviceCode: number | null; lastError: string;
  confirmationSource: 'ack' | 'telemetry' | null; createdAt: string; updatedAt: string; expiresAt: string; requestedConfig: DeviceConfig;
}
export interface DeviceManagement {
  deviceId: string; fresh: boolean; canConfigure: boolean; configBlock: string;
  telemetry: DeviceTelemetry | null; receivedAt: string | null; configRequests: ConfigRequest[];
}
export interface OtaTarget { releaseId: string; version: string; imageSha256: string; imageSize: number; releaseSequence: number }
export interface OtaRelease { releaseId: string; target: OtaTarget; identity: Record<string, string | number>; createdAt: string }
export interface OtaView {
  enabled: boolean; deviceId: string; fresh: boolean; canInstall: boolean; installBlock: string; canReboot: boolean;
  observation: { bootId: string; state: string; observedAt: number; available: boolean; requestId: string; rebootId: string; writtenBytes: number; reservedSequence: number; identity: Record<string, string | number>; target?: OtaTarget } | null;
  deployments: { requestId: string; releaseId: string; target: OtaTarget; status: string; lastError: string; createdAt: string;
    commands: { commandId: string; operation: string; status: string; attempts: number; expiresAt: string }[] }[];
}
export interface Connector { id: string; source: string; configured: boolean; enabled: boolean; state: string; lastSuccessAt?: string | null; lastError?: string | null; since?: string }
export interface Delivery { eventId: string; messageId: string; deviceId: string; title: string; status: string; attempts: number; lastError: string | null; nextAttemptAt: string; readAt: string | null }
export interface Status { database: string; mqtt: string; mqttError: string; schemaVersion: number }
export type Api = <T>(path: string, method?: string, body?: unknown, signal?: AbortSignal) => Promise<T>;
export interface Analysis {
  status: 'not_requested' | 'pending' | 'processing' | 'succeeded' | 'failed' | 'blocked';
  summary?: string; category?: string; importance?: string; replyDraft?: string; lastError?: string;
  memoryStatus?: 'off' | 'pending' | 'stored' | 'error' | 'blocked'; memoryUri?: string; memoryError?: string;
}
export type Tab = 'inbox' | 'pet' | 'devices' | 'connectors' | 'deliveries' | 'tools' | 'automation' | 'memory';
export function formatTime(value?: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? '—' : new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date);
}
export const deliveryNames: Record<string, string> = {
  pending: '等待投递', sent: '等待设备确认', received: '设备已保存', displayed: '设备已展示',
  read: '用户已读', failed: '投递失败', expired: '已结束',
};
export const errorNames: Record<string, string> = {
  unauthorized: '管理令牌无效，请重新登录。', invalid_request: '请检查填写的内容。',
  device_already_registered: '此设备 ID 已登记。', invalid_message_time: '消息时间或有效期不正确。',
  halo_not_configured: '请先在本机配置 Halo 只读令牌并启用接入。',
  halo_poll_in_progress: '正在同步，请稍后查看结果。',
  halo_authorization_required: 'Halo 令牌无效或缺少评论查看权限。',
  halo_authentication_redirect: 'Halo 要求重新认证，请检查只读令牌。',
  halo_network_error: '暂时无法连接 Halo，消息会保留并等待下次同步。',
  halo_scan_limit_exceeded: '评论数量超过单次扫描上限，需要调整同步配置。',
  delivery_not_retryable: '这条投递已处理、已过期或设备已停用，不能重试。',
  ack_timeout: '设备未在重试期限内确认', broker_unavailable: '消息连接暂不可用', busy: '设备队列已满',
  storage_error: '设备存储失败', time_unavailable: '设备尚未校时', read_elsewhere: '已在消息中心标记已读',
  expired: '消息已过期', device_disabled: '设备已停用', invalid: '设备拒绝了消息格式',
  ai_not_configured: 'AI 服务尚未配置，请填写本机配置并重启 CORE。',
  memory_not_configured: '长期记忆尚未配置，请填写本机配置并重启 CORE。',
  data_policy_denied: '仅允许处理经 Halo 核验的公开评论。',
  content_retention_expired: '内容已超过保留期限，不能继续分析或导出。',
  analysis_required: '请先完成 AI 分析。', analysis_not_retryable: '当前分析状态不允许重试。',
  no_longer_public: '评论已不再公开，已阻止外发。', comment_changed: '评论内容已变化，需要重新核验和分析。',
  source_recheck_failed: '无法核验评论公开状态，将按重试策略处理。',
  model_unavailable: 'AI 服务暂不可用，原消息已保留。', invalid_model_output: 'AI 返回内容不符合约定，原消息已保留。',
  memory_unavailable: '长期记忆暂不可用。', memory_write_failed: '长期记忆写入未获确认。',
  memory_search_failed: '记忆检索失败，请检查服务后重试。', memory_search_busy: '记忆检索繁忙，请稍后重试。',
  memory_delete_pending: '记忆副本正在处理，请等待删除完成后重试。',
  memory_write_unconfirmed: '记忆写入结果未确认，正在清理可能的副本。',
  memory_identity_changed: '副本身份与当前记忆服务配置不一致，请先核对旧副本。',
  model_subscription_invalid: '模型服务订阅已失效，请更新本机模型配置。',
  bridge_not_configured: 'Mac Bridge 尚未启用。', bridge_unavailable: 'Mac Bridge 暂不可用。',
  bridge_result_unknown: 'Bridge 尚未确认执行结果。', execution_interrupted: '处理进程中断，需要核对结果。',
  tool_not_allowed: '此工具未列入白名单。', invalid_tool_arguments: '请检查工具参数。',
  confirmation_mismatch: '确认参数与原请求不一致，请重新核对。', confirmation_expired: '确认已过期，请创建新请求。',
  job_not_confirmable: '请求已处理或已取消。', job_already_started: '请求已经开始，不能取消。', job_result_known: '请求结果已经确定。',
  idempotency_conflict: '请求编号对应的参数已变化。', application_not_allowed: '应用未在 Bridge 白名单中或本机未安装。',
  project_not_allowed: '项目未在 Bridge 白名单中。', project_status_failed: '无法读取项目 Git 状态。',
  application_launch_failed: '系统未接受打开应用请求。', rule_disabled: '规则已停用。',
  analysis_needs_review: '分析已失败或被阻止，请先在消息中核验。',
  automation_not_retryable: '仅允许重试当前启用版本中的失败任务。', invalid_automation_rule: '请检查规则时间、时区和提醒内容。',
  comment_not_public_or_missing: '评论不存在或当前不满足公开条件。', invalid_comment_name: '评论 ID 格式不正确。',
  focus_already_active: '已有进行中的专注，请刷新查看。', focus_not_finished: '专注时间尚未结束。', focus_not_active: '本次专注已结束或已取消。',
  config_readback_required: '等待设备回传最新完整设置。', config_storage_readonly: '设备存储不可写，请通过 USB 维护入口检查。',
  config_revision_conflict: '设备设置已变化，请加载新设置后重试。', config_request_in_progress: '已有配置请求等待设备确认。',
  config_result_unconfirmed: '上次配置结果未确认，等待设备回传设置后核对。', config_ack_timeout: '确认超时，等待设备回读核对',
  config_not_sent: '有效期内未能下发', config_not_observed: '到期后回读未见此次修改',
  config_observed_conflict: '设备已保存其他设置', device_conflict_or_storage: '设备版本冲突或存储失败',
  device_expired: '设备拒绝了过期配置', device_rejected: '设备拒绝了配置', config_request_not_found: '配置记录不存在。',
  ota_management_disabled: '远程升级尚未启用。', ota_readback_required: '等待设备回传最新升级状态。',
  ota_device_unavailable: '设备正在忙碌或无法安全保存升级记录。', ota_deployment_in_progress: '已有升级进行中或等待结果核对。',
  ota_device_busy: '设备当前状态不允许新安装。', ota_boot_changed: '设备已经重新启动，请刷新后重新选择。',
  ota_release_immutable: '此发布目录已登记其他内容，请使用新目录。', ota_release_not_found: '发布版本不存在。',
  ota_release_incompatible: '版本的板型、模型或存储身份与设备不一致。', ota_sequence_rejected: '版本序号必须高于设备已预留序号。',
  ota_reboot_readback_required: '需要设备最新的待重启回读，才能再次确认重启。', ota_result_unconfirmed: '安装结果未确认，等待设备回读。',
  ota_reboot_unconfirmed: '重启结果未确认，等待设备回读。', ota_not_sent: '有效期内未能下发安装命令。',
  ota_reboot_not_sent: '有效期内未能下发重启命令，可核对状态后重新确认。', ota_not_observed: '到期后回读未发现此安装请求。',
  ota_device_error: '设备报告升级异常，请查看设备诊断。', ota_reboot_rejected: '设备未接受重启，请核对状态。',
  ota_device_expired: '设备拒绝了过期命令。', ota_device_conflict: '设备升级记录与请求冲突。',
  ota_device_rejected: '设备拒绝了升级命令。',
  ota_deployment_not_found: '升级记录不存在。', device_not_found: '设备不存在，请刷新设备列表。',
  device_name_conflict: '设备名称已被其他操作更新，请加载当前名称后重试。',
};
