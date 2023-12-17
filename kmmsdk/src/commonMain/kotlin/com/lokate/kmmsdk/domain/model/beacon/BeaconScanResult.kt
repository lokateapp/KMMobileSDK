package com.lokate.kmmsdk.domain.model.beacon

data class BeaconScanResult(
    val beaconUUID: String,
    val rssi: Double,
    val txPower: Int,
    val accuracy: Double?,
    val proximity: BeaconProximity,
    val firstSeen: Long = 0,
    val lastSeen: Long = 0
)
