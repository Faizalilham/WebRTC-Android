package dev.faizal.webrtc


import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.getValue
import dev.faizal.webrtc.databinding.ActivityCallBinding
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class CallActivity : AppCompatActivity() {

    private lateinit var binding : ActivityCallBinding

    private lateinit var database: DatabaseReference
    private val currentUserId = "faizal" // Ganti sesuai device/user
    private var currentCallId: String? = null
    private var isCallActive = false

    private var webRtcClient: WebRtcClient? = null
    private var firebaseSignaling: FirebaseSignaling? = null

    private var currentCallerId: String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)


        database = FirebaseDatabase.getInstance().reference
        val manager = PanicCallManager(this, "faizal", { /* connected */ }, { /* ended */ })
        manager.initialize()

        listenForIncomingPanicCalls()
        binding.btnAnswer.setOnClickListener {
            onAnswerClicked()
        }
    }

    private fun listenForIncomingPanicCalls() {
        val callsRef = database.child("panic_calls")

        callsRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                checkCallForCurrentUser(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                checkCallForCurrentUser(snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun checkCallForCurrentUser(snapshot: DataSnapshot) {
        val call = snapshot.getValue<Map<String, Any>>()
        if (call == null || call["status"] != "ringing") return

        val recipients = call["to"] as? List<*>
        if (recipients?.contains(currentUserId) == true && !isCallActive) {
            currentCallId = snapshot.key
            currentCallerId = call["from"] as? String // ✅ Simpan caller ID
            isCallActive = true
            binding.tvCallerName.text = "Panic Call from ${currentCallerId}!"
            binding.btnAnswer.isEnabled = true

            Toast.makeText(this, "Panic call incoming!", Toast.LENGTH_LONG).show()
        }
    }

    fun onAnswerClicked() {
        if (!isCallActive || currentCallId.isNullOrBlank() || currentCallerId.isNullOrBlank()) return

        val callRef = database.child("panic_calls").child(currentCallId!!)
        callRef.child("status").setValue("answered")
        callRef.child("answeredBy").setValue(currentUserId)

        binding.tvCallStatus.text = "Connected to $currentCallerId"
        binding.btnAnswer.isEnabled = false

        startWebRtcCall(currentCallId!!, currentCallerId!!) // ✅ Gunakan currentCallerId
        Toast.makeText(this, "Call answered!", Toast.LENGTH_SHORT).show()
    }
    private fun startWebRtcCall(callId: String, callerId: String) {
        firebaseSignaling = FirebaseSignaling(callId)
        webRtcClient = WebRtcClient(
            this,
            currentUserId,
            callerId,
            object : WebRtcClient.SignalingCallback {
                override fun onLocalDescription(sdp: SessionDescription) {
                    firebaseSignaling?.sendSignal(currentUserId, sdp.type.canonicalForm(), sdp.description)
                }

                override fun onIceCandidate(candidate: IceCandidate) {
                    val candidateStr = "${candidate.sdpMid},${candidate.sdpMLineIndex},${candidate.sdp}"
                    firebaseSignaling?.sendSignal(currentUserId, "candidate", candidateStr)
                }

                override fun onCallEstablished() {
                    runOnUiThread {
                        binding.tvCallStatus.text = "📞 Call Connected!"
                        Toast.makeText(this@CallActivity, "Call connected!", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            firebaseSignaling!!
        )

        // Mulai WebRTC
        webRtcClient?.start()

        // Dengarkan signaling
        firebaseSignaling?.listenForSignals { from, type, data ->
            if (from == callerId) {
                when (type) {
                    "offer" -> {
                        webRtcClient?.setRemoteDescription(data, "offer")
                        webRtcClient?.createAnswer()
                    }
                    "answer" -> {
                        webRtcClient?.setRemoteDescription(data, "answer")
                    }
                    "candidate" -> {
                        val parts = data.split(",")
                        if (parts.size == 3) {
                            val ice = IceCandidate(parts[0], parts[1].toInt(), parts[2])
                            webRtcClient?.addIceCandidate(ice)
                        }
                    }
                }
            }
        }

        // Kirim offer (jika Anda inisiator — tapi di sini receiver, jadi tunggu offer)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

}