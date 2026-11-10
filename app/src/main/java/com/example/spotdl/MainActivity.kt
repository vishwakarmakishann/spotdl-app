package com.example.spotdl

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spotdl.model.CollectionMetadata
import com.example.spotdl.ui.screens.DownloadQueueScreen
import com.example.spotdl.ui.screens.HomeScreen
import com.example.spotdl.ui.screens.IncomingLinkScreen
import com.example.spotdl.ui.screens.PlaylistPreviewScreen
import com.example.spotdl.ui.screens.SettingsScreen
import com.example.spotdl.ui.theme.EditorialCanvas
import com.example.spotdl.ui.theme.EditorialKeyline
import com.example.spotdl.ui.theme.EditorialPrimary
import com.example.spotdl.ui.theme.EditorialSurface
import com.example.spotdl.ui.theme.SpotdlTheme
import com.example.spotdl.viewmodel.HomeViewModel
import com.example.spotdl.viewmodel.QueueViewModel
import com.example.spotdl.viewmodel.SettingsViewModel
import com.example.spotdl.viewmodel.ShareIntentViewModel

enum class MainTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    QUEUE("Queue", Icons.Default.DownloadForOffline),
    SETTINGS("Settings", Icons.Default.Tune)
}

class MainActivity : ComponentActivity() {

    private val shareViewModel: ShareIntentViewModel by viewModels()
    private val queueViewModel: QueueViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private var activeSharedText by mutableStateOf<String?>(null)
    private var previewCollection by mutableStateOf<CollectionMetadata?>(null)
    private var currentTab by mutableStateOf(MainTab.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            SpotdlTheme {
                when {
                    previewCollection != null -> {
                        PlaylistPreviewScreen(
                            collection = previewCollection!!,
                            onStartDownload = { selectedIndices ->
                                shareViewModel.startDownload(previewCollection!!, selectedIndices)
                                previewCollection = null
                                activeSharedText = null
                                currentTab = MainTab.QUEUE
                            },
                            onBack = {
                                previewCollection = null
                            }
                        )
                    }
                    !activeSharedText.isNullOrBlank() -> {
                        IncomingLinkScreen(
                            sharedText = activeSharedText!!,
                            viewModel = shareViewModel,
                            onNavigateToPreview = { collection ->
                                previewCollection = collection
                            },
                            onBack = {
                                activeSharedText = null
                                shareViewModel.resetState()
                            }
                        )
                    }
                    else -> {
                        MainScaffold(
                            currentTab = currentTab,
                            onTabSelected = { currentTab = it },
                            homeContent = {
                                HomeScreen(
                                    viewModel = homeViewModel,
                                    onInspectLink = { url ->
                                        activeSharedText = url
                                    },
                                    onNavigateToQueue = {
                                        currentTab = MainTab.QUEUE
                                    }
                                )
                            },
                            queueContent = {
                                DownloadQueueScreen(viewModel = queueViewModel)
                            },
                            settingsContent = {
                                SettingsScreen(viewModel = settingsViewModel)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                activeSharedText = sharedText
            }
        }
    }
}

@Composable
fun MainScaffold(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    homeContent: @Composable () -> Unit,
    queueContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    Scaffold(
        bottomBar = {
            EditorialBottomNavBar(
                currentTab = currentTab,
                onTabSelected = onTabSelected
            )
        },
        containerColor = EditorialCanvas
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "TabTransition"
            ) { tab ->
                when (tab) {
                    MainTab.HOME -> homeContent()
                    MainTab.QUEUE -> queueContent()
                    MainTab.SETTINGS -> settingsContent()
                }
            }
        }
    }
}

@Composable
fun EditorialBottomNavBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EditorialKeyline),
        color = EditorialSurface.copy(alpha = 0.95f),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEach { tab ->
                val isSelected = currentTab == tab
                Column(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 48.dp, height = 28.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) EditorialPrimary.copy(alpha = 0.12f) else EditorialCanvas.copy(alpha = 0f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            tint = if (isSelected) EditorialPrimary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tab.title,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) EditorialPrimary else MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
