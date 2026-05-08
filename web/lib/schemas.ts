import { z } from 'zod'

export const sendSmsSchema = z.object({
  deviceId: z.string().min(1, {
    message: 'Please select a device',
  }),
  messageKind: z.enum(['sms', 'mms']).default('sms'),
  recipients: z
    .array(
      z.string().regex(/^\+?\d{1,14}$/, {
        message: 'Please enter a valid phone number',
      })
    )
    .min(1, {
      message: 'At least one recipient is required',
    }),
  message: z.string().max(1600, {
    message: 'Message cannot exceed 1600 characters',
  }),
  subject: z.string().max(120, {
    message: 'Subject cannot exceed 120 characters',
  }).optional(),
  attachments: z.array(z.object({
    url: z.string().url({ message: 'Attachment must be a valid URL' }),
    mimeType: z.string().optional(),
    fileName: z.string().optional(),
    sizeBytes: z.number().optional(),
  })).optional(),
  simSubscriptionId: z.number().optional(),
}).superRefine((value, ctx) => {
  if (value.messageKind === 'sms' && (!value.message || value.message.trim() === '')) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['message'],
      message: 'Message is required for SMS',
    })
  }

  if (value.messageKind === 'mms' && (!value.attachments || value.attachments.length === 0)) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['attachments'],
      message: 'At least one attachment is required for MMS',
    })
  }

})

export type SendSmsFormData = z.infer<typeof sendSmsSchema>

// export const bulkSmsSchema = z.object({
//   deviceId: z.string().uuid({
//     message: 'Please select a device',
//   }),
//   file: z.instanceof(File, {
//     message: 'Please upload a CSV file',
//   }),
//   messageTemplate: z
//     .string()
//     .min(1, {
//       message: 'Message template is required',
//     })
//     .max(1600, {
//       message: 'Message template cannot exceed 1600 characters',
//     }),
// })

// export type BulkSmsFormData = z.infer<typeof bulkSmsSchema>
