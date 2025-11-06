package dev.faizal.webrtc

import android.content.Context
import android.util.Log
import com.google.firebase.database.*
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class PanicCallManager(
    private val context: Context,
    private val userId: String,
    private val onCallConnected: () -> Unit,
    private val onCallEnded: () -> Unit
) {

    private val database = FirebaseDatabase.getInstance().reference
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private lateinit var localAudioTrack: AudioTrack
    private var remoteAudioTrack: AudioTrack? = null
    private var isInitiator = false
    private var currentCallId: String? = null
    private val TAG = "PanicCallManager"

    fun initialize() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSampleRate(44100)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    Log.e(TAG, "Audio Record Init Error: $errorMessage")
                }
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    errorMessage: String?
                ) {
                    Log.e(TAG, "Audio Record Start Error: $errorMessage")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    Log.e(TAG, "Audio Record Error: $errorMessage")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    Log.e(TAG, "Audio Track Init Error: $errorMessage")
                }
                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                    errorMessage: String?
                ) {
                    Log.e(TAG, "Audio Track Start Error: $errorMessage")
                }
                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    Log.e(TAG, "Audio Track Error: $errorMessage")
                }
            })
            .createAudioDeviceModule()

        audioDeviceModule.setMicrophoneMute(false)
        audioDeviceModule.setSpeakerMute(false)

        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio", audioSource)
        localAudioTrack.setEnabled(true)

        Log.d(TAG, "WebRTC initialized successfully")
    }

    fun startPanicCall(recipients: List<String>) {
        isInitiator = true
        val callId = database.child("panic_calls").push().key ?: return
        currentCallId = callId

        val callData = mapOf(
            "callId" to callId,
            "from" to userId,
            "to" to recipients,
            "status" to "ringing",
            "answeredBy" to "",
            "timestamp" to System.currentTimeMillis()
        )

        database.child("panic_calls").child(callId).setValue(callData)
            .addOnSuccessListener {
                setupPeerConnection()
                listenForAnswer(callId)
                Log.d(TAG, "Panic call started: $callId")
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to send panic call", it)
            }
    }

    private fun setupPeerConnection() {
        val iceServers = listOf(
            // STUN
            PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer(),

            // TURN UDP
            PeerConnection.IceServer.builder("turn:standard.relay.metered.ca:80")
                .setUsername("77de4d97de5205cca5676bc7")
                .setPassword("1m2Z/3G0MuqwLpNT")
                .createIceServer(),

            // TURN TCP
            PeerConnection.IceServer.builder("turn:standard.relay.metered.ca:80?transport=tcp")
                .setUsername("77de4d97de5205cca5676bc7")
                .setPassword("1m2Z/3G0MuqwLpNT")
                .createIceServer(),

            // TURN UDP 443
            PeerConnection.IceServer.builder("turn:standard.relay.metered.ca:443")
                .setUsername("77de4d97de5205cca5676bc7")
                .setPassword("1m2Z/3G0MuqwLpNT")
                .createIceServer(),

            // TURN TLS (turns)
            PeerConnection.IceServer.builder("turns:standard.relay.metered.ca:443?transport=tcp")
                .setUsername("77de4d97de5205cca5676bc7")
                .setPassword("1m2Z/3G0MuqwLpNT")
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling state: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE Connection state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            Log.d(TAG, "✅ Call connected!")
                            onCallConnected()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            Log.w(TAG, "⚠️ Call disconnected")
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.e(TAG, "❌ Call failed")
                        }
                        else -> {}
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE receiving: $receiving")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE Gathering state: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "New ICE candidate: ${it.sdpMid}")
                        sendIceCandidate(it)
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

                override fun onAddStream(stream: MediaStream?) {
                    Log.d(TAG, "🎵 Remote stream added!")
                    stream?.audioTracks?.forEach { audioTrack ->
                        Log.d(TAG, "Remote audio track: ${audioTrack.id()}, enabled: ${audioTrack.enabled()}")
                        remoteAudioTrack = audioTrack
                        audioTrack.setEnabled(true)
                        audioTrack.setVolume(10.0) // Max volume
                    }
                }

                override fun onRemoveStream(stream: MediaStream?) {
                    Log.d(TAG, "Remote stream removed")
                }

                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    Log.d(TAG, "🎵 Track added: ${receiver?.track()?.kind()}")
                    if (receiver?.track()?.kind() == "audio") {
                        val audioTrack = receiver.track() as AudioTrack
                        remoteAudioTrack = audioTrack
                        audioTrack.setEnabled(true)
                        audioTrack.setVolume(10.0)
                    }
                }
            }
        )

        // Add local audio track
        val streamId = "stream_$userId"
        peerConnection?.addTrack(localAudioTrack, listOf(streamId))
        Log.d(TAG, "Local audio track added to peer connection")
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        currentCallId?.let { callId ->
            val candidateMap = mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "sdp" to candidate.sdp
            )
            database.child("panic_calls/$callId/signaling/candidates").push().setValue(candidateMap)
            Log.d(TAG, "ICE candidate sent")
        }
    }

    private fun createAndSendOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Offer created successfully")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set")
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Set local description failed: $error")
                    }
                }, sdp)

                currentCallId?.let { callId ->
                    val offerMap = mapOf(
                        "type" to sdp.type.canonicalForm(),
                        "sdp" to sdp.description
                    )
                    database.child("panic_calls/$callId/signaling/offer").setValue(offerMap)
                    Log.d(TAG, "Offer sent to Firebase")
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create offer failed: $error")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun createAndSendAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Answer created successfully")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local answer set")
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Set local answer failed: $error")
                    }
                }, sdp)

                currentCallId?.let { callId ->
                    val answerMap = mapOf(
                        "type" to sdp.type.canonicalForm(),
                        "sdp" to sdp.description
                    )
                    database.child("panic_calls/$callId/signaling/answer").setValue(answerMap)
                    Log.d(TAG, "Answer sent to Firebase")
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create answer failed: $error")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun answerIncomingCall(callId: String) {
        isInitiator = false
        currentCallId = callId

        database.child("panic_calls/$callId").updateChildren(
            mapOf("status" to "answered", "answeredBy" to userId)
        )

        setupPeerConnection()
        listenForSignaling(callId)
        Log.d(TAG, "Answering incoming call: $callId")
    }

    private fun listenForAnswer(callId: String) {
        database.child("panic_calls/$callId/signaling/answer")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val data = snapshot.getValue<Map<String, String>>()
                    if (data != null && data.containsKey("type") && data.containsKey("sdp")) {
                        Log.d(TAG, "Answer received")
                        val sdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(data["type"]!!),
                            data["sdp"]!!
                        )
                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.d(TAG, "✅ Remote answer set successfully")
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "Set remote answer failed: $error")
                            }
                        }, sdp)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })

        // Listen for ICE candidates
        listenForIceCandidates(callId)
    }

    private fun listenForSignaling(callId: String) {
        database.child("panic_calls/$callId/signaling/offer")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val data = snapshot.getValue<Map<String, String>>()
                    if (data != null && data.containsKey("type") && data.containsKey("sdp")) {
                        Log.d(TAG, "Offer received")
                        val sdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(data["type"]!!),
                            data["sdp"]!!
                        )
                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.d(TAG, "✅ Remote offer set successfully, creating answer...")
                                createAndSendAnswer()
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "Set remote offer failed: $error")
                            }
                        }, sdp)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })

        listenForIceCandidates(callId)
    }

    private fun listenForIceCandidates(callId: String) {
        database.child("panic_calls/$callId/signaling/candidates")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                    val data = snapshot.getValue<Map<String, Any>>()
                    if (data != null) {
                        val sdpMid = data["sdpMid"] as? String ?: ""
                        val sdpMLineIndex = (data["sdpMLineIndex"] as? Long ?: 0).toInt()
                        val sdp = data["sdp"] as? String ?: ""
                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        peerConnection?.addIceCandidate(candidate)
                        Log.d(TAG, "ICE candidate added: $sdpMid")
                    }
                }
                override fun onChildChanged(s: DataSnapshot, p: String?) {}
                override fun onChildRemoved(s: DataSnapshot) {}
                override fun onChildMoved(s: DataSnapshot, p: String?) {}
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    fun onCallShouldStartAsCaller() {
        Log.d(TAG, "Starting call as caller, creating offer...")
        createAndSendOffer()
    }

    fun endCall() {
        Log.d(TAG, "Ending call")
        peerConnection?.close()
        peerConnection = null
        remoteAudioTrack = null
        currentCallId?.let { callId ->
            database.child("panic_calls/$callId").updateChildren(mapOf("status" to "ended"))
        }
        onCallEnded()
    }
}