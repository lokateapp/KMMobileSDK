package com.lokate.kmmsdk.data.datasource.local.beacon

import com.lokate.kmmsdk.Database
import com.lokate.kmmsdk.data.datasource.DSResult
import com.lokate.kmmsdk.domain.model.beacon.LokateBeacon

class BeaconLocalDS(
    private val database: Database,
) {
    private val queries = database.beaconDatabaseQueries

    suspend fun addBeacon(beacons: List<LokateBeacon>) {
        beacons.forEach {
            queries.insertBeacon(it.uuid, it.major?.toLong() ?: 0, it.minor?.toLong() ?: 0)
        }
    }

    suspend fun getBeacons(): DSResult {
        val a = queries.selectAllBeacons()
        return DSResult.Success(
            a.executeAsList().map {
                LokateBeacon(
                    it.uuid,
                    it.major.toInt(),
                    it.minor.toInt(),
                    "null",
                    // BeaconProximity.Unknown
                )
            },
        )
    }

    suspend fun removeBeacons() {
        queries.removeAllBeacons()
    }
}
