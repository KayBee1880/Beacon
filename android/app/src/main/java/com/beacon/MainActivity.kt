package com.beacon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beacon.data.Identity
import com.beacon.data.IdentityRepository
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val identityRepository = (application as BeaconApplication).identityRepository
        setContent {
            BeaconTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BeaconApp(identityRepository)
                }
            }
        }
    }
}

@Composable
private fun BeaconApp(identityRepository: IdentityRepository) {
    val identity by identityRepository.observe().collectAsState(initial = null)
    val currentIdentity = identity
    if (currentIdentity == null) {
        IdentitySetupScreen(identityRepository = identityRepository)
    } else {
        NearbyScreen(identity = currentIdentity)
    }
}

@Composable
private fun IdentitySetupScreen(identityRepository: IdentityRepository) {
    var displayName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_description))
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.setup_display_name_label)) },
            singleLine = true,
            enabled = !isCreating
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                isCreating = true
                scope.launch {
                    identityRepository.createIdentity(displayName.trim())
                }
            },
            enabled = displayName.isNotBlank() && !isCreating
        ) {
            Text(
                stringResource(
                    if (isCreating) R.string.setup_create_button_creating
                    else R.string.setup_create_button
                )
            )
        }
    }
}

@Composable
private fun NearbyScreen(identity: Identity) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.nearby_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.nearby_signed_in_as, identity.displayName))
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.nearby_empty))
        }
    }
}

@Composable
private fun BeaconTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
