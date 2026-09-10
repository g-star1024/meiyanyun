// 开发期种子数据占位。
// 到店/分诊域已切换为后端真实 API（arrival store），旧的本地 C-201/202 假客户
// 调真实 checkIn 必然 400，故种子写入口停用；/closed-loop 样板页仅保留离线状态机演示，
// 候诊列为真实空态。真实联调数据请走客情登记（/guest-reg）或接待台手工登记。
export function seedClinicData() {
  // no-op：真实环境由后端 API 提供初始数据
}
