package io.github.christiantwu.longhand.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.christiantwu.longhand.TAG
import java.util.Locale

/** One row of the phone's call log. */
data class CallLogEntry(val number: String, val date: Long, val durationSec: Long, val type: Int, val cachedName: String?)

data class Caller(val number: String?, val name: String?, val lookupKey: String?, val direction: Int?) {
    /**
     * This lookup, filled in from an [earlier] one. A lookup with less access than before (call log
     * access taken away, say) mustn't erase what was found then, so earlier details stay unless this
     * lookup found a different number.
     */
    fun orElse(earlier: Caller, sameNumber: (String, String) -> Boolean): Caller {
        if (number != null && earlier.number != null && !sameNumber(number, earlier.number)) return this
        return Caller(number ?: earlier.number, name ?: earlier.name, lookupKey ?: earlier.lookupKey, direction ?: earlier.direction)
    }
}

object CallMatcher {

    private val ANSWERED = setOf(CallLog.Calls.INCOMING_TYPE, CallLog.Calls.OUTGOING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE)

    /**
     * Picks the call that a recording belongs to. The recorder stops writing when the call
     * ends, so the file's modification time should land shortly after the call log's
     * start + duration (the log's start includes ringing, hence the allowance before).
     * A recording can't be longer than the call, but can be shorter if it started late.
     *
     * @param recordingMs the recording's length, or 0 when unknown.
     */
    fun best(calls: List<CallLogEntry>, fileTime: Long, recordingMs: Long): CallLogEntry? =
        calls.asSequence()
            .filter { it.type in ANSWERED && it.durationSec > 0 }
            .filter { recordingMs <= 0 || recordingMs <= it.durationSec * 1000 + 15_000 }
            .map { it to fileTime - (it.date + it.durationSec * 1000) }
            .filter { (_, offset) -> offset in -30_000L..180_000L }
            .minByOrNull { (_, offset) -> kotlin.math.abs(offset) }
            ?.first

    private val plusNumber = Regex("""\+\d{7,15}""")
    private val digitsToken = Regex("""(?<![\d])\d{10,15}(?![\d])""")

    /**
     * A phone number in a recording's file name, if the recorder put one there: either
     * "+" followed by digits, or a run of 10-15 digits. Shorter runs are dates and times.
     */
    fun numberFromFileName(name: String): String? {
        val base = name.substringBeforeLast('.')
        return plusNumber.find(base)?.value ?: digitsToken.find(base)?.value
    }
}

object CallerLookup {

    fun canReadCallLog(context: Context) = granted(context, Manifest.permission.READ_CALL_LOG)
    fun canReadContacts(context: Context) = granted(context, Manifest.permission.READ_CONTACTS)

    fun sameNumber(a: String, b: String) = PhoneNumberUtils.areSamePhoneNumber(a, b, Locale.getDefault().country.lowercase(Locale.ROOT))

    private fun accessPrefs(context: Context) = context.getSharedPreferences("caller_access", Context.MODE_PRIVATE)

    /** Whether call log or contacts access is newer than at the last lookups, granted here or in system settings. */
    fun accessGrown(context: Context, callLog: Boolean, contacts: Boolean): Boolean {
        val prefs = accessPrefs(context)
        return (callLog && !prefs.getBoolean("call_log", false)) || (contacts && !prefs.getBoolean("contacts", false))
    }

    /** Records the access the folder scan's lookups were made with. */
    fun rememberAccess(context: Context, callLog: Boolean, contacts: Boolean) =
        accessPrefs(context).edit { putBoolean("call_log", callLog).putBoolean("contacts", contacts) }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Works out who a recording was with, using whatever access the user has granted. */
    fun resolve(context: Context, rec: Recording): Caller {
        val call = if (canReadCallLog(context)) matchCall(context, rec) else null
        val number = call?.number?.takeIf { it.isNotBlank() } ?: CallMatcher.numberFromFileName(rec.displayName)
        val contact = number?.let { contactFor(context, it) }
        return Caller(
            number = number,
            name = contact?.first ?: call?.cachedName?.takeIf { it.isNotBlank() },
            lookupKey = contact?.second,
            direction = call?.type,
        )
    }

    private fun matchCall(context: Context, rec: Recording): CallLogEntry? = try {
        val from = rec.lastModified - 6 * 3600_000L
        val to = rec.lastModified + 5 * 60_000L
        val calls = ArrayList<CallLogEntry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
            "${CallLog.Calls.DATE} BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                calls += CallLogEntry(c.getString(0) ?: "", c.getLong(1), c.getLong(2), c.getInt(3), c.getString(4))
            }
        }
        CallMatcher.best(calls, rec.lastModified, rec.durationMs)
    } catch (e: SecurityException) {
        Log.w(TAG, "call log not readable: ${e.message}")
        null
    }

    /** Contact name and lookup key for a number, or null (also when contacts access is off). */
    fun contactFor(context: Context, number: String): Pair<String, String?>? {
        if (!canReadContacts(context)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.LOOKUP_KEY), null, null, null,
            )?.use { c -> if (c.moveToFirst()) (c.getString(0) ?: return null) to c.getString(1) else null }
        } catch (e: Exception) {
            Log.w(TAG, "contact lookup failed: ${e.message}")
            null
        }
    }

    /** A contact phone number the user picked by hand (ACTION_PICK on phone numbers). */
    fun fromPickedPhone(context: Context, uri: Uri): Caller? = try {
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) Caller(c.getString(0), c.getString(1), c.getString(2), null) else null }
    } catch (e: Exception) {
        Log.w(TAG, "picked contact unreadable: ${e.message}")
        null
    }

    fun formatNumber(number: String): String =
        PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number
}
