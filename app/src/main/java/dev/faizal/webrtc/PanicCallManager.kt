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

    // Ganti bagian initialize() di PanicCallManager.kt dengan kode ini:

    fun initialize() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // ✅ AUDIO DEVICE MODULE - SETTING OPTIMAL ANTI-NOISE
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            // HARDWARE NOISE SUPPRESSION (Gunakan chip HP)
            .setUseHardwareAcousticEchoCanceler(true) // ✅ Echo cancellation hardware
            .setUseHardwareNoiseSuppressor(true)      // ✅ Noise suppressor hardware

            // AUDIO QUALITY SETTINGS
            .setUseStereoInput(false)   // Mono untuk voice call (lebih jernih)
            .setUseStereoOutput(false)  // Mono output
            .setSampleRate(48000)       // 48kHz = kualitas HD voice

            // ✅ AUDIO RECORD SETTINGS (Mikrofon)
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    Log.d(TAG, "🎤 Audio recording started")
                }
                override fun onWebRtcAudioRecordStop() {
                    Log.d(TAG, "🎤 Audio recording stopped")
                }
            })

            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    Log.e(TAG, "❌ Audio Record Init Error: $errorMessage")
                }
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    errorMessage: String?
                ) {
                    Log.e(TAG, "❌ Audio Record Start Error: $errorMessage")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    Log.e(TAG, "❌ Audio Record Error: $errorMessage")
                }
            })

            // ✅ AUDIO TRACK SETTINGS (Speaker)
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() {
                    Log.d(TAG, "🔊 Audio playback started")
                }
                override fun onWebRtcAudioTrackStop() {
                    Log.d(TAG, "🔊 Audio playback stopped")
                }
            })

            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    Log.e(TAG, "❌ Audio Track Init Error: $errorMessage")
                }
                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                    errorMessage: String?
                ) {
                    Log.e(TAG, "❌ Audio Track Start Error: $errorMessage")
                }
                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    Log.e(TAG, "❌ Audio Track Error: $errorMessage")
                }
            })

            .createAudioDeviceModule()

        // ✅ MUTE CONTROL - Pastikan tidak mute
        audioDeviceModule.setMicrophoneMute(false)
        audioDeviceModule.setSpeakerMute(false)

        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        // ✅ AUDIO CONSTRAINTS - INI YANG PALING PENTING UNTUK NOISE REDUCTION
        val audioConstraints = MediaConstraints().apply {
            // === ECHO CANCELLATION (Menghilangkan echo/gema) ===
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googDAEchoCancellation", "true"))

            // === AUTO GAIN CONTROL (Stabilkan volume, jangan terlalu keras/pelan) ===
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))

            // === NOISE SUPPRESSION (Hilangkan noise background) ===
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))

            // ✅ TAMBAHAN PENTING ANTI-NOISE
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))          // Filter suara bass/rendah
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))    // Deteksi suara ketikan
            mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))         // Jangan mirror audio

            // === EXPERIMENTAL FEATURES (Lebih agresif hilangkan noise) ===
            mandatory.add(MediaConstraints.KeyValuePair("googExperimentalEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googExperimentalAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googExperimentalNoiseSuppression", "true"))

            // ✅ AUDIO BANDWIDTH (Kualitas codec)
            // Opus codec settings untuk voice clarity
            mandatory.add(MediaConstraints.KeyValuePair("maxaveragebitrate", "40000"))  // 40kbps = kualitas tinggi
            mandatory.add(MediaConstraints.KeyValuePair("stereo", "false"))             // Mono = lebih jernih
            mandatory.add(MediaConstraints.KeyValuePair("useinbandfec", "true"))        // Forward Error Correction
            mandatory.add(MediaConstraints.KeyValuePair("usedtx", "false"))             // Jangan pause saat diam (DTX off)
        }

        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio", audioSource)
        localAudioTrack.setEnabled(true)

        Log.d(TAG, "✅ WebRTC initialized with OPTIMIZED NOISE REDUCTION")
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
            // STUN servers - lebih dari 1 untuk backup
            PeerConnection.IceServer.builder("stun:xhvmubnt1.kodein.biz.id:3478")
                .createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer(),

            // TURN UDP
            PeerConnection.IceServer.builder("turn:xhvmubnt1.kodein.biz.id:3478")
                .setUsername("kodein")
                .setPassword("@Readykodein")
                .createIceServer(),

            // TURN TCP - PENTING untuk firewall restrictive
            PeerConnection.IceServer.builder("turn:xhvmubnt1.kodein.biz.id:3478?transport=tcp")
                .setUsername("kodein")
                .setPassword("@Readykodein")
                .createIceServer(),

            // TURN UDP port 443 - untuk bypass firewall
            PeerConnection.IceServer.builder("turn:xhvmubnt1.kodein.biz.id:443")
                .setUsername("kodein")
                .setPassword("@Readykodein")
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL

            // Audio codec preferences - Opus adalah yang terbaik untuk voice
            // audioJitterBufferMaxPackets = 200 // Buffer lebih besar untuk koneksi tidak stabil
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
                        Log.d(TAG, "New ICE candidate: ${it.sdpMid} (${it.sdp})")
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
                        Log.d(TAG, "Remote audio track volume set to maximum")
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
            mandatory.add(MediaConstraints.KeyValuePair("VoiceActivityDetection", "true")) // VAD untuk hemat bandwidth
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Offer created successfully")

                // Modifikasi SDP untuk optimize audio quality
                val modifiedSdp = sdp.description
                    .replace("useinbandfec=1", "useinbandfec=1;stereo=0;maxaveragebitrate=40000") // Opus optimization

                val optimizedSdp = SessionDescription(sdp.type, modifiedSdp)

                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set")
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Set local description failed: $error")
                    }
                }, optimizedSdp)

                currentCallId?.let { callId ->
                    val offerMap = mapOf(
                        "type" to optimizedSdp.type.canonicalForm(),
                        "sdp" to optimizedSdp.description
                    )
                    database.child("panic_calls/$callId/signaling/offer").setValue(offerMap)
                    Log.d(TAG, "Optimized offer sent to Firebase")
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
            mandatory.add(MediaConstraints.KeyValuePair("VoiceActivityDetection", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Answer created successfully")

                // Modifikasi SDP untuk optimize audio quality
                val modifiedSdp = sdp.description
                    .replace("useinbandfec=1", "useinbandfec=1;stereo=0;maxaveragebitrate=40000")

                val optimizedSdp = SessionDescription(sdp.type, modifiedSdp)

                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local answer set")
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Set local answer failed: $error")
                    }
                }, optimizedSdp)

                currentCallId?.let { callId ->
                    val answerMap = mapOf(
                        "type" to optimizedSdp.type.canonicalForm(),
                        "sdp" to optimizedSdp.description
                    )
                    database.child("panic_calls/$callId/signaling/answer").setValue(answerMap)
                    Log.d(TAG, "Optimized answer sent to Firebase")
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