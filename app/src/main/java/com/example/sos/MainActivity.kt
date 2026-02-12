package com.example.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.sos.ui.theme.SOSTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    // --- ROLE SWITCHING VARIABLES ---
    private var isAdvertising = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val switchRoleRunnable = object : Runnable {
        override fun run() {
            if (connectedEndpoints.isNotEmpty()) return // Stop switching if connected!

            stopAllEndpoints() // Clear previous state

            if (isAdvertising) {
                // Switch to LISTENING Mode
                addLog("🔄 Switching to DISCOVERY Mode...")
                startDiscovery()
            } else {
                // Switch to HOSTING Mode
                addLog("🔄 Switching to ADVERTISING Mode...")
                startAdvertising()
            }

            isAdvertising = !isAdvertising // Flip the flag
            handler.postDelayed(this, 12000) // Switch every 12 seconds
        }
    }
    private fun stopAllEndpoints() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }
    private val SERVICE_ID = "sos_mesh_v2"
    private val SERVER_URL = "http://10.170.92.228:3000/sos" // Ensure this matches your Laptop IP!

    private val connectionsClient by lazy { Nearby.getConnectionsClient(this) }
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient

    private val connectedEndpoints = mutableSetOf<String>()
    private val receivedMessages = mutableSetOf<String>()
    private val storedSOSMessages = mutableListOf<String>()

    // --- UI STATE FOR LOGS ---
    private val logMessages = mutableStateListOf<String>()

    // Define permissions
    // Define permissions based on Android version
    // Define permissions based on Android version
    private val requiredPermissions = when {
        // Android 13+ (API 33)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, // <--- MUST BE HERE WITH FINE LOCATION
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        }
        // Android 12 (API 31/32)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        // Android 11 or lower
        else -> {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            addLog("Permissions Granted. Starting Mesh...")
            startMeshNetwork()
        } else {
            addLog("❌ Permissions Missing!")
            Toast.makeText(this, "Permissions needed", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        addLog("App Started. Initializing...")
        checkAndRequestPermissions()

        // Background server sync
        android.os.Handler(mainLooper).postDelayed(object : Runnable {
            override fun run() {
                if (isInternetAvailable() && storedSOSMessages.isNotEmpty()) {
                    addLog("Internet found. Attempting upload...")
                    uploadSOSMessages()
                }
                android.os.Handler(mainLooper).postDelayed(this, 10000)
            }
        }, 10000)

        setContent {
            SOSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // We replaced the manual Column with this cleaner function call
                    EmergencyScreen(
                        logMessages = logMessages,
                        onSendSOS = { p, m, h -> sendSOS(p, m, h) }
                    )
                }
            }
        }
    }

    // --- HELPER TO UPDATE UI LOGS ---
    private fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            logMessages.add(0, "[$time] $msg") // Add to top
        }
        Log.d("SOS_APP", msg)
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startMeshNetwork()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startMeshNetwork() {
        addLog("🚀 Starting Auto-Mesh Search...")
        // Start the first loop immediately
        handler.post(switchRoleRunnable)
    }

    // ------------------ ADVERTISING ------------------
    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .setLowPower(false) // High Power
            .build()

        connectionsClient.startAdvertising(
            "SOS_User",
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            addLog("📡 Advertising Started (Offline Mode)")
        }.addOnFailureListener {
            addLog("❌ Advertising Failed: ${it.message}")
        }
    }

    // ------------------ DISCOVERY ------------------
    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .setLowPower(false) // High Power
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            addLog("🔍 Discovery Started (Offline Mode)")
        }.addOnFailureListener {
            addLog("❌ Discovery Failed: ${it.message}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            addLog("👀 Found Device: ${info.endpointName}")
            connectionsClient.requestConnection("SOS_User", endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {
            addLog("device lost: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            addLog("🤝 Connection Initiated: $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                addLog("✅ Connected to $endpointId")

                // 🛑 1. Stop the switching loop (Standard Logic)
                handler.removeCallbacks(switchRoleRunnable)
                addLog("🛑 Role Switching Stopped")

                // 🚀 2. THE NEW FEATURE: "Store and Forward" Sync
                // Check if we have any saved messages waiting
                if (storedSOSMessages.isNotEmpty()) {
                    addLog("🔄 Syncing ${storedSOSMessages.size} offline SOS messages...")

                    for (savedMsg in storedSOSMessages) {
                        val payload = Payload.fromBytes(savedMsg.toByteArray())
                        connectionsClient.sendPayload(endpointId, payload)
                        addLog("➡️ Delayed Send: Sending saved SOS to $endpointId")
                    }
                }

            } else {
                addLog("❌ Connection Failed: ${result.status.statusCode}")
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            addLog("⚠️ Disconnected from $endpointId")

            // RESTART THE SEARCH LOOP
            if (connectedEndpoints.isEmpty()) {
                addLog("🔄 Restarting Mesh Search...")
                handler.post(switchRoleRunnable)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val message = String(payload.asBytes()!!)
            addLog("📩 RECEIVED: $message")
            Toast.makeText(this@MainActivity, "SOS Received!", Toast.LENGTH_SHORT).show()

            // 🛑 SAFETY CHECK: Only process if we haven't seen this message before
            if (!receivedMessages.contains(message)) {

                // 1. MARK AS SEEN (Stop loops)
                receivedMessages.add(message)

                // 2. SAVE LOCALLY (Crucial for acting as a carrier/mule)
                // This puts the message in the "backpack" to give to Phone C later
                storedSOSMessages.add(message)

                // 3. RELAY IMMEDIATELY (To anyone currently connected)
                // sending to everyone EXCEPT the person who sent it (endpointId)
                forwardMessage(message, endpointId)

                // 4. UPLOAD IMMEDIATELY (If I have internet right now)
                if (isInternetAvailable()) {
                    addLog("🌐 Internet found. Uploading now...")
                    uploadSOSMessages()
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun uploadSOSMessages() {
        Thread {
            val iterator = storedSOSMessages.iterator()
            while (iterator.hasNext()) {
                val message = iterator.next()
                try {
                    val url = URL(SERVER_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    val json = "{\"message\":\"$message\"}"
                    connection.outputStream.write(json.toByteArray())

                    if (connection.responseCode == 200) {
                        val successMsg = "☁️ Uploaded: $message"
                        addLog(successMsg)
                        iterator.remove()
                    }
                } catch (e: Exception) {
                    addLog("⚠️ Upload Failed: ${e.message}")
                }
            }
        }.start()
    }

    private fun forwardMessage(message: String, senderEndpoint: String) {
        val payload = Payload.fromBytes(message.toByteArray())
        for (endpoint in connectedEndpoints) {
            if (endpoint != senderEndpoint) {
                connectionsClient.sendPayload(endpoint, payload)
                addLog("➡️ Relaying Message to $endpoint")
            }
        }
    }

    // ------------------ UI COMPONENTS ------------------


    @Composable
    fun StatusLogBox(messages: List<String>) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                // 1. Dark Background (So white text is visible)
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text(
                "System Logs:",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White, // 2. Header Text White
                modifier = Modifier.padding(bottom = 4.dp)
            )
            LazyColumn {
                items(messages) { msg ->
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        // 3. Log Text White (Errors appear in bright Red/Orange)
                        color = if (msg.contains("❌") || msg.contains("⚠️")) Color(0xFFFF5555) else Color.White
                    )
                    // 4. Divider is now Dark Gray to blend in
                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                }
            }
        }
    }

    // Function now accepts details about the emergency
    // Updated function with Optional Parameters
    private fun sendSOS(
        rawPeople: String,
        rawMedical: String,
        rawHazard: String
    ) {
        // 1. HANDLE OPTIONAL LOGIC (Defaults)
        // If string is empty, use default. Otherwise, use what they typed.
        val peopleCount = if (rawPeople.isBlank()) "1" else rawPeople
        val medicalCondition = if (rawMedical.isBlank()) "None" else rawMedical
        val hazardType = if (rawHazard.isBlank()) "Unknown" else rawHazard

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            addLog("❌ Location Permission Missing")
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            val lat = location?.latitude ?: 0.0
            val lng = location?.longitude ?: 0.0
            val time = System.currentTimeMillis()

            // 2. PACKET CREATION
            val sosMessage = "SOS|Lat:$lat|Lng:$lng|Time:$time|Ppl:$peopleCount|Med:$medicalCondition|Haz:$hazardType"

            addLog("🚨 PACKING: $sosMessage")

            if (!receivedMessages.contains(sosMessage)) {
                receivedMessages.add(sosMessage)
                storedSOSMessages.add(sosMessage)
            }

            val payload = Payload.fromBytes(sosMessage.toByteArray())
            if (connectedEndpoints.isEmpty()) {
                addLog("⚠️ No peers. Stored locally.")
            } else {
                for (endpoint in connectedEndpoints) {
                    connectionsClient.sendPayload(endpoint, payload)
                    addLog("➡️ Sent extended SOS to $endpoint")
                }
            }
        }
    }
    @Composable
    fun EmergencyScreen(
        logMessages: List<String>,
        onSendSOS: (String, String, String) -> Unit
    ) {
        // These variables hold what the user types
        var peopleInput by remember { mutableStateOf("") }
        var medicalInput by remember { mutableStateOf("") }
        var hazardInput by remember { mutableStateOf("") }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // --- TOP SECTION: INPUTS ---
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(), // Takes up available space
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center // Centers inputs vertically
            ) {
                Text(
                    "Emergency Details (Optional)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(15.dp))

                // 1. People Count
                OutlinedTextField(
                    value = peopleInput,
                    onValueChange = { peopleInput = it },
                    label = { Text("People Count (Default: 1)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 2. Medical Condition
                OutlinedTextField(
                    value = medicalInput,
                    onValueChange = { medicalInput = it },
                    label = { Text("Medical Needs (Default: None)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 3. Hazard Type
                OutlinedTextField(
                    value = hazardInput,
                    onValueChange = { hazardInput = it },
                    label = { Text("Hazard Type (Default: Unknown)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))

                // --- SOS BUTTON ---
                Button(
                    onClick = {
                        // Send the inputs to the main logic
                        onSendSOS(peopleInput, medicalInput, hazardInput)

                        // Optional: Clear inputs after sending
                        peopleInput = ""
                        medicalInput = ""
                        hazardInput = ""
                    },
                    modifier = Modifier.size(150.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    elevation = ButtonDefaults.buttonElevation(10.dp)
                ) {
                    Text("SOS", fontSize = 24.sp, color = Color.White)
                }
            }

            // --- BOTTOM SECTION: LOGS ---
            // (Re-using your existing StatusLogBox)
            StatusLogBox(messages = logMessages)
        }
    }
}