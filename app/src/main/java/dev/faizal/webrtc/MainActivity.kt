package dev.faizal.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import dev.faizal.webrtc.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var database: DatabaseReference
    private val currentUser = "gebby" // PENTING: Ganti sesuai HP (user1/user2/user3)
    private val recipients = listOf("gebby", "user9")

    private var currentCallId: String? = null
    private var panicCallManager: PanicCallManager? = null

    private val PERMISSION_REQUEST_CODE = 1001

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager

    // Proximity sensor untuk deteksi HP didekatkan ke telinga
    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isInCall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance().reference

        // Setup Audio Manager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Setup Proximity Sensor
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        // Setup WakeLock untuk matikan layar saat didekatkan ke telinga
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            "WebRTC:ProximityLock"
        )

        requestAudioPermissions()

        // Initialize PanicCallManager
        panicCallManager = PanicCallManager(
            this,
            currentUser,
            { /* connected */
                runOnUiThread {
                    binding.btnRegister.text = "📞 CALL CONNECTED!"
                    startCallAudioMode()
                }
            },
            { /* ended */
                runOnUiThread {
                    binding.btnRegister.text = "Call ended"
                    stopCallAudioMode()
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

    private fun startCallAudioMode() {
        isInCall = true

        // ✅ MODE_IN_COMMUNICATION = Mode khusus voice call
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // ✅ CEK BLUETOOTH DULU
        if (isBluetoothHeadsetConnected()) {
            // Gunakan Bluetooth (TWS/Headset)
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            audioManager.isSpeakerphoneOn = false
            Toast.makeText(this, "🎧 Audio via Bluetooth", Toast.LENGTH_SHORT).show()
        } else {
            // Gunakan earpiece (speaker telinga)
            audioManager.isSpeakerphoneOn = false
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            Toast.makeText(this, "📱 Audio via Earpiece", Toast.LENGTH_SHORT).show()
        }

        // ✅ MIKROFON SETTINGS
        audioManager.isMicrophoneMute = false

        // ✅ VOLUME OPTIMAL (85% lebih baik dari 100% - menghindari distorsi)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            (maxVolume * 0.35).toInt(),
            0
        )

        // ✅ TAMBAHAN: Request audio focus untuk prioritas
        val result = audioManager.requestAudioFocus(
            null,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        )

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d("MainActivity", "✅ Audio focus granted")
        }

        // Aktifkan proximity sensor HANYA jika tidak pakai Bluetooth
        if (!isBluetoothHeadsetConnected()) {
            proximitySensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    // ✅ FUNGSI BARU: Cek apakah Bluetooth headset terkoneksi
    private fun isBluetoothHeadsetConnected(): Boolean {
        return try {
            val devices = audioManager.javaClass
                .getMethod("getConnectedDevices", Int::class.javaPrimitiveType)
                .invoke(audioManager, 2) as? List<*> // 2 = TYPE_BLUETOOTH_SCO

            devices != null && devices.isNotEmpty()
        } catch (e: Exception) {
            // Fallback: cek dengan cara sederhana
            audioManager.isBluetoothScoAvailableOffCall
        }
    }

    private fun stopCallAudioMode() {
        isInCall = false

        // Release audio focus
        audioManager.abandonAudioFocus(null)

        // Kembalikan ke mode normal
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false

        // Stop Bluetooth SCO jika aktif
        if (audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }

        // Unregister sensor
        sensorManager.unregisterListener(this)

        // Release wake lock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    // Proximity Sensor Listener
    override fun onSensorChanged(event: SensorEvent?) {
        // ✅ PROXIMITY SENSOR DISABLED JIKA PAKAI BLUETOOTH
        if (!isInCall || isBluetoothHeadsetConnected()) return

        event?.let {
            if (it.sensor.type == Sensor.TYPE_PROXIMITY) {
                val distance = it.values[0]
                val maxRange = it.sensor.maximumRange

                // Jika HP didekatkan ke telinga (distance mendekati 0)
                if (distance < maxRange / 2) {
                    // Gunakan earpiece (speaker telinga)
                    audioManager.isSpeakerphoneOn = false

                    // Matikan layar untuk hemat baterai
                    if (wakeLock?.isHeld == false) {
                        wakeLock?.acquire(10*60*1000L /*10 minutes*/)
                    }
                } else {
                    // HP dijauhkan dari telinga, gunakan speaker
                    audioManager.isSpeakerphoneOn = true

                    // Volume lebih tinggi untuk speaker
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        maxVolume,
                        0
                    )

                    // Hidupkan layar kembali
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun requestAudioPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.BLUETOOTH_CONNECT // ✅ TAMBAHAN untuk Android 12+
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
        stopCallAudioMode()
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
        stopCallAudioMode()
    }
}