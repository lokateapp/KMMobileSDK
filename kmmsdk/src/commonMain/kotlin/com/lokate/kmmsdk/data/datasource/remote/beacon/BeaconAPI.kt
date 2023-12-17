package com.lokate.kmmsdk.data.datasource.remote.beacon

import com.lokate.kmmsdk.data.datasource.remote.ApiResponse
import com.lokate.kmmsdk.data.datasource.remote.base.BaseAPI
import com.lokate.kmmsdk.data.datasource.remote.util.extension.lokateRequest
import com.lokate.kmmsdk.domain.model.beacon.LokateBeacon
import io.ktor.http.HttpMethod
import io.ktor.http.path

class BeaconAPI: BaseAPI {

    companion object{
        const val SERVICE = "127.0.0.1:3000"
    }

    suspend fun fetchBeacons(appToken: String): ApiResponse<List<LokateBeacon>, Any> = client.lokateRequest {
        url{
            method = HttpMethod.Get
            host = SERVICE
            path("$appToken/beacon")
        }
    }
}