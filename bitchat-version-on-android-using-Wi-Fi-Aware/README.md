# Wi-Fi Aware Mesh for bitchat (Android)

Wi-Fi Aware (NAN) transport for bitchat that aligns with the latest `BluetoothMeshService` and iOS protocol. Tested and verified working with minimal integration changes. 
Supports **signed TLV announces**, **Noise handshake / NOISE_ENCRYPTED payloads**, and **Gossip sync**. 

---

**Files (pinned to commits):**
- [`WifiAwareMeshServiceLatest.kt`](https://github.com/permissionlesstech/bitchat-android/commit/6546943cf7bc727b03b6a96c74a07949bb2ddb7a) → *Latest (current protocol implementation)*
- [`WifiAwareMeshServiceStable.kt`](https://github.com/permissionlesstech/bitchat-android/commit/013f0c00cf3ca1b7942fda6f1dac6f3e84b721f2) → *Stable (legacy key exchange, pre-Noise)*

---
