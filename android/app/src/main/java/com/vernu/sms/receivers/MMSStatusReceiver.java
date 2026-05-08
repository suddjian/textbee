package com.vernu.sms.receivers;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;

import com.klinker.android.send_message.MmsSentReceiver;
import com.vernu.sms.dtos.SMSDTO;
import com.vernu.sms.helpers.SMSHelper;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class MMSStatusReceiver extends MmsSentReceiver {
    private static final String TAG = "MMSStatusReceiver";
    private static final Map<Integer, String> RESULT_CODE_NAMES = buildResultCodeMap();

    @Override
    public void onMessageStatusUpdated(Context context, Intent intent, int resultCode) {
        String smsId = intent.getStringExtra("sms_id");
        String smsBatchId = intent.getStringExtra("sms_batch_id");

        if (smsId == null || smsId.trim().isEmpty()) {
            Log.e(TAG, "Missing sms_id for MMS status callback");
            return;
        }

        SMSDTO smsDTO = new SMSDTO();
        smsDTO.setSmsId(smsId);
        smsDTO.setSmsBatchId(smsBatchId);

        long timestamp = System.currentTimeMillis();
        if (resultCode == Activity.RESULT_OK) {
            smsDTO.setStatus("SENT");
            smsDTO.setSentAtInMillis(timestamp);
            Log.d(TAG, "MMS sent successfully - ID: " + smsId);
        } else {
            String errorCodeName = resolveResultCodeName(resultCode);
            String errorMessage = errorCodeName != null
                    ? errorCodeName
                    : "Unknown MMS send failure (code " + resultCode + ")";

            smsDTO.setStatus("FAILED");
            smsDTO.setFailedAtInMillis(timestamp);
            smsDTO.setErrorCode(String.valueOf(resultCode));
            smsDTO.setErrorMessage(errorMessage);
            Log.e(TAG, "MMS failed to send - ID: " + smsId + ", Error: " + errorMessage);
        }

        SMSHelper.enqueueStatusUpdate(context, smsDTO);
    }

    private static String resolveResultCodeName(int resultCode) {
        return RESULT_CODE_NAMES.get(resultCode);
    }

    private static Map<Integer, String> buildResultCodeMap() {
        Map<Integer, String> map = new HashMap<>();
        for (Class<?> clazz : new Class<?>[]{SmsManager.class, Activity.class}) {
            try {
                for (Field field : clazz.getDeclaredFields()) {
                    if (field.getType() != int.class) continue;
                    if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) continue;
                    if (!field.getName().startsWith("RESULT_")) continue;
                    field.setAccessible(true);
                    int code = field.getInt(null);
                    map.put(code, clazz.getSimpleName() + "." + field.getName());
                }
            } catch (Exception e) {
                Log.w(TAG, "Reflection failed for " + clazz.getSimpleName() + ": " + e.getMessage());
            }
        }
        return map;
    }
}
