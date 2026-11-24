package app.pwhs.blockads.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import app.pwhs.blockads.utils.AppNameResolver
import timber.log.Timber
import tunnel.AppResolver
import tunnel.Engine
import tunnel.UIDResolver
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale

internal object GoTunnelResolvers {
    fun setupUidResolver(context: Context, engine: Engine) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        engine.setUIDResolver(UIDResolver { protocol, localIP, localPort, remoteIP, remotePort ->
            try {
                val protoInt = protocol.toInt()
                val portInt = localPort.toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cm != null) {
                    val osProto = when (protoInt) {
                        6 -> OsConstants.IPPROTO_TCP
                        17 -> OsConstants.IPPROTO_UDP
                        else -> return@UIDResolver -1L
                    }
                    val local = InetSocketAddress(InetAddress.getByName(localIP), portInt)
                    val remote = InetSocketAddress(InetAddress.getByName(remoteIP), remotePort.toInt())
                    val uid = cm.getConnectionOwnerUid(osProto, local, remote)
                    if (uid >= 0) return@UIDResolver uid.toLong()
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    return@UIDResolver findUidInProcNet(protoInt, portInt).toLong()
                }
                -1L
            } catch (e: Exception) {
                -1L
            }
        })
    }

    internal fun findUidInProcNet(protocol: Int, localPort: Int): Int {
        if (localPort <= 0) return -1
        val hexPort = String.format(Locale.US, "%04X", localPort)
        val paths = when (protocol) {
            6 -> arrayOf("/proc/net/tcp", "/proc/net/tcp6")
            17 -> arrayOf("/proc/net/udp", "/proc/net/udp6")
            else -> return -1
        }
        for (path in paths) {
            findUidInProcFile(File(path), hexPort)?.let { if (it >= 0) return it }
        }
        return -1
    }

    internal fun findUidInProcFile(file: File, hexPort: String): Int? {
        if (!file.exists()) return null
        return try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLine() ?: return null
                var line = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val parts = trimmed.split(WHITESPACE_REGEX)
                        if (parts.size >= 8) {
                            val localAddress = parts[1]
                            val colonIdx = localAddress.lastIndexOf(':')
                            if (colonIdx >= 0) {
                                val portHex = localAddress.substring(colonIdx + 1)
                                if (portHex.equals(hexPort, ignoreCase = true)) {
                                    parts[7].toIntOrNull()?.let { return it }
                                }
                            }
                        }
                    }
                    line = reader.readLine()
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private val WHITESPACE_REGEX = "\\s+".toRegex()

    fun setupAppUidResolver(context: Context, engine: Engine) {
        engine.setAppUidResolver(tunnel.AppUidResolver { uid ->
            try {
                val uidInt = uid.toInt()
                // Fallback to appId (uid % 100_000) for Secure Folder / Private Space / Work Profile UIDs.
                context.packageManager.getPackagesForUid(uidInt)?.firstOrNull()
                    ?: (if (uidInt >= 100_000) {
                        context.packageManager.getPackagesForUid(uidInt % 100_000)?.firstOrNull()
                    } else {
                        null
                    })
                    ?: ""
            } catch (e: Exception) {
                ""
            }
        })
    }

    fun setupAppResolver(engine: Engine, appNameResolver: AppNameResolver) {
        engine.setAppResolver(AppResolver { sourcePort, sourceIP, destIP, destPort ->
            try {
                val identity = appNameResolver.resolveIdentity(
                    sourcePort.toInt(), sourceIP, destIP, destPort.toInt()
                )
                when {
                    identity.packageName.isNotEmpty() -> identity.packageName
                    identity.appName.isNotEmpty() -> identity.appName
                    else -> ""
                }
            } catch (e: Exception) {
                Timber.e(e, "App resolve failed")
                ""
            }
        })
    }
}
