package com.boost12.rd12.data.adb.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.boost12.rd12.domain.model.EndpointType
import com.boost12.rd12.domain.model.MdnsDiscoveredEndpoint
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers the Redmi 12 4G's own Wireless Debugging mDNS services (`_adb-tls-pairing._tcp`
 * and `_adb-tls-connect._tcp`) via Android [NsdManager], filtering strictly to the device's
 * own local network interface IP addresses.
 */
class AdbMdnsDiscovery(
    context: Context,
    private val onEndpointDiscovered: (MdnsDiscoveredEndpoint) -> Unit,
    private val onEndpointLost: (EndpointType) -> Unit,
    private val onTrace: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val running = AtomicBoolean(false)
    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null

    fun start() {
        val manager = nsdManager ?: return
        if (!running.compareAndSet(false, true)) return

        runCatching {
            multicastLock = wifiManager?.createMulticastLock("boost12_adb_mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        pairingListener = createDiscoveryListener(
            serviceType = SERVICE_TLS_PAIRING,
            endpointType = EndpointType.TLS_PAIRING,
            manager = manager
        )
        connectListener = createDiscoveryListener(
            serviceType = SERVICE_TLS_CONNECT,
            endpointType = EndpointType.TLS_CONNECT,
            manager = manager
        )

        runCatching {
            manager.discoverServices(
                SERVICE_TLS_PAIRING,
                NsdManager.PROTOCOL_DNS_SD,
                pairingListener
            )
            onTrace("Started mDNS scan for $SERVICE_TLS_PAIRING")
        }
        runCatching {
            manager.discoverServices(
                SERVICE_TLS_CONNECT,
                NsdManager.PROTOCOL_DNS_SD,
                connectListener
            )
            onTrace("Started mDNS scan for $SERVICE_TLS_CONNECT")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val manager = nsdManager
        pairingListener?.let { listener ->
            runCatching { manager?.stopServiceDiscovery(listener) }
        }
        connectListener?.let { listener ->
            runCatching { manager?.stopServiceDiscovery(listener) }
        }
        pairingListener = null
        connectListener = null

        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
        multicastLock = null
    }

    private fun createDiscoveryListener(
        serviceType: String,
        endpointType: EndpointType,
        manager: NsdManager
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                onTrace("mDNS start failed ($serviceType, code=$errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String?) = Unit

            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null || !running.get()) return
                resolveServiceSafely(manager, serviceInfo, endpointType)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                onEndpointLost(endpointType)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveServiceSafely(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo,
        endpointType: EndpointType
    ) {
        runCatching {
            manager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(failedInfo: NsdServiceInfo?, errorCode: Int) {
                        onTrace("mDNS resolve failed (${failedInfo?.serviceName}, code=$errorCode)")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo?) {
                        if (resolved == null) return
                        val hostAddr = resolved.host ?: return
                        val port = resolved.port
                        if (port <= 0) return

                        if (isOwnDeviceAddress(hostAddr)) {
                            val hostStr = hostAddr.hostAddress ?: "127.0.0.1"
                            val endpoint = MdnsDiscoveredEndpoint(
                                serviceName = resolved.serviceName.orEmpty(),
                                host = hostStr,
                                port = port,
                                type = endpointType
                            )
                            onTrace("mDNS discovered own ${endpointType.name} at ${endpoint.addressLabel}")
                            onEndpointDiscovered(endpoint)
                        }
                    }
                }
            )
        }
    }

    companion object {
        const val SERVICE_TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val SERVICE_TLS_CONNECT = "_adb-tls-connect._tcp"

        fun findDeviceIpv4Address(): String? {
            return runCatching {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (nif in interfaces) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in Collections.list(nif.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
                null
            }.getOrNull()
        }

        fun isOwnDeviceAddress(candidate: InetAddress): Boolean {
            if (candidate.isLoopbackAddress) return true
            return runCatching {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                interfaces.any { nif ->
                    nif.isUp && Collections.list(nif.inetAddresses).any { local ->
                        local.hostAddress == candidate.hostAddress
                    }
                }
            }.getOrDefault(false)
        }
    }
}
