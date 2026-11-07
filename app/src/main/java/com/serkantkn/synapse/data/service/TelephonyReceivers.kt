package com.serkantkn.synapse.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * BroadcastReceiver to detect incoming call states.
 * Notifies SynapseService which then forwards the event to macOS.
 */
class CallReceiver(private val onCallEvent: (String, String?) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            
            Log.d("CallReceiver", "Phone state changed: $state")
            
            state?.let {
                onCallEvent(it, number)
            }
        }
    }
}

/**
 * BroadcastReceiver to detect incoming SMS messages.
 */
class SmsReceiver(private val onSmsReceived: (String, String) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle = intent.extras
            val pdus = bundle?.get("pdus") as Array<*>?
            pdus?.forEach { pdu ->
                val smsMessage = android.telephony.SmsMessage.createFromPdu(pdu as ByteArray)
                val sender = smsMessage.displayOriginatingAddress
                val body = smsMessage.displayMessageBody
                Log.d("SmsReceiver", "SMS received (${body?.length ?: 0} chars)")
                onSmsReceived(sender, body)
            }
        }
    }
}
