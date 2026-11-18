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
import java.net.InetAddress
import java.net.InetSocketAddress

internal object GoTunnelResolvers {
    fun setupUidResolver(context: Context, engine: Engine) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        engine.setUIDResolver(UIDResolver { protocol, localIP, localPort, remoteIP, remotePort ->
            try {
                val proto = when (protocol.toInt()) {
                    6 -> OsConstants.IPPROTO_TCP
                    17 -> OsConstants.IPPROTO_UDP
                    else -> return@UIDResolver -1L
                }
                val local = InetSocketAddress(InetAddress.getByName(localIP), localPort.toInt())
                val remote = InetSocketAddress(InetAddress.getByName(remoteIP), remotePort.toInt())
                cm.getConnectionOwnerUid(proto, local, remote).toLong()
            } catch (e: Exception) {
                -1L
            }
        })
    }

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
