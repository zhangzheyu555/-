export interface NotificationRequest {
  templateIds?: string[]
}

export interface NotificationCapability {
  supported: boolean
  enabled: boolean
  message: string
}

export async function requestNotification(
  request: NotificationRequest = {},
): Promise<NotificationCapability> {
  // #ifdef H5
  if (typeof Notification === 'undefined') {
    return { supported: false, enabled: false, message: '当前浏览器不支持通知，可在工作台查看待办' }
  }
  const permission = await Notification.requestPermission()
  return {
    supported: true,
    enabled: permission === 'granted',
    message: permission === 'granted' ? '浏览器通知已允许' : '浏览器通知未开启',
  }
  // #endif

  // #ifdef MP-WEIXIN
  const templateIds = (request.templateIds || []).filter(Boolean)
  if (!templateIds.length) {
    return { supported: true, enabled: false, message: '暂未配置微信订阅消息模板' }
  }
  const response = await new Promise<Record<string, string>>((resolve, reject) => {
    uni.requestSubscribeMessage({
      tmplIds: templateIds,
      success: (result) => resolve(result as unknown as Record<string, string>),
      fail: reject,
    })
  })
  const enabled = templateIds.some((id) => response[id] === 'accept')
  return { supported: true, enabled, message: enabled ? '微信待办提醒已允许' : '微信待办提醒未开启' }
  // #endif

  // #ifdef APP-PLUS
  return {
    supported: true,
    enabled: false,
    message: 'App 推送接口已预留，待证书和运营策略就绪后开启',
  }
  // #endif

  return { supported: false, enabled: false, message: '当前平台暂不支持通知' }
}
