package com.beacon

import android.app.Application
import com.beacon.ble.BlePeerDiscovery
import com.beacon.ble.PeerDiscovery
import com.beacon.data.BeaconDatabase
import com.beacon.data.IdentityRepository
import com.beacon.data.PeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BeaconApplication : Application() {

    lateinit var database: BeaconDatabase
        private set

    lateinit var identityRepository: IdentityRepository
        private set

    lateinit var peerRepository: PeerRepository
        private set

    lateinit var peerDiscovery: PeerDiscovery
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        database = BeaconDatabase.build(this)
        identityRepository = IdentityRepository(database.identityDao())
        peerRepository = PeerRepository(database.peerDao())
        peerDiscovery = BlePeerDiscovery(this, peerRepository, applicationScope)
    }
}
