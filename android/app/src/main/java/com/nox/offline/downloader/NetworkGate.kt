package com.nox.offline.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.nox.offline.core.NoxLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Можно ли сейчас передавать данные. Смотрит на настоящий тип сети
 * (ConnectivityManager), а не на название точки доступа.
 * «Только Wi-Fi» = транспорт Wi-Fi или Ethernet; мобильная сеть — ждать.
 */
class NetworkGate(context: Context, private val wifiOnly: () -> Boolean) {
    enum class State { OK, NO_NETWORK, METERED }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(compute())
    val state: StateFlow<State> = _state

    /** Кого позвать, когда сеть или правило поменялись. */
    @Volatile
    var onChanged: ((State) -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    fun start() {
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
            .onFailure { NoxLog.event("network-gate-error", "error" to it.javaClass.simpleName) }
    }

    /** Пересчитать (в том числе после смены настройки «Только Wi-Fi»). */
    fun refresh() {
        val next = compute()
        val prev = _state.value
        _state.value = next
        if (next != prev) {
            NoxLog.event("network-gate", "state" to next.name, "wifiOnly" to wifiOnly())
            onChanged?.invoke(next)
        }
    }

    fun allowed(): Boolean = compute() == State.OK

    private fun compute(): State {
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return State.NO_NETWORK
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return State.NO_NETWORK
        return decide(
            wifiOnly = wifiOnly(),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        )
    }

    companion object {
        /** Чистое правило: при «Только Wi-Fi» годится только Wi-Fi или Ethernet (по типу сети, не по имени). */
        fun decide(wifiOnly: Boolean, wifi: Boolean): State = if (!wifiOnly || wifi) State.OK else State.METERED
    }
}
