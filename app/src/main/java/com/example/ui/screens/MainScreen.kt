package com.example.ui.screens

import android.text.format.Formatter
import android.widget.Space
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.model.Song
import com.example.ui.player.PlayerType
import com.example.ui.viewmodel.MusicViewModel
import com.example.ui.viewmodel.SearchState
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.launch

// Visual Theme Palettes (Luxury Cosmic Dark)
val DeepSpaceDb = Color(0xFF090A0E)
val NebulaGrey = Color(0xFF141722)
val AstroGlow = Color(0xFF00D2D2)       // Futuristic Electric Cyan
val SupernovaPink = Color(0xFFFF2D55)     // Highlight color
val StardustText = Color(0xFFE2E8F0)
val CometSubText = Color(0xFF94A3B8)

enum class ScreenTab {
    HOME, SEARCH, LIBRARY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MusicViewModel) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(ScreenTab.HOME) }
    
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val activeType by viewModel.activePlayerType.collectAsStateWithLifecycle()

    var isPlayerExpanded by remember { mutableStateOf(false) }

    // Floating UI layout framed elegantly in dark mode
    Scaffold(
        bottomBar = {
            Column {
                // Mini Player Bar sitting directly above standard bottom navigation bar when a track is chosen
                if (currentSong != null && !isPlayerExpanded) {
                    MiniPlayerBar(
                        song = currentSong!!,
                        isPlaying = isPlaying,
                        activeType = activeType,
                        onTogglePlay = { viewModel.playerManager.togglePlayPause() },
                        onNext = { viewModel.playerManager.skipNext() },
                        onClick = { isPlayerExpanded = true }
                    )
                }
                
                // Unified standard Bottom Navigation Bar
                HorizontalDivider(color = Color(0xFF1E293B), thickness = 0.5.dp)
                NavigationBar(
                    containerColor = DeepSpaceDb,
                    contentColor = AstroGlow
                ) {
                    val items = listOf(
                        Triple(ScreenTab.HOME, "Home", Icons.Default.Home),
                        Triple(ScreenTab.SEARCH, "Search", Icons.Default.Search),
                        Triple(ScreenTab.LIBRARY, "Library", Icons.Default.LibraryMusic)
                    )
                    items.forEach { (tab, label, icon) ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Icon(icon, contentDescription = label, tint = if (currentTab == tab) AstroGlow else CometSubText) },
                            label = { Text(label, color = if (currentTab == tab) AstroGlow else CometSubText) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = NebulaGrey
                            )
                        )
                    }
                }
            }
        },
        containerColor = DeepSpaceDb
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Invisible attached YouTubePlayer for streaming
            BackgroundYoutubePlayer(viewModel = viewModel)

            // Dynamic view selector based on Navigation states
            when (currentTab) {
                ScreenTab.HOME -> HomeScreen(viewModel = viewModel)
                ScreenTab.SEARCH -> SearchScreen(viewModel = viewModel)
                ScreenTab.LIBRARY -> LibraryScreen(viewModel = viewModel)
            }

            // Expanding Full-Screen Player overlay Sheet
            AnimatedVisibility(
                visible = isPlayerExpanded && currentSong != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                if (currentSong != null) {
                    FullPlayerScreen(
                        song = currentSong!!,
                        viewModel = viewModel,
                        onCollapse = { isPlayerExpanded = false }
                    )
                }
            }
        }
    }
}

@Composable
fun BackgroundYoutubePlayer(viewModel: MusicViewModel) {
    var lastDuration = 0f
    AndroidView(
        modifier = Modifier.size(1.dp), // size at 1.dp to attach fully but stay completely hidden!
        factory = { ctx ->
            YouTubePlayerView(ctx).apply {
                enableAutomaticInitialization = false
                initialize(object : AbstractYouTubePlayerListener() {
                    override fun onReady(youTubePlayer: YouTubePlayer) {
                        viewModel.playerManager.registerYoutubePlayer(youTubePlayer)
                    }

                    override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                        val isPlaying = state == PlayerConstants.PlayerState.PLAYING
                        viewModel.playerManager.onYoutubeStateChanged(isPlaying)
                    }

                    override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                        viewModel.playerManager.onYoutubeProgress(second, lastDuration)
                    }

                    override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                        lastDuration = duration
                    }
                })
            }
        },
        onRelease = {
            viewModel.playerManager.unregisterYoutubePlayer()
        }
    )
}

@Composable
fun HomeScreen(viewModel: MusicViewModel) {
    val curatedTracks by viewModel.curatedTracks.collectAsStateWithLifecycle()
    val downloadedSongs by viewModel.downloadedSongs.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
    ) {
        item {
            // Creative display header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = AstroGlow,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "BeatStream",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = StardustText,
                            letterSpacing = 1.sp
                        )
                    )
                    Text(
                        text = "Your Ad-Free Streaming Hub",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = CometSubText
                        )
                    )
                }
            }
        }

        // Quick Curated Section (Dynamic list with beautiful custom card horizontal scrolling)
        item {
            SectionHeader(title = "Featured Hits", icon = Icons.Outlined.Whatshot)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                items(curatedTracks) { song ->
                    FeaturedTrackCard(
                        song = song,
                        onClick = { viewModel.playSongWithQueue(song, curatedTracks) }
                    )
                }
            }
        }

        // Display Moods/Categories
        item {
            SectionHeader(title = "Pick Your Vibe", icon = Icons.Outlined.Mood)
            GenreList(onSelectGenre = { genre ->
                viewModel.onSearchQueryChanged(genre)
                viewModel.executeSearch()
                // Emulate search query change for instant navigation
            })
        }

        // Quick display of downloaded files if present
        if (downloadedSongs.isNotEmpty()) {
            item {
                SectionHeader(title = "Downloaded Tracks", icon = Icons.Outlined.CheckCircle)
            }
            items(downloadedSongs.take(4)) { song ->
                SongRowItem(
                    song = song,
                    viewModel = viewModel,
                    onClick = { viewModel.playSongWithQueue(song, downloadedSongs) }
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AstroGlow, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = StardustText
            )
        )
    }
}

@Composable
fun FeaturedTrackCard(song: Song, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(150.dp)
            .clickable(onClick = onClick)
            .border(BorderStroke(1.dp, Color(0xFF1E293B)), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = NebulaGrey),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                )
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(song.durationText, color = Color.White, fontSize = 10.sp)
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    color = StardustText,
                    fontSize = 13.sp
                )
                Text(
                    text = song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = CometSubText,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun GenreList(onSelectGenre: (String) -> Unit) {
    val genres = listOf("Lo-fi Study", "Top Pop Hits", "Acoustic Chill", "Synthwave Night", "Gaming Gym")
    val colors = listOf(Color(0xFF6366F1), Color(0xFFEC4899), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEF4444))

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        items(genres.size) { idx ->
            val genre = genres[idx]
            val color = colors[idx % colors.size]
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.horizontalGradient(listOf(color.copy(alpha = 0.5f), color))
                    )
                    .clickable { onSelectGenre(genre) }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(genre, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: MusicViewModel) {
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        // Search bar custom styled matching Neon cosmic canvas
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.onSearchQueryChanged(it) },
            placeholder = { Text("Search any song, artist, album...", color = CometSubText) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AstroGlow) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = SupernovaPink)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = { viewModel.executeSearch() }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = StardustText,
                unfocusedTextColor = StardustText,
                focusedBorderColor = AstroGlow,
                unfocusedBorderColor = Color(0xFF1E293B),
                focusedContainerColor = NebulaGrey,
                unfocusedContainerColor = NebulaGrey
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_input")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // State machine UI handler
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (searchState) {
                SearchState.IDLE -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = CometSubText,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Discover Music Unbound",
                            color = StardustText,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Type to search official YouTube Music database.",
                            color = CometSubText,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                SearchState.LOADING -> {
                    CircularProgressIndicator(
                        color = AstroGlow,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                SearchState.ERROR -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.SignalWifiOff,
                            contentDescription = null,
                            tint = SupernovaPink,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No Results Found",
                            color = SupernovaPink,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Double check spelling or internet connection.",
                            color = CometSubText,
                            fontSize = 12.sp
                        )
                    }
                }
                SearchState.SUCCESS -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(searchResults) { song ->
                            SongRowItem(
                                song = song,
                                viewModel = viewModel,
                                onClick = { viewModel.playSongWithQueue(song, searchResults) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(viewModel: MusicViewModel) {
    val downloadedSongs by viewModel.downloadedSongs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Local Music Library",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                color = StardustText
            )
        )
        Text(
            "Offline playback available for downloaded songs",
            style = MaterialTheme.typography.bodySmall.copy(
                color = CometSubText
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (downloadedSongs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    tint = CometSubText,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Your Library is Empty",
                    color = StardustText,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Search songs and tap download to enjoy them offline.",
                    color = CometSubText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(downloadedSongs) { song ->
                    SongRowItem(
                        song = song,
                        viewModel = viewModel,
                        onClick = { viewModel.playSongWithQueue(song, downloadedSongs) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRowItem(song: Song, viewModel: MusicViewModel, onClick: () -> Unit) {
    val progressMap by viewModel.downloadProgressMap.collectAsStateWithLifecycle()
    val isDownloading = progressMap.containsKey(song.videoId)
    val progress = progressMap[song.videoId] ?: 0

    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val isActiveTrack = currentSong?.videoId == song.videoId

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (song.isLocal) {
                        viewModel.deleteDownloadedSong(song)
                    }
                }
            )
            .border(
                BorderStroke(
                    0.5.dp,
                    if (isActiveTrack) AstroGlow.copy(alpha = 0.5f) else Color(0xFF1E293B)
                ),
                RoundedCornerShape(10.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isActiveTrack) NebulaGrey.copy(alpha = 0.8f) else NebulaGrey
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                if (isActiveTrack) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "Active",
                            tint = AstroGlow,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.Center)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    fontWeight = FontWeight.Bold,
                    color = if (isActiveTrack) AstroGlow else StardustText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song.isLocal) {
                        Icon(
                            Icons.Default.OfflinePin,
                            contentDescription = "Offline Available",
                            tint = AstroGlow,
                            modifier = Modifier
                                .size(12.dp)
                                .padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = song.artist,
                        color = CometSubText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            
            // Dynamic actions logic (Downloading circle / Download button / Duration indicator)
            when {
                isDownloading -> {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progress / 100f },
                            color = AstroGlow,
                            strokeWidth = 2.dp,
                            trackColor = Color.DarkGray
                        )
                        Text(
                            "$progress%", 
                            color = StardustText, 
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                song.isLocal -> {
                    IconButton(onClick = { viewModel.deleteDownloadedSong(song) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Local Video", tint = SupernovaPink.copy(alpha = 0.8f))
                    }
                }
                else -> {
                    IconButton(onClick = { viewModel.startSongDownload(song) }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "Download Song", tint = AstroGlow)
                    }
                }
            }
            Text(
                song.durationText,
                color = CometSubText,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
fun MiniPlayerBar(
    song: Song,
    isPlaying: Boolean,
    activeType: PlayerType,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(BorderStroke(0.5.dp, Color(0xFF1E293B)), RoundedCornerShape(0.dp)),
        colors = CardDefaults.cardColors(containerColor = NebulaGrey),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    fontWeight = FontWeight.Bold,
                    color = StardustText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = if (activeType == PlayerType.OFFLINE) "Offline File" else "YouTube Ad-Free"
                    val icon = if (activeType == PlayerType.OFFLINE) Icons.Default.OfflinePin else Icons.Default.CloudQueue
                    Icon(icon, contentDescription = null, tint = AstroGlow, modifier = Modifier.size(10.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "$label • ${song.artist}",
                        color = CometSubText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = AstroGlow
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next Track", tint = StardustText)
            }
        }
    }
}

@Composable
fun FullPlayerScreen(
    song: Song,
    viewModel: MusicViewModel,
    onCollapse: () -> Unit
) {
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val positionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val activeType by viewModel.activePlayerType.collectAsStateWithLifecycle()
    val progressMap by viewModel.downloadProgressMap.collectAsStateWithLifecycle()
    val isDownloading = progressMap.containsKey(song.videoId)

    val formatProgress = progressMap[song.videoId] ?: 0

    // Animating disk rotation when song is playing which looks unbelievably gorgeous!
    val infiniteTransition = rememberInfiniteTransition(label = "DiskRotate")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "DiskRotateAngle"
    )
    val animatedAngle = if (isPlaying) angle else 0f

    // Cosmic background mesh using gorgeous dark radial brush
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        NebulaGrey,
                        DeepSpaceDb
                    )
                )
            )
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Player header top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize Screen", tint = StardustText, modifier = Modifier.size(32.dp))
            }
            Text(
                text = if (activeType == PlayerType.OFFLINE) "Playing Offline MP3" else "Streaming Ad-Free",
                style = MaterialTheme.typography.titleSmall.copy(color = CometSubText, fontWeight = FontWeight.Bold)
            )
            IconButton(onClick = { /* Settings action */ }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = StardustText)
            }
        }

        Spacer(modifier = Modifier.weight(0.3f))

        // Large Rotating Cover Art Ring (Luxury Design Accent)
        Box(
            modifier = Modifier
                .size(260.dp)
                .clip(CircleShape)
                .background(Color(0xFF14151B))
                .border(BorderStroke(4.dp, Brush.radialGradient(listOf(AstroGlow, Color.Transparent))), CircleShape)
                .rotate(animatedAngle),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(210.dp)
                    .clip(CircleShape)
            )
            // Center record center spindle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(DeepSpaceDb)
                    .border(BorderStroke(2.dp, Color.Black), CircleShape)
            )
        }

        Spacer(modifier = Modifier.weight(0.3f))

        // Song details labels
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = song.title,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                color = StardustText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = song.artist,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = AstroGlow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.weight(0.2f))

        // Progress Slider component
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Slider(
                value = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
                onValueChange = { scaled ->
                    val seekTarget = (scaled * durationMs).toLong()
                    viewModel.playerManager.seekTo(seekTarget)
                },
                colors = SliderDefaults.colors(
                    thumbColor = AstroGlow,
                    activeTrackColor = AstroGlow,
                    inactiveTrackColor = Color(0xFF1E293B)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatDuration(positionMs), color = CometSubText, fontSize = 11.sp)
                Text(formatDuration(durationMs), color = CometSubText, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.weight(0.2f))

        // Main Player Controls Bar (Play/Pause, Previous, Next, Download shortcut, Repeat/Delete)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Action 1: Delete or Download
            if (song.isLocal) {
                IconButton(onClick = { 
                    viewModel.deleteDownloadedSong(song)
                    onCollapse()
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Local file", tint = SupernovaPink)
                }
            } else if (isDownloading) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { formatProgress / 100f },
                        color = AstroGlow,
                        strokeWidth = 2.dp
                    )
                    Text("$formatProgress%", color = StardustText, fontSize = 8.sp)
                }
            } else {
                IconButton(onClick = { viewModel.startSongDownload(song) }) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "Download Offline", tint = AstroGlow, modifier = Modifier.size(28.dp))
                }
            }

            // Action 2: Skip Backwards
            IconButton(onClick = { viewModel.playerManager.skipPrevious() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Track", tint = StardustText, modifier = Modifier.size(36.dp))
            }

            // Action 3: Giant Play Pause Button (Neon Accent)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(AstroGlow, Color(0xFF00B2FE))))
                    .clickable { viewModel.playerManager.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play Action",
                    tint = DeepSpaceDb,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Action 4: Skip Forwards
            IconButton(onClick = { viewModel.playerManager.skipNext() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next Track", tint = StardustText, modifier = Modifier.size(36.dp))
            }

            // Action 5: Playlist icon indicator
            IconButton(onClick = { /* Share link */ }) {
                Icon(Icons.Outlined.Share, contentDescription = "Share tracker", tint = CometSubText)
            }
        }

        Spacer(modifier = Modifier.weight(0.5f))
    }
}

// Format milliseconds: "3:45"
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%d:%02d", min, sec)
}
