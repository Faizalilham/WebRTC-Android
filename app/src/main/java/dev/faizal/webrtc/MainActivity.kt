package dev.faizal.webrtc

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import dev.faizal.webrtc.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference
    private val currentUser = "faizal" // PENTING: Ganti sesuai HP (user1/user2/user3)
    private val recipients = listOf("sigit", "user9")

    private var currentCallId: String? = null
    private var panicCallManager: PanicCallManager? = null

    private val PERMISSION_REQUEST_CODE = 1001

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance().reference

        // Setup Audio Manager untuk speaker
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        requestAudioPermissions()

        // Initialize PanicCallManager
        panicCallManager = PanicCallManager(
            this,
            currentUser,
            { /* connected */
                runOnUiThread {
                    binding.btnRegister.text = "📞 CALL CONNECTED!"
                    enableSpeakerphone()
                }
            },
            { /* ended */
                runOnUiThread {
                    binding.btnRegister.text = "Call ended"
                    disableSpeakerphone()
                    resetUI()
                }
            }
        )
        panicCallManager?.initialize()

        // KUNCI: Dengarkan panggilan masuk
        listenForIncomingCalls()

        binding.btnStartCall.setOnClickListener {
            triggerPanicCall()
        }
    }

    private fun enableSpeakerphone() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        audioManager.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
            0
        )
    }

    private fun disableSpeakerphone() {
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
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

    // ==================== RECEIVER LOGIC (HP2) ====================
    private fun listenForIncomingCalls() {
        database.child("panic_calls")
            .orderByChild("timestamp")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val call = snapshot.getValue<Map<String, Any>>() ?: return

                    val callId = call["callId"] as? String ?: return
                    val from = call["from"] as? String ?: return
                    val to = call["to"] as? List<String> ?: return
                    val status = call["status"] as? String ?: return

                    // Cek apakah panggilan untuk user ini
                    if (to.contains(currentUser) && status == "ringing" && from != currentUser) {
                        showIncomingCallDialog(callId, from)
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun showIncomingCallDialog(callId: String, fromUser: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("🚨 PANIC CALL")
                .setMessage("Incoming panic call from $fromUser")
                .setCancelable(false)
                .setPositiveButton("ANSWER") { dialog, _ ->
                    dialog.dismiss()
                    answerCall(callId, fromUser)
                }
                .setNegativeButton("REJECT") { dialog, _ ->
                    dialog.dismiss()
                    rejectCall(callId)
                }
                .show()
        }
    }

    private fun answerCall(callId: String, fromUser: String) {
        currentCallId = callId
        binding.btnRegister.text = "Answering call from $fromUser..."

        // Update status di Firebase
        database.child("panic_calls").child(callId).updateChildren(
            mapOf(
                "status" to "answered",
                "answeredBy" to currentUser
            )
        )

        // Start WebRTC sebagai receiver
        panicCallManager?.answerIncomingCall(callId)

        binding.btnStartCall.text = "End Call"
        binding.btnStartCall.setOnClickListener {
            endCall()
        }
    }

    private fun rejectCall(callId: String) {
        database.child("panic_calls").child(callId).child("status").setValue("rejected")
    }

    // ==================== CALLER LOGIC (HP1) ====================
    private fun triggerPanicCall() {
        if (currentCallId != null) {
            Toast.makeText(this, "Panic call already active!", Toast.LENGTH_SHORT).show()
            return
        }

        panicCallManager?.startPanicCall(recipients)
        binding.btnRegister.text = "Panic call sent! Waiting for answer..."

        listenForCallAnswer()
    }

    private fun listenForCallAnswer() {
        database.child("panic_calls")
            .orderByChild("from")
            .equalTo(currentUser)
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {}

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    val call = snapshot.getValue<Map<String, Any>>() ?: return
                    val status = call["status"] as? String
                    val answeredBy = call["answeredBy"] as? String
                    val callId = call["callId"] as? String

                    if (status == "answered" && answeredBy != null && callId != null) {
                        currentCallId = callId
                        binding.btnRegister.text = "Call answered by $answeredBy. Connecting..."

                        panicCallManager?.onCallShouldStartAsCaller()

                        binding.btnStartCall.text = "End Call"
                        binding.btnStartCall.setOnClickListener { endCall() }
                    }
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun endCall() {
        panicCallManager?.endCall()
        disableSpeakerphone()
        resetUI()
    }

    private fun resetUI() {
        currentCallId = null
        binding.btnRegister.text = "Ready"
        binding.btnStartCall.text = "🚨 PANIC CALL"
        binding.btnStartCall.setOnClickListener { triggerPanicCall() }
    }

    override fun onDestroy() {
        super.onDestroy()
        panicCallManager?.endCall()
        disableSpeakerphone()
    }
}