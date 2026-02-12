package com.example.sos

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.sos.ui.theme.SOSTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    // --- VARIABLES ---
    private var isAdvertising = false
    private lateinit var database: com.google.firebase.database.DatabaseReference
    private lateinit var prefs: SharedPreferences // To store user data locally

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val switchRoleRunnable = object : Runnable {
        override fun run() {
            if (connectedEndpoints.isNotEmpty()) return
            stopAllEndpoints()
            if (isAdvertising) {
                addLog("🔄 Switching to DISCOVERY Mode...")
                startDiscovery()
            } else {
                addLog("🔄 Switching to ADVERTISING Mode...")
                startAdvertising()
            }
            isAdvertising = !isAdvertising
            handler.postDelayed(this, 12000)
        }
    }

    private fun stopAllEndpoints() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    private val SERVICE_ID = "sos_mesh_v2"
    private val connectionsClient by lazy { Nearby.getConnectionsClient(this) }
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient

    private val connectedEndpoints = mutableSetOf<String>()
    private val receivedMessages = mutableSetOf<String>()
    private val storedSOSMessages = mutableListOf<String>()
    private val logMessages = mutableStateListOf<String>()

    private val requiredPermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
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

        // 1. Initialize Storage & Firebase
        prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        database = com.google.firebase.database.FirebaseDatabase.getInstance().reference
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        addLog("App Started. Initializing...")
        checkAndRequestPermissions()

        // Background Upload Logic
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // 2. CHECK: Is User Registered?
                    var isRegistered by remember { mutableStateOf(prefs.getBoolean("is_registered", false)) }

                    if (isRegistered) {
                        // Show Main SOS Screen
                        EmergencyScreen(
                            logMessages = logMessages,
                            onSendSOS = { p, m, h -> sendSOS(p, m, h) }
                        )
                    } else {
                        // Show Sign Up Screen First
                        SignUpScreen(
                            onRegister = { name, age, phone ->
                                // Save Data Permanently
                                with(prefs.edit()) {
                                    putString("user_name", name)
                                    putString("user_age", age)
                                    putString("user_phone", phone)
                                    putBoolean("is_registered", true)
                                    apply()
                                }
                                isRegistered = true // Switch Screen
                                addLog("✅ User Registered: $name")
                            }
                        )
                    }
                }
            }
        }
    }

    // --- HELPER FUNCTIONS ---
    private fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread { logMessages.add(0, "[$time] $msg") }
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
        handler.post(switchRoleRunnable)
    }

    // --- NEARBY CONNECTIONS LOGIC ---
    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).setLowPower(false).build()
        connectionsClient.startAdvertising("SOS_User", SERVICE_ID, connectionLifecycleCallback, options)
            .addOnFailureListener { addLog("❌ Advertising Failed: ${it.message}") }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).setLowPower(false).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnFailureListener { addLog("❌ Discovery Failed: ${it.message}") }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            addLog("👀 Found Device: ${info.endpointName}")
            connectionsClient.requestConnection("SOS_User", endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                addLog("✅ Connected to $endpointId")
                handler.removeCallbacks(switchRoleRunnable)

                if (storedSOSMessages.isNotEmpty()) {
                    for (savedMsg in storedSOSMessages) {
                        connectionsClient.sendPayload(endpointId, Payload.fromBytes(savedMsg.toByteArray()))
                    }
                }
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            if (connectedEndpoints.isEmpty()) handler.post(switchRoleRunnable)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val message = String(payload.asBytes()!!)
            addLog("📩 RECEIVED: $message")
            Toast.makeText(this@MainActivity, "SOS Received!", Toast.LENGTH_SHORT).show()

            if (!receivedMessages.contains(message)) {
                receivedMessages.add(message)
                storedSOSMessages.add(message)
                forwardMessage(message, endpointId)
                if (isInternetAvailable()) uploadSOSMessages()
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
        val iterator = storedSOSMessages.iterator()
        while (iterator.hasNext()) {
            val message = iterator.next()
            val key = database.child("sos_alerts").push().key
            if (key != null) {
                val sosData = mapOf("message" to message, "timestamp" to System.currentTimeMillis())
                database.child("sos_alerts").child(key).setValue(sosData)
                    .addOnSuccessListener { addLog("☁️ Firebase Upload Success") }
            }
            iterator.remove()
        }
    }

    private fun forwardMessage(message: String, senderEndpoint: String) {
        val payload = Payload.fromBytes(message.toByteArray())
        for (endpoint in connectedEndpoints) {
            if (endpoint != senderEndpoint) {
                connectionsClient.sendPayload(endpoint, payload)
            }
        }
    }

    // --- MAIN LOGIC: SENDING SOS WITH USER DATA ---
    private fun sendSOS(rawPeople: String, rawMedical: String, rawHazard: String) {
        val peopleCount = if (rawPeople.isBlank()) "1" else rawPeople
        val medicalCondition = if (rawMedical.isBlank()) "None" else rawMedical
        val hazardType = if (rawHazard.isBlank()) "Unknown" else rawHazard

        // 1. RETRIEVE SAVED USER DATA
        val name = prefs.getString("user_name", "Unknown") ?: "Unknown"
        val age = prefs.getString("user_age", "?") ?: "?"
        val phone = prefs.getString("user_phone", "No Phone") ?: "No Phone"

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            addLog("❌ Location Permission Missing")
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            val lat = location?.latitude ?: 0.0
            val lng = location?.longitude ?: 0.0
            val time = System.currentTimeMillis()
            val msgId = java.util.UUID.randomUUID().toString().substring(0, 8)

            // 2. BUILD PACKET WITH USER INFO
            // Format: ID | SOS | Lat | Lng | Time | Name | Age | Ph | Ppl | Med | Haz
            val sosMessage = "$msgId|SOS|Lat:$lat|Lng:$lng|Time:$time|Name:$name|Age:$age|Ph:$phone|Ppl:$peopleCount|Med:$medicalCondition|Haz:$hazardType"

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
                }
            }
        }.addOnFailureListener { addLog("❌ Failed to get location") }
    }

    // --- UI COMPONENTS ---

    @Composable
    fun SignUpScreen(onRegister: (String, String, String) -> Unit) {
        var name by remember { mutableStateOf("") }
        var age by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Create SOS Profile", style = MaterialTheme.typography.headlineMedium, color = Color.Black)
            Text("This info will be sent to rescuers.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

            Spacer(modifier = Modifier.height(30.dp))

            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Age") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(30.dp))

            Button(
                onClick = { if (name.isNotBlank() && phone.isNotBlank()) onRegister(name, age, phone) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
            ) {
                Text("Save Profile", fontSize = 18.sp)
            }
        }
    }

    @Composable
    fun StatusLogBox(messages: List<String>) {
        Column(
            modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp)).padding(8.dp)
        ) {
            Text("Logs:", color = Color.White)
            LazyColumn {
                items(messages) { msg ->
                    Text(text = msg, fontSize = 12.sp, color = if (msg.contains("❌")) Color.Red else Color.White)
                    HorizontalDivider(color = Color.DarkGray)
                }
            }
        }
    }

    @Composable
    fun EmergencyScreen(logMessages: List<String>, onSendSOS: (String, String, String) -> Unit) {
        var peopleInput by remember { mutableStateOf("") }
        var medicalInput by remember { mutableStateOf("") }
        var hazardInput by remember { mutableStateOf("") }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("EMERGENCY MODE", style = MaterialTheme.typography.headlineLarge, color = Color.Red)
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(value = peopleInput, onValueChange = { peopleInput = it }, label = { Text("People Count") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = medicalInput, onValueChange = { medicalInput = it }, label = { Text("Medical Needs") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = hazardInput, onValueChange = { hazardInput = it }, label = { Text("Hazard Type") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = {
                        onSendSOS(peopleInput, medicalInput, hazardInput)
                        peopleInput = ""; medicalInput = ""; hazardInput = ""
                    },
                    modifier = Modifier.size(150.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("SOS", fontSize = 24.sp, color = Color.White) }
            }
            StatusLogBox(messages = logMessages)
        }
    }
}