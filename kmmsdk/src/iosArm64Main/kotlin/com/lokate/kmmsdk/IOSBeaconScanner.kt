package com.lokate.kmmsdk

import com.lokate.kmmsdk.domain.beacon.BeaconScanner
import com.lokate.kmmsdk.domain.beacon.CFlow
import com.lokate.kmmsdk.domain.beacon.Defaults
import com.lokate.kmmsdk.domain.beacon.Defaults.MINIMUM_SECONDS_BEFORE_EXIT
import com.lokate.kmmsdk.domain.beacon.wrap
import com.lokate.kmmsdk.domain.model.beacon.Beacon
import com.lokate.kmmsdk.domain.model.beacon.BeaconProximity
import com.lokate.kmmsdk.domain.model.beacon.BeaconScanResult
import com.lokate.kmmsdk.utils.extension.emptyString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import platform.CoreLocation.CLBeacon
import platform.CoreLocation.CLBeaconIdentityConstraint
import platform.CoreLocation.CLBeaconRegion
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLProximity
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

class IOSBeaconScanner : BeaconScanner {
    object DefaultBeacons {
        val beacons = listOf(
            Beacon(
                "1",
                "B9407F30-F5F8-466E-AFF9-25556B57FE6D",
                24719,
                65453
            ),//white
            Beacon(
                "2",
                "5D72CC30-5C61-4C09-889F-9AE750FA84EC",
                1,
                1
            )//pink
            ,
            Beacon(
                "3",
                "B9407F30-F5F8-466E-AFF9-25556B57FE6D",
                24719,
                28241
            ),//red
            Beacon(
                "4",
                "25E296BD-A76C-4013-8E90-4898977A6E1B",
                24719,
                11975
            )
        )//yellow)
    }

    internal class IOSBeaconScannerHandler : NSObject(), CLLocationManagerDelegateProtocol {

        private val NOT_DETERMINED = 0
        private val RESTRICTED = 1
        private val DENIED = 2
        private val AUTHORIZED_ALWAYS = 3
        private val AUTHORIZED_WHEN_IN_USE = 4

        private var scanPeriodMillis = Defaults.DEFAULT_PERIOD_SCAN

        private val _errorObservable = MutableSharedFlow<Exception>()
        private val _beaconScanResultObservable = MutableSharedFlow<List<BeaconScanResult>>()
        private val _inRegionObservable = MutableSharedFlow<BeaconScanResult>()
        private val regions = mutableListOf<Beacon>()

        private val manager = CLLocationManager()

        init {
            NSLog("IOSBeaconScannerHandler initialized")
            manager.delegate = this
            manager.allowsBackgroundLocationUpdates = true
        }

        private fun startRangingBeacons() {
            NSLog("startRangingBeacons called")
            if (regions.isEmpty()) {
                NSLog("Adding default beacons")
                regions.addAll(DefaultBeacons.beacons)
            }
            beaconEmitJob?.cancel()
            beaconEmitJob = scope.launch {
                NSLog("Starting beacon emitting job")
                while (true) {
                    removeInvalidRegions()
                    var beaconWithMaxRssi =
                        regionedBeacons.filter { it.proximity == BeaconProximity.Immediate }
                            .maxByOrNull { it.rssi }
                    if (beaconWithMaxRssi == null)
                        beaconWithMaxRssi =
                            regionedBeacons.filter { it.proximity == BeaconProximity.Near }
                                .maxByOrNull { it.rssi }
                    if (beaconWithMaxRssi != null)
                        _inRegionObservable.emit(beaconWithMaxRssi)
                    else
                        _inRegionObservable.emit(BeaconScanResult(
                            Beacon(
                                emptyString(),
                                emptyString(),
                                0,
                                0
                            ),
                            0.0,
                            0,
                            0.0,
                            BeaconProximity.Unknown,
                            NSDate().timeIntervalSince1970.toLong(),
                            NSDate().timeIntervalSince1970.toLong()
                        ))
                    NSLog("Emitting beacon: $beaconWithMaxRssi")
                    kotlinx.coroutines.delay(scanPeriodMillis)
                }
            }
            regions.forEach {
                NSLog("Starting ranging for beacon: UUID - ${it.uuid}, Major - ${it.major}, Minor - ${it.minor}")
                manager.startRangingBeaconsInRegion(it.toCLBeaconRegion())
            }
        }

        private fun Beacon.toCLBeaconRegion(): CLBeaconRegion {
            NSLog("Converting Beacon to CLBeaconRegion: UUID - ${this.uuid}, Major - ${this.major}, Minor - ${this.minor}")
            return CLBeaconRegion(
                uUID = NSUUID(this.uuid),
                major = this.major.toUShort(),
                minor = this.minor.toUShort(),
                identifier = emptyString()
            )
        }

        private fun stopRangingBeacons(beacons: List<Beacon>) {
            beacons.forEach {
                NSLog("Stopping ranging for beacon: UUID - ${it.uuid}, Major - ${it.major}, Minor - ${it.minor}")
                manager.stopRangingBeaconsInRegion(it.toCLBeaconRegion())
            }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            NSLog("locationManagerDidChangeAuthorization called")
            startRegionOrAskPermissions()
        }

        override fun locationManager(
            manager: CLLocationManager,
            didChangeAuthorizationStatus: Int
        ) {
            NSLog("locationManager didChangeAuthorizationStatus: Status - $didChangeAuthorizationStatus")
            when (didChangeAuthorizationStatus) {
                NOT_DETERMINED -> {
                    NSLog("Requesting location permissions")
                    manager.requestAlwaysAuthorization()
                }

                AUTHORIZED_WHEN_IN_USE, AUTHORIZED_ALWAYS -> {
                    NSLog("Permissions granted, starting ranging for regions")
                    startRangingForRegions()
                }

                DENIED, RESTRICTED -> {
                    NSLog("Localization permission denied")
                    _errorObservable.tryEmit(Exception("Localization permission denied"))
                }

                else -> {
                    NSLog("Requesting location")
                    manager.requestLocation()
                }
            }
        }

        override fun locationManager(
            manager: CLLocationManager,
            rangingBeaconsDidFailForRegion: CLBeaconRegion,
            withError: NSError
        ) {
            NSLog("rangingBeaconsDidFailForRegion: Error - ${withError.localizedDescription}")
        }

        private fun CLBeacon.toBeaconScanResult(): BeaconScanResult {
            NSLog("Converting CLBeacon to BeaconScanResult: UUID - ${this.proximityUUID.UUIDString}, RSSI - ${this.rssi}")
            return BeaconScanResult(
                Beacon(
                    emptyString(),
                    this.proximityUUID.UUIDString,
                    this.major.intValue,
                    this.minor.intValue
                ),
                this.rssi.toDouble(),
                0,
                0.0,
                this.proximity.toLokateProximity(),
                NSDate().timeIntervalSince1970.toLong()
            )
        }

        private fun CLProximity.toLokateProximity(): BeaconProximity {
            NSLog("Converting CLProximity to LokateProximity: Proximity - $this")
            return when (this) {
                CLProximity.CLProximityUnknown -> BeaconProximity.Unknown
                CLProximity.CLProximityImmediate -> BeaconProximity.Immediate
                CLProximity.CLProximityNear -> BeaconProximity.Near
                CLProximity.CLProximityFar -> BeaconProximity.Far
                else -> BeaconProximity.Unknown
            }
        }

        override fun locationManager(
            manager: CLLocationManager,
            didRangeBeacons: List<*>,
            inRegion: CLBeaconRegion
        ) {
            NSLog("locationManager didRangeBeacons in region: ${inRegion.identifier}")
            didRangeBeacons.forEach {
                with((it as CLBeacon).toBeaconScanResult()) {
                    NSLog("Beacon ranged: $this")
                    if (this.rssi < 0 && this.proximity != BeaconProximity.Unknown) {
                        if (regionedBeacons.none { it.beacon.uuid == this.beacon.uuid && it.beacon.major == this.beacon.major && it.beacon.minor == this.beacon.minor }) {
                            regionedBeacons.add(
                                this.copy(
                                    firstSeen = NSDate().timeIntervalSince1970.toLong(),
                                    lastSeen = NSDate().timeIntervalSince1970.toLong()
                                )
                            )
                        } else {
                            val beacon =
                                regionedBeacons.first { it.beacon.uuid == this.beacon.uuid && it.beacon.major == this.beacon.major && it.beacon.minor == this.beacon.minor }
                            regionedBeacons.removeAll { it.beacon.uuid == this.beacon.uuid && it.beacon.major == this.beacon.major && it.beacon.minor == this.beacon.minor }
                            regionedBeacons.add(beacon.copy(lastSeen = NSDate().timeIntervalSince1970.toLong()))
                        }
                    }
                }
            }
        }
        private fun removeInvalidRegions() {
            val currentTimeStamp = NSDate().timeIntervalSince1970.toLong()
            regionedBeacons.filter { currentTimeStamp - it.lastSeen > MINIMUM_SECONDS_BEFORE_EXIT }
                .forEach {
                    NSLog("Beacon removed: $it, timestamp: $currentTimeStamp, first seen: ${it.firstSeen} last timestamp: ${it.lastSeen}")
                    regionedBeacons.removeAll { it.beacon.uuid == it.beacon.uuid && it.beacon.major == it.beacon.major && it.beacon.minor == it.beacon.minor }
                }
        }

        override fun locationManager(
            manager: CLLocationManager,
            didFailRangingBeaconsForConstraint: CLBeaconIdentityConstraint,
            error: NSError
        ) {
            NSLog("locationManager didFailRangingBeaconsForConstraint: Error - ${error.localizedDescription}")
            regionedBeacons.removeAll { it.beacon.uuid == didFailRangingBeaconsForConstraint.UUID.UUIDString && it.beacon.major == didFailRangingBeaconsForConstraint.major?.intValue && it.beacon.minor == didFailRangingBeaconsForConstraint.minor?.intValue }
        }

        private val job = SupervisorJob()
        private val scope = CoroutineScope(Dispatchers.Main + job)

        private var beaconEmitJob: Job? = null
        private var regionedBeacons = mutableListOf<BeaconScanResult>()


        private fun startRangingForRegions() {
            NSLog("startRangingForRegions called")
            startRangingBeacons()
        }

        private fun startRegionOrAskPermissions() {
            NSLog("startRegionOrAskPermissions called")
            NSLog("Checking location permissions")
            NSLog("Authorization status: ${manager.authorizationStatus}")
            when (manager.authorizationStatus) {
                NOT_DETERMINED -> {
                    NSLog("Requesting location permissions")
                    manager.requestAlwaysAuthorization()
                }

                AUTHORIZED_WHEN_IN_USE, AUTHORIZED_ALWAYS -> {
                    NSLog("Permissions granted, starting ranging for regions")
                    startRangingForRegions()
                }

                DENIED, RESTRICTED -> {
                    NSLog("Localization permission denied")
                    _errorObservable.tryEmit(Exception("Localization permission denied"))
                }

                else -> {
                    NSLog("Requesting location")
                    manager.requestLocation()
                }
            }
        }

        fun setScanPeriod(scanPeriodMillis: Long) {
            NSLog("setScanPeriod called")
            this.scanPeriodMillis = scanPeriodMillis
        }

        fun observeResuls(): CFlow<List<BeaconScanResult>> {
            return _beaconScanResultObservable.wrap()
        }

        fun observeNonBeaconResults(): CFlow<List<BeaconScanResult>> {
            throw UnsupportedOperationException("Not planning to implement this")
        }

        fun setIosRegions(regions: List<Beacon>) {
            this.regions.clear()
            this.regions.addAll(regions)
        }

        fun setAndroidRegions(region: List<Beacon>) {
            throw UnsupportedOperationException("set iOS Region on iOS")
        }

        fun setRssiThreshold(threshold: Int) {
            throw UnsupportedOperationException("Cannot set RSSI threshold on iOS")
        }

        fun observeErrors(): CFlow<Exception> {
            return _errorObservable.wrap()
        }

        fun observeRegion(): CFlow<BeaconScanResult> {
            return _inRegionObservable.wrap()
        }

        fun start() {
            NSLog("start called")
            manager.requestAlwaysAuthorization()
            startRegionOrAskPermissions()
        }

        fun stop() {
            NSLog("stop called")
            stopRanging()

            beaconEmitJob?.cancel()
        }

        private fun stopRanging() {
            stopRangingBeacons(regions)
        }

    }

    companion object {
        private val handler: IOSBeaconScannerHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            IOSBeaconScannerHandler()
        }
    }

    init {
        //handler.setBetweenScanPeriod(Defauls.DEFAULT_PERIOD_BETWEEEN_SCAN)
        //handler.setScanPeriod(Defauls.DEFAULT_PERIOD_SCAN)
    }

    override fun setScanPeriod(scanPeriodMillis: Long) {
        handler.setScanPeriod(scanPeriodMillis)
    }

    override fun setBetweenScanPeriod(betweenScanPeriod: Long) {
        //handler.setBetweenScanPeriod(betweenScanPeriod)
    }

    override fun observeResults(): CFlow<List<BeaconScanResult>> {
        return handler.observeResuls()
    }

    override fun observeNonBeaconResults(): CFlow<List<BeaconScanResult>> {
        return handler.observeNonBeaconResults()
    }

    override fun setIosRegions(regions: List<Beacon>) {
        handler.setIosRegions(regions)
    }

    override fun setAndroidRegions(region: List<Beacon>) {
        handler.setAndroidRegions(region)
    }

    override fun setRssiThreshold(threshold: Int) {
        handler.setRssiThreshold(threshold)
    }

    override fun observeErrors(): CFlow<Exception> {
        return handler.observeErrors()
    }

    override fun start() {
        handler.start()
    }

    override fun stop() {
        handler.stop()
    }

    fun observeRegion(): CFlow<BeaconScanResult> {
        return handler.observeRegion()
    }
}