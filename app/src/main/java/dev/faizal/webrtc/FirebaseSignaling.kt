package dev.faizal.webrtc

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.getValue

class FirebaseSignaling(private val callId: String) {
    private val signalingRef = FirebaseDatabase.getInstance().reference
        .child("panic_calls").child(callId).child("signaling")

    fun sendSignal(from: String, type: String, data : String) {
        val signal = mapOf(
            "from" to from,
            "type" to type, // "offer", "answer", "candidate"
            "data" to data,
            "timestamp" to System.currentTimeMillis()
        )
        signalingRef.push().setValue(signal)
    }

    fun listenForSignals(listener: (from: String, type: String,  String) -> Unit) {
        signalingRef.addChildEventListener(object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val signal = snapshot.getValue<Map<String, Any>>()
                signal?.let {
                    val from = it["from"] as String
                    val type = it["type"] as String
                    val data = it["data"] as String
                    listener(from, type, data)
                }
            }
            override fun onChildChanged(p0: DataSnapshot, p1: String?) {}
            override fun onChildRemoved(p0: DataSnapshot) {}
            override fun onChildMoved(p0: DataSnapshot, p1: String?) {}
            override fun onCancelled(p0: com.google.firebase.database.DatabaseError) {}
        })
    }
}