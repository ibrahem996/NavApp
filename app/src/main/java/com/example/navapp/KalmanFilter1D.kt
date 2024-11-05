package com.example.navapp

class KalmanFilter1D(
    private var q: Float,  // Process noise covariance
    private var r: Float,  // Measurement noise covariance
    initialEstimate: Float,  // Initial estimate of the state
    initialErrorCovariance: Float  // Initial estimation error covariance
) {
    private var x: Float = initialEstimate  // Current state estimate
    private var p: Float = initialErrorCovariance  // Current estimation error covariance

    fun update(z: Float): Float {
        // Prediction step (assuming the state doesn't change between measurements)
        val xPrior = x
        val pPrior = p + q

        // Update step
        val k = pPrior / (pPrior + r)  // Kalman gain
        x = xPrior + k * (z - xPrior)  // Updated state estimate
        p = (1 - k) * pPrior  // Updated estimation error covariance

        return x
    }
}
