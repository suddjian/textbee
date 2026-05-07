package com.vernu.sms.helpers;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.net.Uri;
import android.telephony.SmsManager;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.klinker.android.send_message.Message;
import com.klinker.android.send_message.Settings;
import com.klinker.android.send_message.Transaction;
import com.vernu.sms.AppConstants;
import com.vernu.sms.TextBeeUtils;
import com.vernu.sms.dtos.SMSDTO;
import com.vernu.sms.models.MessageAttachmentPayload;
import com.vernu.sms.receivers.MMSStatusReceiver;
import com.vernu.sms.receivers.SMSStatusReceiver;
import com.vernu.sms.workers.SMSStatusUpdateWorker;

import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class SMSHelper {
    private static final String TAG = "SMSHelper";
    private static final int MMS_ATTACHMENT_DOWNLOAD_TIMEOUT_MS = 15000;
    private static final int MMS_ATTACHMENT_MAX_BYTES = 5 * 1024 * 1024;
    
    /**
     * Sends an SMS message and returns whether the operation was successful
     * 
     * @param phoneNo The recipient's phone number
     * @param message The SMS message to send
     * @param smsId The unique ID for this SMS
     * @param smsBatchId The batch ID for this SMS
     * @param context The application context
     * @return boolean True if sending was initiated, false if permissions aren't granted
     */
    public static boolean sendSMS(String phoneNo, String message, String smsId, String smsBatchId, Context context) {
        // Check if we have permission to send SMS
        if (!TextBeeUtils.isPermissionGranted(context, Manifest.permission.SEND_SMS)) {
            Log.e(TAG, "SMS permission not granted. Unable to send SMS.");
            
            // Report failure to API
            reportPermissionError(context, smsId, smsBatchId);
            
            return false;
        }
        
        try {
            SmsManager smsManager = SmsManager.getDefault();

            // Create pending intents for status tracking
            PendingIntent sentIntent = createSentPendingIntent(context, smsId, smsBatchId);
            PendingIntent deliveredIntent = createDeliveredPendingIntent(context, smsId, smsBatchId);

            // For SMS with more than 160 chars
            ArrayList<String> parts = smsManager.divideMessage(message);
            if (parts.size() > 1) {
                ArrayList<PendingIntent> sentIntents = new ArrayList<>();
                ArrayList<PendingIntent> deliveredIntents = new ArrayList<>();
                
                for (int i = 0; i < parts.size(); i++) {
                    sentIntents.add(sentIntent);
                    deliveredIntents.add(deliveredIntent);
                }
                
                smsManager.sendMultipartTextMessage(phoneNo, null, parts, sentIntents, deliveredIntents);
            } else {
                smsManager.sendTextMessage(phoneNo, null, message, sentIntent, deliveredIntent);
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Exception when sending SMS: " + e.getMessage());
            
            // Report exception to API
            reportSendingError(context, smsId, smsBatchId, e.getMessage());
            
            return false;
        }
    }

    public static boolean sendMMS(String phoneNo, String message, String subject,
                                  MessageAttachmentPayload[] attachments, Integer simSubscriptionId,
                                  String smsId, String smsBatchId, Context context) {
        if (!TextBeeUtils.isPermissionGranted(context, Manifest.permission.SEND_SMS)) {
            Log.e(TAG, "SMS permission not granted. Unable to send MMS.");
            reportPermissionError(context, smsId, smsBatchId);
            return false;
        }

        try {
            Message mmsMessage = new Message(message != null ? message : "", phoneNo);
            mmsMessage.setSave(false);

            if (subject != null && !subject.trim().isEmpty()) {
                mmsMessage.setSubject(subject.trim());
            }

            int attachmentCount = 0;
            if (attachments != null) {
                for (MessageAttachmentPayload attachment : attachments) {
                    if (attachment == null || attachment.getUrl() == null || attachment.getUrl().trim().isEmpty()) {
                        continue;
                    }

                    byte[] attachmentBytes = downloadAttachment(attachment.getUrl().trim());
                    String mimeType = sanitizeMimeType(attachment.getMimeType(), attachment.getUrl());
                    String fileName = sanitizeFileName(attachment.getFileName(), attachment.getUrl(), mimeType);

                    mmsMessage.addMedia(attachmentBytes, mimeType, fileName);
                    attachmentCount++;
                }
            }

            if (attachmentCount == 0) {
                reportSendingError(context, smsId, smsBatchId, "MMS requires at least one valid attachment URL");
                return false;
            }

            Settings settings = new Settings();
            settings.setUseSystemSending(true);
            settings.setGroup(false);
            settings.setSubscriptionId(simSubscriptionId);

            Transaction transaction = new Transaction(context, settings);
            Intent mmsSentIntent = new Intent(context, MMSStatusReceiver.class);
            mmsSentIntent.putExtra("sms_id", smsId);
            mmsSentIntent.putExtra("sms_batch_id", smsBatchId);
            transaction.setExplicitBroadcastForSentMms(mmsSentIntent);

            transaction.sendNewMessage(mmsMessage, Transaction.NO_THREAD_ID);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Exception when sending MMS: " + e.getMessage());
            reportSendingError(context, smsId, smsBatchId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Sends an SMS message from a specific SIM slot and returns whether the operation was successful
     * 
     * @param phoneNo The recipient's phone number
     * @param message The SMS message to send
     * @param simSubscriptionId The specific SIM subscription ID to use
     * @param smsId The unique ID for this SMS
     * @param smsBatchId The batch ID for this SMS
     * @param context The application context
     * @return boolean True if sending was initiated, false if permissions aren't granted
     */
    public static boolean sendSMSFromSpecificSim(String phoneNo, String message, int simSubscriptionId, 
                                      String smsId, String smsBatchId, Context context) {
        // Check for required permissions
        if (!TextBeeUtils.isPermissionGranted(context, Manifest.permission.SEND_SMS) ||
            !TextBeeUtils.isPermissionGranted(context, Manifest.permission.READ_PHONE_STATE)) {
            Log.e(TAG, "SMS or Phone State permission not granted. Unable to send SMS from specific SIM.");
            
            // Report failure to API
            reportPermissionError(context, smsId, smsBatchId);
            
            return false;
        }
        
        try {
            // Get the SmsManager for the specific SIM
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                smsManager = SmsManager.getSmsManagerForSubscriptionId(simSubscriptionId);
            } else {
                // Fallback to default SmsManager for older Android versions
                smsManager = SmsManager.getDefault();
                Log.w(TAG, "Using default SIM as specific SIM selection not supported on this Android version");
            }

            // Create pending intents for status tracking
            PendingIntent sentIntent = createSentPendingIntent(context, smsId, smsBatchId);
            PendingIntent deliveredIntent = createDeliveredPendingIntent(context, smsId, smsBatchId);

            // For SMS with more than 160 chars
            ArrayList<String> parts = smsManager.divideMessage(message);
            if (parts.size() > 1) {
                ArrayList<PendingIntent> sentIntents = new ArrayList<>();
                ArrayList<PendingIntent> deliveredIntents = new ArrayList<>();
                
                for (int i = 0; i < parts.size(); i++) {
                    sentIntents.add(sentIntent);
                    deliveredIntents.add(deliveredIntent);
                }
                
                smsManager.sendMultipartTextMessage(phoneNo, null, parts, sentIntents, deliveredIntents);
            } else {
                smsManager.sendTextMessage(phoneNo, null, message, sentIntent, deliveredIntent);
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Exception when sending SMS from specific SIM: " + e.getMessage());
            
            // Report exception to API
            reportSendingError(context, smsId, smsBatchId, e.getMessage());
            
            return false;
        }
    }
    
    private static void reportPermissionError(Context context, String smsId, String smsBatchId) {
        SMSDTO smsDTO = new SMSDTO();
        smsDTO.setSmsId(smsId);
        smsDTO.setSmsBatchId(smsBatchId);
        smsDTO.setStatus("FAILED");
        smsDTO.setFailedAtInMillis(System.currentTimeMillis());
        smsDTO.setErrorCode("PERMISSION_DENIED");
        smsDTO.setErrorMessage("SMS permission not granted");
        
        updateSMSStatus(context, smsDTO);
    }
    
    private static void reportSendingError(Context context, String smsId, String smsBatchId, String errorMessage) {
        SMSDTO smsDTO = new SMSDTO();
        smsDTO.setSmsId(smsId);
        smsDTO.setSmsBatchId(smsBatchId);
        smsDTO.setStatus("FAILED");
        smsDTO.setFailedAtInMillis(System.currentTimeMillis());
        smsDTO.setErrorCode("SENDING_EXCEPTION");
        smsDTO.setErrorMessage(errorMessage);
        
        updateSMSStatus(context, smsDTO);
    }

    public static void reportUnsupportedMMS(Context context, String smsId, String smsBatchId) {
        SMSDTO smsDTO = new SMSDTO();
        smsDTO.setSmsId(smsId);
        smsDTO.setSmsBatchId(smsBatchId);
        smsDTO.setStatus("FAILED");
        smsDTO.setFailedAtInMillis(System.currentTimeMillis());
        smsDTO.setErrorCode("MMS_NOT_SUPPORTED");
        smsDTO.setErrorMessage("This app version does not support MMS sending yet");

        updateSMSStatus(context, smsDTO);
    }

    public static void enqueueStatusUpdate(Context context, SMSDTO smsDTO) {
        updateSMSStatus(context, smsDTO);
    }
    
    private static void updateSMSStatus(Context context, SMSDTO smsDTO) {
        String deviceId = SharedPreferenceHelper.getSharedPreferenceString(context, AppConstants.SHARED_PREFS_DEVICE_ID_KEY, "");
        String apiKey = SharedPreferenceHelper.getSharedPreferenceString(context, AppConstants.SHARED_PREFS_API_KEY_KEY, "");
        
        if (deviceId.isEmpty() || apiKey.isEmpty()) {
            Log.e(TAG, "Device ID or API key not found");
            return;
        }

        SMSStatusUpdateWorker.enqueueWork(context, deviceId, apiKey, smsDTO);
    }
    
    private static PendingIntent createSentPendingIntent(Context context, String smsId, String smsBatchId) {
        // Create explicit intent (specify the component)
        Intent intent = new Intent(context, SMSStatusReceiver.class);
        intent.setAction(SMSStatusReceiver.SMS_SENT);
        intent.putExtra("sms_id", smsId);
        intent.putExtra("sms_batch_id", smsBatchId);
        
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        
        // Use a unique request code to avoid PendingIntent collisions
        int requestCode = (smsId + "_sent").hashCode();
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }
    
    private static PendingIntent createDeliveredPendingIntent(Context context, String smsId, String smsBatchId) {
        // Create explicit intent (specify the component)
        Intent intent = new Intent(context, SMSStatusReceiver.class);
        intent.setAction(SMSStatusReceiver.SMS_DELIVERED);
        intent.putExtra("sms_id", smsId);
        intent.putExtra("sms_batch_id", smsBatchId);
        
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        
        // Use a unique request code to avoid PendingIntent collisions
        int requestCode = (smsId + "_delivered").hashCode();
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    private static byte[] downloadAttachment(String urlString) throws Exception {
        URL url = new URL(urlString);
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("Unsupported attachment URL protocol");
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(MMS_ATTACHMENT_DOWNLOAD_TIMEOUT_MS);
        connection.setReadTimeout(MMS_ATTACHMENT_DOWNLOAD_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(true);

        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IllegalStateException("Attachment download failed with HTTP " + responseCode);
        }

        int contentLength = connection.getContentLength();
        if (contentLength > MMS_ATTACHMENT_MAX_BYTES) {
            throw new IllegalStateException("Attachment is too large for MMS");
        }

        try (InputStream inputStream = connection.getInputStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            int totalBytes = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                totalBytes += bytesRead;
                if (totalBytes > MMS_ATTACHMENT_MAX_BYTES) {
                    throw new IllegalStateException("Attachment is too large for MMS");
                }
                outputStream.write(buffer, 0, bytesRead);
            }

            return outputStream.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static String sanitizeMimeType(String mimeType, String urlString) {
        if (mimeType != null && !mimeType.trim().isEmpty()) {
            return mimeType.trim().toLowerCase(Locale.ROOT);
        }

        String extension = MimeTypeMap.getFileExtensionFromUrl(urlString);
        if (extension != null && !extension.isEmpty()) {
            String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
            if (guessed != null && !guessed.isEmpty()) {
                return guessed;
            }
        }

        return "application/octet-stream";
    }

    private static String sanitizeFileName(String fileName, String urlString, String mimeType) {
        if (fileName != null && !fileName.trim().isEmpty()) {
            return fileName.trim();
        }

        String lastSegment = Uri.parse(urlString).getLastPathSegment();
        if (lastSegment != null && !lastSegment.trim().isEmpty()) {
            return lastSegment.trim();
        }

        if (mimeType.startsWith("image/")) {
            return "attachment.jpg";
        }
        if (mimeType.startsWith("audio/")) {
            return "attachment.wav";
        }
        if (mimeType.startsWith("video/")) {
            return "attachment.3gp";
        }
        return "attachment.bin";
    }
}
