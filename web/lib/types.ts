export interface WebhookData {
  _id?: string
  deliveryUrl: string
  events: string[]
  isActive: boolean
  signingSecret: string
}

export interface WebhookPayload {
  smsId: string
  sender: string
  message: string
  messageKind?: 'sms' | 'mms'
  subject?: string
  attachments?: Array<{
    url: string
    mimeType?: string
    fileName?: string
    sizeBytes?: number
  }>
  receivedAt: string
  deviceId: string
  webhookSubscriptionId: string
  webhookEvent: string
}
