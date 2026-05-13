package com.example.android_screen_relay

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object NetworkDiscovery {
    private const val DISCOVERY_PORT = 8888
    private const val PREFIX_DISCOVER = "DISCOVER:"
    private const val PREFIX_OFFER = "OFFER:"

    /**
     * Start listening for discovery requests.
     * @param passkey The 6-digit passkey this host is identified by.
     * @param onStop Callback to check if we should stop listening.
     */
    suspend fun startHostListeners(context: Context, passkey: String, port: Int, isRunning: () -> Boolean) {
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                // Keep trying to bind until successful or stopped
                while (isRunning() && socket == null) {
                    try {
                        socket = DatagramSocket(DISCOVERY_PORT).apply {
                            broadcast = true
                            soTimeout = 2000 // 2 second read timeout to allow checking isRunning
                        }
                    } catch (e: Exception) {
                         // Port might be busy, wait and retry or fail safely
                         // If EADDRINUSE, it implies another service instance or app is running.
                         // For simplicity, we just return if we can't bind.
                         e.printStackTrace()
                         return@withContext
                    }
                }

                val buffer = ByteArray(1024)
                
                while (isRunning()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)
                        
                        val message = String(packet.data, 0, packet.length).trim()
                        if (message.startsWith(PREFIX_DISCOVER)) {
                            val requestedPasskey = message.substringAfter(PREFIX_DISCOVER)
                            val remoteIp = packet.address.hostAddress
                            
                            LogRepository.addLog(
                                component = "NetworkDiscovery",
                                event = "discovery_request",
                                data = mapOf("ip" to (remoteIp ?: "unknown"), "requested_key" to requestedPasskey),
                                type = LogRepository.LogType.INCOMING
                            )
                            
                            if (requestedPasskey == passkey) {
                                // Match! Send our IP details back to the sender
                                // We reply directly to the sender's IP/Port
                                val response = "$PREFIX_OFFER$passkey" 
                                // We don't need to send IP in body, the receiver gets it from packet source.
                                // But to be explicit and helpful with port:
                                
                                val myIp = getLocalIpAddress()
                                val responsePayload = "$PREFIX_OFFER$myIp:$port"
                                val responseBytes = responsePayload.toByteArray()
                                
                                LogRepository.addLog(
                                    component = "NetworkDiscovery",
                                    event = "discovery_match",
                                    data = mapOf("target_ip" to (remoteIp ?: "unknown")),
                                    type = LogRepository.LogType.OUTGOING
                                )
                                
                                val replyPacket = DatagramPacket(
                                    responseBytes,
                                    responseBytes.size,
                                    packet.address,
                                    packet.port
                                )
                                socket?.send(replyPacket)
                                android.util.Log.d("NetworkDiscovery", "Replied to discovery from ${packet.address}")
                            } else {
                                LogRepository.addLog(
                                    component = "NetworkDiscovery",
                                    event = "discovery_ignored",
                                    data = mapOf("reason" to "passkey_mismatch", "ip" to (remoteIp ?: "unknown")),
                                    level = "INFO",
                                    type = LogRepository.LogType.INFO
                                )
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout allows loop to check isRunning()
                        continue
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } finally {
                socket?.close()
            }
        }
    }

    /**
     * Broadcasts a discovery packet and waits for a response.
     * @return The IP address of the host if found, null otherwise.
     */
    suspend fun discoverHost(context: Context, passkey: String): String? {
        return withContext(Dispatchers.IO) {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 3000 // Wait 3 seconds for reply

            try {
                // Broadcast address calculation
                val broadcastAddr = getBroadcastAddress(context) ?: InetAddress.getByName("255.255.255.255")
                
                val msg = "$PREFIX_DISCOVER$passkey"
                val data = msg.toByteArray()
                val packet = DatagramPacket(data, data.size, broadcastAddr, DISCOVERY_PORT)
                
                // Send multiple times to ensure delivery (UDP is unreliable)
                repeat(3) {
                    socket.send(packet)
                    Thread.sleep(100) 
                }

                // Listen for response
                val buf = ByteArray(1024)
                val responsePacket = DatagramPacket(buf, buf.size)
                
                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length).trim()
                    if (response.startsWith(PREFIX_OFFER)) {
                        // Payload: OFFER:192.168.1.5:8887
                        // Or just extract IP from packet if we trusted the sender
                        // Let's use the payload body for Port info
                        val result = response.substringAfter(PREFIX_OFFER)
                        // Result format: IP:PORT
                        // We return just IP to keep existing logic simple, or IP:Port?
                        // The user's ViewerScreen takes "hostIp" and appends :8887
                        // Let's parse.
                        
                        return@withContext result.substringBefore(":")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    return@withContext null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext null
            } finally {
                socket.close()
            }
            return@withContext null
        }
    }

    private fun getBroadcastAddress(context: Context): InetAddress? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifi.dhcpInfo ?: return null
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val quads = ByteArray(4)
        for (k in 0..3) quads[k] = ((broadcast shr k * 8) and 0xFF).toByte()
        return try {
            InetAddress.getByAddress(quads)
        } catch (e: Exception) {
            null
        }
    }

    // Helper duplicated from MainActivity (should be centralized ideally but copying for safety)
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }
}
