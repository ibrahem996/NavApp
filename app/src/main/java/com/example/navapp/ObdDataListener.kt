// ObdDataListener.kt
package com.example.navapp

interface ObdDataListener {
    fun onSpeedUpdated(speed: Float)
    fun onObdError(errorMessage: String)
}
