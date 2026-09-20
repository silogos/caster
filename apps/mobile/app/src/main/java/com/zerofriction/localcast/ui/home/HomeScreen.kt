package com.zerofriction.localcast.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerofriction.localcast.R
import com.zerofriction.localcast.ui.theme.LocalCastTheme

@Composable
fun HomeScreen(
    onStartCast: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeContent(
        uiState = uiState,
        onStartCast = onStartCast,
    )
}

@Composable
fun HomeContent(
    uiState: HomeUiState,
    onStartCast: () -> Unit,
) {
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
            Text(
                text = "Status: ${uiState.status.label}",
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                // Phase3: opens the pairing scan (QR → WebSocket → handshake).
                onClick = onStartCast,
                modifier = Modifier.fillMaxWidth(0.6f),
            ) {
                Text(stringResource(R.string.start_cast))
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomeContentPreview() {
    LocalCastTheme {
        HomeContent(
            uiState = HomeUiState(),
            onStartCast = {},
        )
    }
}
