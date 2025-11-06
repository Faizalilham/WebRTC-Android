package dev.faizal.webrtc

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import dev.faizal.webrtc.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

// Data class untuk menyimpan info call
data class InfoCall(
    val caller: String,
    val answeredBy: String,
    val startCall: String,
    val endCall: String,
    val howLong: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference

    private val currentUser = "user1"
    private val recipients = listOf("user2", "user3")

    private var currentCallId: String? = null
    private var panicCallManager: PanicCallManager? = null

    private val PERMISSION_REQUEST_CODE = 1001
    private val TAG = "MainActivity"

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager

    private var incomingCallDialog: AlertDialog? = null
    private var pendingCallId: String? = null
    private var callEndListener: ValueEventListener? = null

    // ========== TRACKING DATA UNTUK InfoCall ==========
    private var callStartTime: Long = 0
    private var currentCaller: String = ""
    private var currentAnsweredBy: String = ""

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance().reference
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        requestAudioPermissions()

        panicCallManager = PanicCallManager(
            this,
            currentUser,
            {
                // Connected callback
                runOnUiThread {
                    binding.btnRegister.text = "📞 CALL CONNECTED!"
                    enableSpeakerphone()

                    // CATAT WAKTU MULAI CALL
                    callStartTime = System.currentTimeMillis()
                    Log.d(TAG, "Call connected at: ${dateFormat.format(Date(callStartTime))}")
                }
            },
            {
                // Ended callback
                runOnUiThread {
                    binding.btnRegister.text = "Call ended"
                    disableSpeakerphone()

                    // HITUNG DAN SIMPAN DATA CALL
                    saveCallInfo()

                    resetUI()
                    Log.d(TAG, "Call ended")
                }
            }
        )
        panicCallManager?.initialize()

        listenForIncomingCalls()

        binding.btnStartCall.setOnClickListener {
            triggerPanicCall()
        }

        binding.btnRegister.text = "Ready as $currentUser"
        Log.d(TAG, "MainActivity initialized for user: $currentUser")
    }

    // ========== FUNCTION UNTUK SAVE CALL INFO ==========
    private fun saveCallInfo() {
        if (callStartTime == 0L) {
            Log.w(TAG, "Call start time not recorded")
            return
        }

        val callEndTime = System.currentTimeMillis()
        val duration = callEndTime - callStartTime

        // Format durasi menjadi HH:mm:ss
        val hours = duration / 3600000
        val minutes = (duration % 3600000) / 60000
        val seconds = (duration % 60000) / 1000
        val formattedDuration = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        // Buat object InfoCall
        val infoCall = InfoCall(
            caller = currentCaller,
            answeredBy = currentAnsweredBy,
            startCall = dateFormat.format(Date(callStartTime)),
            endCall = dateFormat.format(Date(callEndTime)),
            howLong = formattedDuration
        )

        Log.d(TAG, "Call Info Ready to Send:")
        Log.d(TAG, "  Caller: ${infoCall.caller}")
        Log.d(TAG, "  Answered By: ${infoCall.answeredBy}")
        Log.d(TAG, "  Start: ${infoCall.startCall}")
        Log.d(TAG, "  End: ${infoCall.endCall}")
        Log.d(TAG, "  Duration: ${infoCall.howLong}")

        // TODO: Kirim ke API
        sendCallInfoToApi(infoCall)

        // Reset tracking variables
        callStartTime = 0
        currentCaller = ""
        currentAnsweredBy = ""
    }

    private fun sendCallInfoToApi(infoCall: InfoCall) {
        // TODO: Implementasi API call disini
        // Contoh menggunakan Retrofit, OkHttp, atau library lainnya
        Log.d(TAG, "Ready to send to API: $infoCall")
    }

    private fun enableSpeakerphone() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
            Log.d(TAG, "Speakerphone enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling speakerphone", e)
        }
    }

    private fun disableSpeakerphone() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            Log.d(TAG, "Speakerphone disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling speakerphone", e)
        }
    }

    private fun requestAudioPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Audio permissions granted")
                Toast.makeText(this, "Audio permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "Audio permissions denied")
                Toast.makeText(this, "Audio permissions required!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun listenForIncomingCalls() {
        database.child("panic_calls")
            .orderByChild("timestamp")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    handleIncomingCall(snapshot)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    handleIncomingCall(snapshot)
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Database error: ${error.message}")
                }
            })
    }

    private fun handleIncomingCall(snapshot: DataSnapshot) {
        val call = snapshot.getValue<Map<String, Any>>() ?: return

        val callId = call["callId"] as? String ?: return
        val from = call["from"] as? String ?: return
        val to = call["to"] as? List<*> ?: return
        val status = call["status"] as? String ?: return
        val answeredBy = call["answeredBy"] as? String ?: ""

        Log.d(TAG, "Call detected: callId=$callId, from=$from, status=$status, to=$to, answeredBy=$answeredBy")

        if (callId == pendingCallId) {
            when (status) {
                "answered" -> {
                    if (answeredBy.isNotEmpty() && answeredBy != currentUser) {
                        Log.d(TAG, "Call answered by $answeredBy, dismissing dialog")
                        dismissIncomingCallDialog()
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Call answered by $answeredBy",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                "ended", "cancelled", "rejected" -> {
                    Log.d(TAG, "Call $status, dismissing dialog")
                    dismissIncomingCallDialog()
                }
            }
        }

        if (to.contains(currentUser) &&
            status == "ringing" &&
            from != currentUser &&
            currentCallId == null &&
            incomingCallDialog == null) {

            Log.d(TAG, "Incoming call from $from for $currentUser")
            showIncomingCallDialog(callId, from)
        }
    }

    private fun showIncomingCallDialog(callId: String, fromUser: String) {
        dismissIncomingCallDialog()

        pendingCallId = callId

        runOnUiThread {
            incomingCallDialog = AlertDialog.Builder(this)
                .setTitle("🚨 PANIC CALL")
                .setMessage("Incoming panic call from $fromUser")
                .setCancelable(false)
                .setPositiveButton("ANSWER") { dialog, _ ->
                    dialog.dismiss()
                    incomingCallDialog = null
                    pendingCallId = null
                    answerCall(callId, fromUser)
                }
                .setNegativeButton("REJECT") { dialog, _ ->
                    dialog.dismiss()
                    incomingCallDialog = null
                    pendingCallId = null
                    rejectCall(callId)
                }
                .create()

            incomingCallDialog?.show()
            Log.d(TAG, "Incoming call dialog shown for callId: $callId")
        }
    }

    private fun dismissIncomingCallDialog() {
        runOnUiThread {
            incomingCallDialog?.dismiss()
            incomingCallDialog = null
            pendingCallId = null
        }
    }

    private fun answerCall(callId: String, fromUser: String) {
        currentCallId = callId

        // SIMPAN DATA CALLER DAN ANSWERER
        currentCaller = fromUser
        currentAnsweredBy = currentUser

        binding.btnRegister.text = "Answering call from $fromUser..."
        Log.d(TAG, "Answering call: $callId")

        database.child("panic_calls").child(callId).updateChildren(
            mapOf(
                "status" to "answered",
                "answeredBy" to currentUser
            )
        ).addOnSuccessListener {
            Log.d(TAG, "Call status updated to answered")

            panicCallManager?.answerIncomingCall(callId)

            binding.btnStartCall.text = "End Call"
            binding.btnStartCall.setOnClickListener {
                endCall()
            }

            listenForCallEnd(callId)
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to update call status", e)
            Toast.makeText(this, "Failed to answer call", Toast.LENGTH_SHORT).show()
        }
    }

    private fun rejectCall(callId: String) {
        Log.d(TAG, "Rejecting call: $callId")
        database.child("panic_calls").child(callId).child("status").setValue("rejected")
        Toast.makeText(this, "Call rejected", Toast.LENGTH_SHORT).show()
    }

    private fun triggerPanicCall() {
        if (currentCallId != null) {
            Toast.makeText(this, "Panic call already active!", Toast.LENGTH_SHORT).show()
            return
        }

        // SIMPAN DATA CALLER (currentUser adalah caller)
        currentCaller = currentUser

        Log.d(TAG, "Triggering panic call to: $recipients")
        binding.btnRegister.text = "Sending panic call..."

        panicCallManager?.startPanicCall(recipients)
        binding.btnRegister.text = "Panic call sent! Waiting for answer..."

        listenForCallAnswer()
    }

    private fun listenForCallAnswer() {
        database.child("panic_calls")
            .orderByChild("from")
            .equalTo(currentUser)
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    checkCallAnswer(snapshot)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    checkCallAnswer(snapshot)
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Database error: ${error.message}")
                }
            })
    }

    private fun checkCallAnswer(snapshot: DataSnapshot) {
        val call = snapshot.getValue<Map<String, Any>>() ?: return
        val status = call["status"] as? String
        val answeredBy = call["answeredBy"] as? String
        val callId = call["callId"] as? String

        Log.d(TAG, "Call status update: status=$status, answeredBy=$answeredBy, callId=$callId")

        if (status == "answered" &&
            answeredBy != null &&
            answeredBy.isNotEmpty() &&
            callId != null &&
            currentCallId == null) {

            currentCallId = callId

            // SIMPAN DATA ANSWERER
            currentAnsweredBy = answeredBy

            binding.btnRegister.text = "Call answered by $answeredBy. Connecting..."
            Log.d(TAG, "Call answered by $answeredBy, sending offer...")

            panicCallManager?.onCallShouldStartAsCaller()

            binding.btnStartCall.text = "End Call"
            binding.btnStartCall.setOnClickListener { endCall() }

            listenForCallEnd(callId)
        }
    }

    private fun listenForCallEnd(callId: String) {
        callEndListener?.let { listener ->
            database.child("panic_calls").child(callId).child("status")
                .removeEventListener(listener)
        }

        callEndListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue<String>()
                Log.d(TAG, "Call status changed to: $status for callId: $callId")

                if (status == "ended" && currentCallId == callId) {
                    Log.d(TAG, "Remote ended the call")
                    runOnUiThread {
                        panicCallManager?.endCall()
                        disableSpeakerphone()
                        resetUI()
                        Toast.makeText(
                            this@MainActivity,
                            "Call ended by remote user",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error listening for call end: ${error.message}")
            }
        }

        database.child("panic_calls").child(callId).child("status")
            .addValueEventListener(callEndListener!!)
    }

    private fun removeCallEndListener() {
        currentCallId?.let { callId ->
            callEndListener?.let { listener ->
                database.child("panic_calls").child(callId).child("status")
                    .removeEventListener(listener)
                callEndListener = null
            }
        }
    }

    private fun endCall() {
        Log.d(TAG, "Ending call")

        currentCallId?.let { callId ->
            database.child("panic_calls").child(callId).child("status").setValue("ended")
                .addOnSuccessListener {
                    Log.d(TAG, "Call status updated to ended in Firebase")
                }
        }

        removeCallEndListener()

        panicCallManager?.endCall()
        disableSpeakerphone()
        resetUI()
        Toast.makeText(this, "Call ended", Toast.LENGTH_SHORT).show()
    }

    private fun resetUI() {
        currentCallId = null
        binding.btnRegister.text = "Ready as $currentUser"
        binding.btnStartCall.text = "🚨 PANIC CALL"
        binding.btnStartCall.setOnClickListener { triggerPanicCall() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroyed")
        dismissIncomingCallDialog()
        removeCallEndListener()
        panicCallManager?.endCall()
        disableSpeakerphone()
    }
}