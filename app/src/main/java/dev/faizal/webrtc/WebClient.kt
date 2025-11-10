package dev.faizal.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcClient(
    private val context: Context,
    private val signalingCallback: SignalingCallback,
) {

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localPeer: PeerConnection? = null

    val iceServers = listOf(
        // STUN
        PeerConnection.IceServer.builder("stun:turn.fzcoturn.com:3478")
            .createIceServer(),

        // TURN UDP
        PeerConnection.IceServer.builder("turn:turn.fzcoturn.com:3478")
            .setUsername("fzubnt")
            .setPassword("@Readyfz")
            .createIceServer(),

        // TURN TCP
        PeerConnection.IceServer.builder("turn:turn.fzcoturn.com:3478?transport=tcp")
            .setUsername("fzubnt")
            .setPassword("@Readyfz")
            .createIceServer(),

        // TURN UDP 443 (untuk bypass firewall)
        PeerConnection.IceServer.builder("turn:turn.fzcoturn.com:443")
            .setUsername("fzubnt")
            .setPassword("@Readyfz")
            .createIceServer(),

        // TURNS (TLS) - hanya jika sudah setup SSL
        PeerConnection.IceServer.builder("turns:turn.fzcoturn.com:5349?transport=tcp")
            .setUsername("fzubnt")
            .setPassword("@Readyfz")
            .createIceServer()
    )



    interface SignalingCallback {
        fun onLocalDescription(sdp: SessionDescription)
        fun onIceCandidate(candidate: IceCandidate)
        fun onCallEstablished()
    }

    fun start() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(null, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(null))
            .createPeerConnectionFactory()

        createPeerConnection()
    }

    private fun createPeerConnection() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")) // audio only
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                if (p0 == PeerConnection.IceConnectionState.CONNECTED) {
                    signalingCallback.onCallEstablished()
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(p0: IceCandidate?) {
                p0?.let { signalingCallback.onIceCandidate(it) }
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate?>?) {
                TODO("Not yet implemented")
            }

            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }

        localPeer = peerConnectionFactory?.createPeerConnection(iceServers, constraints, observer)

        // Tambahkan track audio
        val audioTrack = createAudioTrack()
        localPeer?.addTrack(audioTrack, listOf("stream1"))
    }

    private fun createAudioTrack(): AudioTrack {
        val audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        return peerConnectionFactory?.createAudioTrack("audio_track", audioSource!!)!!
    }

    fun setRemoteDescription(sdp: String, type: String) {
        val sessionDescription = SessionDescription(
            when (type) {
                "offer" -> SessionDescription.Type.OFFER
                "answer" -> SessionDescription.Type.ANSWER
                else -> SessionDescription.Type.OFFER
            },
            sdp
        )
        localPeer?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { Log.d("WebRTC", "Remote SDP set") }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) { Log.e("WebRTC", "Set SDP failed: $p0") }
        }, sessionDescription)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        localPeer?.addIceCandidate(candidate)
    }

    fun createAnswer() {
        localPeer?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    localPeer?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() { Log.d("WebRTC", "Local Answer set") }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, it)
                    signalingCallback.onLocalDescription(it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { Log.e("WebRTC", "Create answer failed: $p0") }
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }
}