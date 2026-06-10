# SOS Mesh Network App

A decentralized emergency communication application built for Android. This app allows users to broadcast SOS alerts and user profiles using Bluetooth Low Energy (BLE) and Google Nearby Connections, enabling communication in environments without cellular or internet connectivity.

## Features

- **Mesh Networking**: Uses Google Nearby Connections (P2P_STAR strategy) to create a mesh network where messages are forwarded between connected devices.
- **BLE Beaconing**: Broadcasts a highly compressed SOS payload via Bluetooth Low Energy hardware advertising, allowing discovery even by devices not actively in the mesh.
- **Offline Storage**: Stores received SOS messages locally and automatically uploads them to a Firebase Realtime Database once an internet connection is detected.
- **User Profiles**: Allows users to register a profile (Name, Age, Phone) which is automatically bundled with emergency alerts.
- **Emergency Metadata**: Attach critical info to SOS alerts including:
    - People count
    - Medical needs
    - Hazard type (e.g., Flood, Fire)
    - GPS Coordinates (Latitude/Longitude)

## Centralized Monitoring (Cloud Integration)

While the app functions primarily as a decentralized mesh network, it includes a cloud synchronization layer for rescue coordination:

- **Server Sync**: Once any device in the mesh gains internet access, it automatically pushes all collected SOS messages to a central Firebase server.
- **Incident Command System**: Data from the server can be fetched and visualized in real-time on a monitoring dashboard at a **fire station** or emergency response center.
- **Location Tracking**: Rescuers can track the precise location of individuals in distress on a map, prioritized by the severity of their reported medical needs or hazard type.

## How to Use

1. **Permissions**: Grant necessary permissions for Location, Bluetooth, and Nearby Devices on first launch.
2. **Profile Setup**: Enter your name, age, and phone number. This information is stored locally and sent with any SOS you trigger.
3. **Automatic Mesh**: The app automatically cycles between *Advertising* and *Discovery* modes every 12 seconds to find and connect with nearby peers.
4. **Trigger SOS**: Press the large red **SOS** button to broadcast an alert.
    - If peers are connected, the alert is sent immediately.
    - If no peers are found, the alert is stored and will be sent to the first peer discovered.
5. **Relay**: Any device running the app will automatically relay received SOS messages to other peers, extending the reach of the signal.

## Technical Details

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Connectivity**: 
    - Google Nearby Connections API
    - Bluetooth LE (Custom Manufacturer Data Advertising)
- **Backend**: Firebase Realtime Database (for cloud syncing when online)

## Security & Privacy Note

> [!IMPORTANT]
> The `google-services.json` file is excluded from this repository for security reasons. To build this project yourself, you must provide your own Firebase configuration file in the `app/` directory.

---
*Developed for emergency resilience and mesh-based disaster response.*
