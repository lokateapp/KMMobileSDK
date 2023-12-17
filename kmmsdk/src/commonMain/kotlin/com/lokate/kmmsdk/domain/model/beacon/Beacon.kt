package com.lokate.kmmsdk.domain.model.beacon

data class LokateBeacon(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val campaign: String,
    val minProximity: BeaconProximity
)
