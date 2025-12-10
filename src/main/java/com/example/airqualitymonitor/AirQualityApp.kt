package com.example.airqualitymonitor

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import org.osmdroid.config.Configuration

class AirQualityApp : Application() {
    override fun onCreate() {
        super.onCreate()

        FirebaseApp.initializeApp(this)
        Firebase.database.setPersistenceEnabled(true)

        Configuration.getInstance().apply {
            load(applicationContext, applicationContext.getSharedPreferences(
                "${packageName}_osmdroid",
                MODE_PRIVATE
            ))
            userAgentValue = packageName
        }
    }
}