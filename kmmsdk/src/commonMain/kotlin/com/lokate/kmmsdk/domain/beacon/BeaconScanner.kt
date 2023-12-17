package com.lokate.kmmsdk.domain.beacon

import com.lokate.kmmsdk.domain.model.beacon.LokateBeacon
import com.lokate.kmmsdk.domain.model.beacon.BeaconScanResult
import io.ktor.utils.io.core.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * we are adopting a similar api with kmm beacons, we will have some serious modifications over them so we cannot use directly their implementations.
 */

object Defaults{
    const val DEFAULT_PERIOD_SCAN = 1000L
    const val DEFAULT_PERIOD_BETWEEEN_SCAN = 250L
    const val BEACON_LAYOUT_IBEACON = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25,i:0-56"
    const val MINIMUM_SECONDS_BEFORE_EXIT = 10
}
interface BeaconScanner {
    fun setScanPeriod(scanPeriodMillis: Long)
    fun setBetweenScanPeriod(betweenScanPeriod:Long)

    fun observeResults(): CFlow<List<BeaconScanResult>>

    fun observeNonBeaconResults(): CFlow<List<BeaconScanResult>>

    fun setIosRegions(regions: List<LokateBeacon>)
    fun setAndroidRegions(beacons: List<LokateBeacon>)

    fun setRssiThreshold(threshold: Int)
    fun observeErrors(): CFlow<Exception>
    fun start(branchId: String, regionStayCallback: (List<String>) -> Unit)
    fun stop()
}


fun <T> Flow<T>.wrap(): CFlow<T> = CFlow(this)

class CFlow<T>(private val origin: Flow<T>) : Flow<T> by origin {
    fun watch(block: (T) -> Unit): Closeable {
        val job = Job()

        onEach {
            block(it)
        }.launchIn(CoroutineScope(Dispatchers.Main + job))

        return object : Closeable {
            override fun close() {
                job.cancel()
            }
        }
    }
}