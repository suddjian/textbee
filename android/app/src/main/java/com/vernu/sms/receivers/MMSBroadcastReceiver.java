package com.vernu.sms.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.vernu.sms.AppConstants;
import com.vernu.sms.dtos.SMSDTO;
import com.vernu.sms.helpers.SharedPreferenceHelper;
import com.vernu.sms.models.MessageAttachmentPayload;
import com.vernu.sms.workers.SMSReceivedWorker;

import java.security.MessageDigest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MMSBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "MMSBroadcastReceiver";
    private static final int MMS_ADDRESS_TYPE_FROM = 137;
    private static final String MMS_WAP_PUSH_ACTION = "android.provider.Telephony.WAP_PUSH_RECEIVED";
    private static final int MMS_ATTACHMENT_MAX_BYTES = 300 * 1024;
    private static final int MMS_ATTACHMENTS_TOTAL_MAX_BYTES = 900 * 1024;

    // In-memory cache to prevent rapid duplicate processing (15 seconds TTL)
    private static final ConcurrentHashMap<String, Long> processedFingerprints = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 15000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !MMS_WAP_PUSH_ACTION.equals(intent.getAction())) {
            return;
        }

        String deviceId = SharedPreferenceHelper.getSharedPreferenceString(context, AppConstants.SHARED_PREFS_DEVICE_ID_KEY, "");
        String apiKey = SharedPreferenceHelper.getSharedPreferenceString(context, AppConstants.SHARED_PREFS_API_KEY_KEY, "");
        boolean receiveSMSEnabled = SharedPreferenceHelper.getSharedPreferenceBoolean(context, AppConstants.SHARED_PREFS_RECEIVE_SMS_ENABLED_KEY, false);

        if (deviceId.isEmpty() || apiKey.isEmpty() || !receiveSMSEnabled) {
            Log.d(TAG, "Device ID or API Key is empty or Receive SMS Feature is disabled");
            return;
        }

        ParsedMms parsedMms = readLatestIncomingMms(context);
        if (parsedMms == null) {
            Log.w(TAG, "Unable to resolve incoming MMS from content provider");
            return;
        }

        String fingerprint = generateFingerprint(
                parsedMms.sender,
                parsedMms.message,
                parsedMms.subject,
                parsedMms.receivedAtInMillis
        );

        long currentTime = System.currentTimeMillis();
        Long lastProcessedTime = processedFingerprints.get(fingerprint);
        if (lastProcessedTime != null && (currentTime - lastProcessedTime) < CACHE_TTL_MS) {
            Log.d(TAG, "Duplicate MMS detected in cache, skipping: " + fingerprint);
            return;
        }

        processedFingerprints.put(fingerprint, currentTime);
        cleanupCache(currentTime);

        SMSDTO receivedMmsDTO = new SMSDTO();
        receivedMmsDTO.setMessageKind("mms");
        receivedMmsDTO.setSender(parsedMms.sender);
        receivedMmsDTO.setMessage(parsedMms.message);
        receivedMmsDTO.setSubject(parsedMms.subject);
        receivedMmsDTO.setAttachments(parsedMms.attachments);
        receivedMmsDTO.setReceivedAtInMillis(parsedMms.receivedAtInMillis);
        receivedMmsDTO.setFingerprint(fingerprint);

        SMSReceivedWorker.enqueueWork(context, deviceId, apiKey, receivedMmsDTO);
        Log.d(TAG, "Queued inbound MMS from: " + parsedMms.sender);
    }

    private ParsedMms readLatestIncomingMms(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Telephony.Mms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.SUBJECT},
                    null,
                    null,
                    Telephony.Mms.DATE + " DESC"
            );

            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }

            int idIndex = cursor.getColumnIndex(Telephony.Mms._ID);
            int dateIndex = cursor.getColumnIndex(Telephony.Mms.DATE);
            int subjectIndex = cursor.getColumnIndex(Telephony.Mms.SUBJECT);

            if (idIndex < 0 || dateIndex < 0) {
                return null;
            }

            String mmsId = cursor.getString(idIndex);
            long dateSeconds = cursor.getLong(dateIndex);
            long receivedAtInMillis = dateSeconds > 0 ? dateSeconds * 1000L : System.currentTimeMillis();
            String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : "";

            String sender = getMmsSender(context, mmsId);
            String message = getMmsTextBody(context, mmsId);
            MessageAttachmentPayload[] attachments = getMmsAttachments(context, mmsId);

            if (TextUtils.isEmpty(sender)) {
                Log.w(TAG, "Skipping MMS because sender is empty");
                return null;
            }

            if (TextUtils.isEmpty(message) && (attachments == null || attachments.length == 0)) {
                Log.w(TAG, "Skipping MMS because both text and attachments are empty");
                return null;
            }

            ParsedMms parsedMms = new ParsedMms();
            parsedMms.sender = sender;
            parsedMms.message = message != null ? message : "";
            parsedMms.subject = subject != null ? subject : "";
            parsedMms.attachments = attachments;
            parsedMms.receivedAtInMillis = receivedAtInMillis;
            return parsedMms;
        } catch (Exception e) {
            Log.e(TAG, "Failed reading MMS from provider: " + e.getMessage());
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private String getMmsSender(Context context, String mmsId) {
        Cursor cursor = null;
        try {
            Uri uri = Uri.parse("content://mms/" + mmsId + "/addr");
            cursor = context.getContentResolver().query(
                    uri,
                    new String[]{"address", "type"},
                    "type=?",
                    new String[]{String.valueOf(MMS_ADDRESS_TYPE_FROM)},
                    null
            );

            if (cursor == null || !cursor.moveToFirst()) {
                return "";
            }

            int addressIndex = cursor.getColumnIndex("address");
            if (addressIndex < 0) {
                return "";
            }

            String address = cursor.getString(addressIndex);
            // Some carriers store a placeholder instead of a resolvable sender address.
            if (address == null || "insert-address-token".equalsIgnoreCase(address)) {
                return "";
            }
            return address;
        } catch (Exception e) {
            Log.e(TAG, "Failed reading MMS sender: " + e.getMessage());
            return "";
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private String getMmsTextBody(Context context, String mmsId) {
        Cursor cursor = null;
        StringBuilder body = new StringBuilder();
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse("content://mms/part"),
                    new String[]{"_id", "ct", "text", "_data"},
                    "mid=?",
                    new String[]{mmsId},
                    null
            );

            if (cursor == null) {
                return "";
            }

            int idIndex = cursor.getColumnIndex("_id");
            int ctIndex = cursor.getColumnIndex("ct");
            int textIndex = cursor.getColumnIndex("text");
            int dataIndex = cursor.getColumnIndex("_data");

            while (cursor.moveToNext()) {
                String contentType = ctIndex >= 0 ? cursor.getString(ctIndex) : null;
                if (!"text/plain".equalsIgnoreCase(contentType)) {
                    continue;
                }

                String data = dataIndex >= 0 ? cursor.getString(dataIndex) : null;
                String text = textIndex >= 0 ? cursor.getString(textIndex) : null;

                if (!TextUtils.isEmpty(data) && idIndex >= 0) {
                    String partId = cursor.getString(idIndex);
                    text = readMmsPartText(context, partId);
                }

                if (!TextUtils.isEmpty(text)) {
                    if (body.length() > 0) {
                        body.append('\n');
                    }
                    body.append(text);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed reading MMS body: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return body.toString();
    }

    private String readMmsPartText(Context context, String partId) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse("content://mms/part/" + partId),
                    new String[]{"text"},
                    null,
                    null,
                    null
            );
            if (cursor != null && cursor.moveToFirst()) {
                int textIndex = cursor.getColumnIndex("text");
                if (textIndex >= 0) {
                    String text = cursor.getString(textIndex);
                    return text != null ? text : "";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed reading MMS part text: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return "";
    }

    private MessageAttachmentPayload[] getMmsAttachments(Context context, String mmsId) {
        Cursor cursor = null;
        ArrayList<MessageAttachmentPayload> attachments = new ArrayList<>();
        int totalBytes = 0;
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse("content://mms/part"),
                    new String[]{"_id", "ct", "name", "cl"},
                    "mid=?",
                    new String[]{mmsId},
                    null
            );

            if (cursor == null) {
                return new MessageAttachmentPayload[0];
            }

            int idIndex = cursor.getColumnIndex("_id");
            int ctIndex = cursor.getColumnIndex("ct");
            int nameIndex = cursor.getColumnIndex("name");
            int clIndex = cursor.getColumnIndex("cl");

            while (cursor.moveToNext()) {
                String contentType = ctIndex >= 0 ? cursor.getString(ctIndex) : null;
                if (TextUtils.isEmpty(contentType) || contentType.toLowerCase().startsWith("text/") || "application/smil".equalsIgnoreCase(contentType)) {
                    continue;
                }

                if (idIndex < 0) {
                    continue;
                }

                String partId = cursor.getString(idIndex);
                if (TextUtils.isEmpty(partId)) {
                    continue;
                }

                AttachmentData attachmentData = readAttachmentAsDataUrl(context, partId, contentType);
                if (attachmentData == null) {
                    continue;
                }
                if ((totalBytes + attachmentData.sizeBytes) > MMS_ATTACHMENTS_TOTAL_MAX_BYTES) {
                    Log.w(TAG, "Skipping MMS attachment " + partId + " because total size limit would be exceeded");
                    continue;
                }
                totalBytes += attachmentData.sizeBytes;

                MessageAttachmentPayload attachment = new MessageAttachmentPayload();
                attachment.setUrl(attachmentData.dataUrl);
                attachment.setMimeType(contentType);
                attachment.setSizeBytes((long) attachmentData.sizeBytes);

                String fileName = null;
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex);
                }
                if (TextUtils.isEmpty(fileName) && clIndex >= 0) {
                    fileName = cursor.getString(clIndex);
                }
                attachment.setFileName(fileName);

                attachments.add(attachment);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed reading MMS attachments: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return attachments.toArray(new MessageAttachmentPayload[0]);
    }

    private AttachmentData readAttachmentAsDataUrl(Context context, String partId, String mimeType) {
        InputStream inputStream = null;
        ByteArrayOutputStream outputStream = null;
        try {
            Uri partUri = Uri.parse("content://mms/part/" + partId);
            inputStream = context.getContentResolver().openInputStream(partUri);
            if (inputStream == null) {
                return null;
            }

            outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            int totalBytes = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                totalBytes += bytesRead;
                if (totalBytes > MMS_ATTACHMENT_MAX_BYTES) {
                    Log.w(TAG, "Skipping MMS attachment " + partId + " because it exceeds max size");
                    return null;
                }
                outputStream.write(buffer, 0, bytesRead);
            }

            byte[] data = outputStream.toByteArray();
            String encoded = Base64.encodeToString(data, Base64.NO_WRAP);
            AttachmentData result = new AttachmentData();
            result.sizeBytes = data.length;
            result.dataUrl = "data:" + mimeType + ";base64," + encoded;
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed reading MMS attachment content: " + e.getMessage());
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {}
            }
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private String generateFingerprint(String sender, String message, String subject, long timestamp) {
        try {
            String data = (sender != null ? sender : "") + "|" +
                    (message != null ? message : "") + "|" +
                    (subject != null ? subject : "") + "|" +
                    timestamp;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(data.getBytes("UTF-8"));

            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error generating MMS fingerprint: " + e.getMessage());
            return (sender != null ? sender : "") + "_" +
                    (message != null ? message : "") + "_" +
                    (subject != null ? subject : "") + "_" +
                    timestamp;
        }
    }

    private void cleanupCache(long currentTime) {
        if (processedFingerprints.size() > 100) {
            Set<String> keysToRemove = new HashSet<>();
            for (String key : processedFingerprints.keySet()) {
                Long timestamp = processedFingerprints.get(key);
                if (timestamp != null && (currentTime - timestamp) > CACHE_TTL_MS) {
                    keysToRemove.add(key);
                }
            }
            for (String key : keysToRemove) {
                processedFingerprints.remove(key);
            }
            Log.d(TAG, "Cleaned up " + keysToRemove.size() + " expired MMS cache entries");
        }
    }

    private static class ParsedMms {
        String sender;
        String message;
        String subject;
        MessageAttachmentPayload[] attachments;
        long receivedAtInMillis;
    }

    private static class AttachmentData {
        String dataUrl;
        int sizeBytes;
    }
}
