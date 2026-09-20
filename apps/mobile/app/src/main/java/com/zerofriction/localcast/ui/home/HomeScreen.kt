package com.zerofriction.localcast.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerofriction.localcast.R
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.ui.theme.LocalCastTheme

@Composable
fun HomeScreen(
    onStartCast: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val castState by viewModel.castState.collectAsStateWithLifecycle()

    HomeContent(
        castState = castState,
        onStartCast = onStartCast,
    )
}

/**
 * The home screen is session-aware (Phase6): the cast service owns the
 * session, so this screen is the cast's status line — a running cast shows
 * who it's going to and how to stop it, without going through the scan screen.
 */
@Composable
fun HomeContent(
    castState: CastState,
    onStartCast: () -> Unit,
) {
    val context = LocalContext.current

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            when (val state = castState) {
                CastState.Idle -> {
                    Text(text = stringResource(R.string.home_not_connected), fontSize = 14.sp)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        // Phase3: opens the pairing scan (QR → WebSocket → handshake).
                        onClick = onStartCast,
                        modifier = Modifier.fillMaxWidth(0.6f),
                    ) {
                        Text(stringResource(R.string.start_cast))
                    }
                }

                is CastState.Starting -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(text = stringResource(R.string.starting_cast), fontSize = 14.sp)
                }

                is CastState.Casting -> {
                    Text(
                        text = stringResource(R.string.casting_to, state.desktopName),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { CastService.requestStop(context) },
                        modifier = Modifier.fillMaxWidth(0.6f),
                    ) {
                        Text(stringResource(R.string.stop_casting))
                    }
                }

                is CastState.Failed -> {
                    Text(
                        text = state.message,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onStartCast,
                        modifier = Modifier.fillMaxWidth(0.6f),
                    ) {
                        Text(stringResource(R.string.start_cast))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomeContentPreview() {
    LocalCastTheme {
        HomeContent(
            castState = CastState.Casting("MacBook Pro"),
            onStartCast = {},
        )
    }
}
