package com.lokate.kmmsdk

actual fun getScanner(): BeaconScanner {
    return AndroidBeaconScanner()
}
