package com.beacon

import android.app.Application
import com.beacon.data.BeaconDatabase
import com.beacon.data.IdentityRepository

class BeaconApplication : Application() {

    lateinit var database: BeaconDatabase
        private set

    lateinit var identityRepository: IdentityRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = BeaconDatabase.build(this)
        identityRepository = IdentityRepository(database.identityDao())
    }
}
