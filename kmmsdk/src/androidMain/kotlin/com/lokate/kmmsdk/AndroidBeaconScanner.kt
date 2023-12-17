package com.lokate.kmmsdk

import android.annotation.SuppressLint
import android.util.Log
import com.lokate.kmmsdk.domain.beacon.BeaconScanner
import com.lokate.kmmsdk.domain.beacon.CFlow
import com.lokate.kmmsdk.domain.beacon.Defaults.BEACON_LAYOUT_IBEACON
import com.lokate.kmmsdk.domain.beacon.Defaults.DEFAULT_PERIOD_BETWEEEN_SCAN
import com.lokate.kmmsdk.domain.beacon.Defaults.DEFAULT_PERIOD_SCAN
import com.lokate.kmmsdk.domain.beacon.wrap
import com.lokate.kmmsdk.domain.model.beacon.LokateBeacon
import com.lokate.kmmsdk.domain.model.beacon.BeaconProximity
import com.lokate.kmmsdk.domain.model.beacon.BeaconScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Identifier
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.Region
import kotlin.math.pow
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.GlobalScope
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Body
import retrofit2.http.POST
import com.google.gson.annotations.SerializedName

data class EventRequest(
    @SerializedName("customerId") val customerId: String,
    @SerializedName("beaconUID") val beaconUID: String,
    @SerializedName("status") val status: String,
    @SerializedName("timestamp") val timestamp: Long
)

interface BeaconApiService {
    @GET("/mobile/activeBeacons")
    fun getActiveBeacons(@Query("branchId") branchId: String): Call<JsonArray>
    @POST("/mobile/beaconArea")
    fun postBeaconArea(@Body request: EventRequest): Call<Void>
}

class BeaconApiClient {
    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("http://192.168.1.42:5173")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val beaconApiService: BeaconApiService = retrofit.create(BeaconApiService::class.java)

    suspend fun fetchBeacons(branchId: String): List<LokateBeacon> {
        val call = beaconApiService.getActiveBeacons(branchId)
        val response = call.execute()

        if (response.isSuccessful) {
            val jsonArray = response.body()
            jsonArray?.let {
                val beacons = mutableListOf<LokateBeacon>()
                for (jsonElement in it) {
                    if (jsonElement is JsonObject) {

                        val range = jsonElement.getAsJsonPrimitive("range").asString
                        if (range == "invalid") continue    // beacon is not active

                        val minProximity = when(range) {
                            "immediate" -> BeaconProximity.Immediate
                            "near" -> BeaconProximity.Near
                            "far" -> BeaconProximity.Far
                            else -> BeaconProximity.Unknown
                        }

                        val beacon = LokateBeacon(
                            jsonElement.getAsJsonPrimitive("id").asString,
                            jsonElement.getAsJsonPrimitive("major").asInt,
                            jsonElement.getAsJsonPrimitive("minor").asInt,
                            jsonElement.getAsJsonObject("campaign").getAsJsonPrimitive("name").asString,
                            minProximity
                        )

                        beacons.add(beacon)
                    }
                }
                return beacons
            }
        } else {
            // Handle error
            println("Error: ${response.code()}")
        }

        return emptyList()
    }

    fun postBeaconArea(request: EventRequest): Call<Void> {
        return beaconApiService.postBeaconArea(request)
    }
}

//TODO do not expose this class and require to use a helper class and add business logic there
class AndroidBeaconScanner : BeaconScanner {

    private val scanBeaconFlow: MutableSharedFlow<List<BeaconScanResult>> =
        MutableSharedFlow()
    private val scanNonBeaconFlow: MutableSharedFlow<List<BeaconScanResult>> =
        MutableSharedFlow()

    private var isScanning = false
    private var scanPeriodMillis: Long = DEFAULT_PERIOD_SCAN
    private var betweenScanPeriod: Long = DEFAULT_PERIOD_BETWEEEN_SCAN

    private val lastScannedBeacons = mutableSetOf<BeaconScanResult>()
    private val lastScannedNonBeacons = mutableListOf<BeaconScanResult>()

    private val beaconRegions = mutableListOf<Region>()
    private val beaconMap = mutableMapOf<String, LokateBeacon>()

    private val beaconApiClient = BeaconApiClient()

    companion object {
        private val manager: BeaconManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            BeaconManager.getInstanceForApplication(applicationContext).apply {
                beaconParsers.add(BeaconParser().setBeaconLayout(BEACON_LAYOUT_IBEACON))
                setEnableScheduledScanJobs(false)
                isRegionStatePersistenceEnabled = false
            }
        }
    }

    init {
        setScanPeriod(scanPeriodMillis)
        setBetweenScanPeriod(betweenScanPeriod)
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private var beaconEmitJob: Job? = null
    private var nonBeaconEmitJob: Job? = null

    private var rssiThreshold: Int? = null


    override fun setScanPeriod(scanPeriodMillis: Long) {
        this.scanPeriodMillis = scanPeriodMillis
    }

    override fun setBetweenScanPeriod(betweenScanPeriod: Long) {
        this.betweenScanPeriod = betweenScanPeriod
    }

    override fun observeResults(): CFlow<List<BeaconScanResult>> {
        return scanBeaconFlow.wrap()
    }

    override fun observeNonBeaconResults(): CFlow<List<BeaconScanResult>> {
        TODO("Not planning to implement this")
    }

    override fun setIosRegions(regions: List<LokateBeacon>) {
        throw UnsupportedOperationException("set Android Region on Android")
    }

    override fun setAndroidRegions(beacons: List<LokateBeacon>) {
        beaconRegions.clear()
        beaconRegions.addAll(beacons.map {
            Region(
                it.uuid,
                Identifier.parse(it.uuid),
                null,
                null
            )
        })
    }

    override fun setRssiThreshold(threshold: Int) {
        rssiThreshold = threshold
    }

    override fun observeErrors(): CFlow<Exception> {
        TODO("Not planning to implement this")
    }

    private fun sendEnterEvent(beacon: LokateBeacon) {
        val request = EventRequest(
            customerId = "customer1",
            beaconUID = beacon.uuid,
            status = "ENTER",
            timestamp = System.currentTimeMillis() / 1000   // seconds
        )

        GlobalScope.launch(Dispatchers.IO) {
            val postResponse = beaconApiClient.postBeaconArea(request).execute()

            if (postResponse.isSuccessful) {
                println("POST request successful")
            } else {
                println("POST request failed: ${postResponse.code()}")
            }
        }
    }

    private fun sendExitEvent(beacon: LokateBeacon) {
        val request = EventRequest(
            customerId = "customer1",
            beaconUID = beacon.uuid,
            status = "EXIT",
            timestamp = System.currentTimeMillis() / 1000   // seconds
        )

        GlobalScope.launch(Dispatchers.IO) {
            val postResponse = beaconApiClient.postBeaconArea(request).execute()

            if (postResponse.isSuccessful) {
                println("POST request successful")
            } else {
                println("POST request failed: ${postResponse.code()}")
            }
        }
    }

    private fun filterScannedBeacons(scannedBeacons: Set<BeaconScanResult>): Set<LokateBeacon> {
        val filteredBeacons = mutableSetOf<LokateBeacon>()
        for (scannedBeacon in scannedBeacons) {
            val beacon = beaconMap[scannedBeacon.beaconUUID]
            if (beacon != null && scannedBeacon.proximity <= beacon.minProximity) {
                filteredBeacons.add(beacon)
            }
        }
        return filteredBeacons
    }

    // todo: remove parameter and move callback to constructor
    private fun startEmittingBeaconsJob(regionStayCallback: (List<String>) -> Unit) {
        beaconEmitJob?.cancel()
        beaconEmitJob = scope.launch {
            var lastRegionEnteredBeacons: Set<LokateBeacon> = setOf()
            var currentRegionEnteredBeacons: Set<LokateBeacon>;
            var stayCount = 0
            var longStayBeacons = mutableSetOf<LokateBeacon>()
            while (isScanning) {
                scanBeaconFlow.emit(lastScannedBeacons.toList())
                currentRegionEnteredBeacons = filterScannedBeacons(lastScannedBeacons)
                for (beacon in currentRegionEnteredBeacons) {
                    if (lastRegionEnteredBeacons.contains(beacon)) {
                        stayCount++
                        if (stayCount == 1 /* adjust it according to when you want to send notification*/) {
                            // staying in region for a long time
                            longStayBeacons.add(beacon)
                            stayCount = 0
                        }
                    } else {
                        // region entered
                        sendEnterEvent(beacon)
                        Log.d("BeaconScanner", "Region entered: $beacon")
                    }
                }
                for (beacon in lastRegionEnteredBeacons) {
                    if (!currentRegionEnteredBeacons.contains(beacon)){
                        // region exited
                        sendExitEvent(beacon)
                        stayCount = 0
                        Log.d("BeaconScanner", "Region exited: $beacon")
                    }
                }
                regionStayCallback(longStayBeacons.map{
                  it.campaign
                })
                longStayBeacons.clear()
                lastRegionEnteredBeacons = currentRegionEnteredBeacons
                lastScannedBeacons.clear()
                kotlinx.coroutines.delay(scanPeriodMillis)
            }
        }
    }
    // todo: maybe remove parameters from start function and move them to constructor?
    @SuppressLint("MissingPermission")
    override fun start(branchId: String, regionStayCallback: (List<String>) -> Unit) {
        if (isScanning)
            stop()

        isScanning = true

        GlobalScope.launch(Dispatchers.IO) {
            val beaconsToBeScanned: List<LokateBeacon> = getBeaconsOfBranch(branchId)
            setAndroidRegions(beaconsToBeScanned)

            with(manager) {
                removeAllRangeNotifiers()
                removeAllMonitorNotifiers()

                beaconRegions.forEach { region ->

                    addMonitorNotifier(object : MonitorNotifier {
                        override fun didEnterRegion(region: Region) {
                            Log.d("BeaconScanner", "didEnterRegion: $region")
                        }

                        override fun didExitRegion(region: Region) {
                            Log.d("BeaconScanner", "didExitRegion: $region")
                        }

                        override fun didDetermineStateForRegion(state: Int, region: Region) {
                            Log.d("BeaconScanner", "didDetermineStateForRegion: $region")
                        }
                    })

                    addRangeNotifier { beacons, a ->
                        if (rssiThreshold == null) {
                            // somehow, this callback is called twice
                            // therefore, possibly duplicates are introduced in lastScannedBeacons
                            // to avoid that, lastScannedBeacons is declared as a set
                            lastScannedBeacons.addAll(beacons.map {
                                it.toBeaconScanResult()
                            })
                        } else {
                            lastScannedBeacons.addAll(beacons.filter { it.rssi >= rssiThreshold!! }
                                .map { it.toBeaconScanResult() })
                        }
                    }

                    startMonitoring(region)
                    startRangingBeacons(region)
                }
            }

            startEmittingBeaconsJob(regionStayCallback)
        }

    }

    private suspend fun getBeaconsOfBranch(branchId: String): List<LokateBeacon> {
        val apiBeacons = beaconApiClient.fetchBeacons(branchId)
        if (apiBeacons.isEmpty()) {
            Log.d("BeaconAPI", "Returned set of beacons from api call is empty")
        } else {
            Log.d("BeaconAPI", "Returned beacons: $apiBeacons")
        }

        for (beacon in apiBeacons) {
            beaconMap[beacon.uuid] = beacon
        }

        return apiBeacons

//        val apiBeacons = listOf(
//            LokateBeacon(
//                "5d72cc30-5c61-4c09-889f-9ae750fa84ec",
//                1,
//                1,
//                "deterjan",
//                BeaconProximity.Immediate
//            ) //pink
//            ,
//            LokateBeacon(
//                "b9407f30-f5f8-466e-aff9-25556b57fe6d",
//                24719,
//                28241,
//                "tavuk",
//                BeaconProximity.Far
//            ) //red
//        )
    }

    private fun org.altbeacon.beacon.Beacon.toBeaconScanResult(): BeaconScanResult {
        val beaconUUID = id1.toString()
        val accuracy = calculateAccuracy(txPower, rssi.toDouble())
        val proximity = calculateProximity(accuracy)
        return BeaconScanResult(
            beaconUUID = beaconUUID,
            rssi = rssi.toDouble(),
            txPower = txPower,
            accuracy = accuracy,
            proximity = proximity
        )
    }


    //taken from kmmbeacons
    private fun calculateProximity(accuracy: Double): BeaconProximity = when (accuracy) {
        in 0.0..0.5 -> BeaconProximity.Immediate
        in 0.5..3.0 -> BeaconProximity.Near
        in 3.0..Double.MAX_VALUE -> BeaconProximity.Far
        else -> BeaconProximity.Unknown

    }


    private fun startEmittingNonBeaconsJob() {
        nonBeaconEmitJob?.cancel()
        nonBeaconEmitJob = scope.launch {
            while (isScanning) {
                scanNonBeaconFlow.emit(lastScannedNonBeacons.toList())
                lastScannedNonBeacons.clear()
                kotlinx.coroutines.delay(scanPeriodMillis)
            }
        }
    }

    override fun stop() {
        isScanning = false
        beaconEmitJob?.cancel()
        nonBeaconEmitJob?.cancel()
        manager.removeAllRangeNotifiers()
        manager.removeAllMonitorNotifiers()
    }

    //taken from kmmbeacons
    private fun calculateAccuracy(txPower: Int, rssi: Double): Double {
        if (rssi == 0.0) {
            return -1.0 // if we cannot determine accuracy, return -1.
        }

        val ratio = rssi * 1.0 / txPower
        return if (ratio < 1.0) {
            ratio.pow(10.0)
        } else {
            val accuracy = 0.89976 * ratio.pow(7.7095) + 0.111
            accuracy
        }
    }

}