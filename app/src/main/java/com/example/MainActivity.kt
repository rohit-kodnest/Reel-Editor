package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.api.GenerateContentRequest
import com.example.api.GenerationConfig
import com.example.api.ReelAnalysisResult
import com.example.api.RetrofitClient
import com.example.api.Content as ApiContent
import com.example.api.Part as ApiPart
import com.example.data.*
import com.example.ui.theme.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var appDatabase: AppDatabase
    private lateinit var repository: ReelRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Room DB
        appDatabase = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "reel_genius_studio_db"
        ).fallbackToDestructiveMigration().build()

        repository = ReelRepository(
            appDatabase.videoProjectDao(),
            appDatabase.generatedReelDao()
        )

        // Seed default video projects if empty
        lifecycleScope.launch {
            try {
                val existing = appDatabase.videoProjectDao().getAllProjectsFlow().first()
                if (existing.isEmpty()) {
                    val defaultProjects = listOf(
                        VideoProject(title = "Tech Setup Showcase", durationSec = 60),
                        VideoProject(title = "Sunset Coast Drone", durationSec = 90),
                        VideoProject(title = "Beast Mode Gym Session", durationSec = 75),
                        VideoProject(title = "Sizzling Steak ASMR", durationSec = 45)
                    )
                    defaultProjects.forEach {
                        appDatabase.videoProjectDao().insertProject(it)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    val viewModel: ReelStudioViewModel = ViewModelProvider(
                        this,
                        ReelStudioViewModelFactory(repository)
                    )[ReelStudioViewModel::class.java]

                    ReelGeniusStudioApp(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

// Custom ViewModel to manage the active studio operations
class ReelStudioViewModel(private val repository: ReelRepository) : ViewModel() {
    val projects = repository.allProjects.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val reels = repository.allReels.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Current editing configurations
    var selectedProject = mutableStateOf<VideoProject?>(null)
    var reelTitle = mutableStateOf("My Fire Reel")
    var startTime = mutableStateOf(0f)
    var endTime = mutableStateOf(15f)
    var selectedFilter = mutableStateOf("Cinematic")
    var selectedAudio = mutableStateOf("Cyber Beats")
    var selectedAspect = mutableStateOf("9:16") // Options: "9:16", "1:1", "16:9"
    var textOverlay = mutableStateOf("Viral Hook Here 🚀")

    // AI Analysis status
    var isAnalyzing = mutableStateOf(false)
    var currentLog = mutableStateOf("AI Engine Standby")
    var analysisResult = mutableStateOf<ReelAnalysisResult?>(null)
    var isSaving = mutableStateOf(false)

    // Player controls
    var isPlaying = mutableStateOf(false)
    var playPosition = mutableStateOf(0f)

    // Active project state transition
    fun updateProjectSelection(project: VideoProject) {
        selectedProject.value = project
        reelTitle.value = project.title + " Viral Reel"
        startTime.value = 0f
        endTime.value = if (project.durationSec > 15) 15f else project.durationSec.toFloat()
        analysisResult.value = null
        isPlaying.value = false
        playPosition.value = 0f
    }

    // Live Simulated Video Timer loop
    init {
        viewModelScope.launch {
            while (true) {
                delay(100)
                if (isPlaying.value && selectedProject.value != null) {
                    val maxS = endTime.value
                    val minS = startTime.value
                    var current = playPosition.value + 0.1f
                    if (current > maxS || current < minS) {
                        current = minS
                    }
                    playPosition.value = current
                }
            }
        }
    }

    // Direct Gemini REST API integration using Moshi for parsing
    fun runGeminiAnalysis(apiKey: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val proj = selectedProject.value ?: return
        isAnalyzing.value = true
        currentLog.value = "Establishing link with gemini-3.5-flash..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateLog("Parsing video description metadata...")
                val videoDescription = getDescriptionForProject(proj)
                
                updateLog("Formulating high-retention engagement request...")
                val systemPrompt = """
                    You are ReelGenius, a viral social media engineer. Your job is to extract the absolute best high-retention highlight reel from a video.
                    Identify the perfect start and end seconds (duration: 10 to 25 seconds long).
                    Provide viral hooks, high-impact subtitle overlay text, and a complete ready-to-paste caption with emojis and tags.
                    
                    You MUST return your response as a raw JSON string conforming EXACTLY to this schema. DO NOT include any backticks (```) or surrounding text. Clean JSON only:
                    {
                      "recommendedStartSec": 2.5,
                      "recommendedEndSec": 17.5,
                      "caption": "Your video title hook here 🔥 See description...",
                      "hashtags": "#reel #growth #setup #viral",
                      "scriptHook": "The #1 secret they didn't want you to know!",
                      "viralScore": 94,
                      "retentionTip": "The viewer is locked in by a high-tempo physical trigger in the first 2 seconds."
                    }
                """.trimIndent()

                val prompt = """
                    Analyze this video workspace project:
                    Title: ${proj.title}
                    Total Duration: ${proj.durationSec} seconds
                    Visual Context: $videoDescription
                    
                    Return the best high-retention highlights and caption in raw JSON format. Start time MUST be between 0 and ${proj.durationSec - 10}. End time limit: ${proj.durationSec}.
                """.trimIndent()

                updateLog("Transmitting data to Gemini AI model...")
                val request = GenerateContentRequest(
                    contents = listOf(
                        com.example.api.Content(
                            parts = listOf(com.example.api.Part(text = prompt))
                        )
                    ),
                    generationConfig = GenerationConfig(
                        responseMimeType = "application/json",
                        temperature = 0.8f
                    ),
                    systemInstruction = com.example.api.Content(
                        parts = listOf(com.example.api.Part(text = systemPrompt))
                    )
                )

                val response = RetrofitClient.service.generateContent(apiKey, request)
                val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: throw Exception("Empty response received from server.")

                updateLog("Optimizing engagement metrics...")
                val cleanedJson = cleanJsonResponse(rawText)

                // Initialize Moshi to parse the JSON securely
                val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                val adapter = moshi.adapter(ReelAnalysisResult::class.java)
                val parsedResult = adapter.fromJson(cleanedJson)
                    ?: throw Exception("Could not map layout parameters.")

                withContext(Dispatchers.Main) {
                    analysisResult.value = parsedResult
                    // Autofill editing timelines based on AI suggestions
                    startTime.value = parsedResult.recommendedStartSec.coerceIn(0f, proj.durationSec.toFloat())
                    endTime.value = parsedResult.recommendedEndSec.coerceIn(startTime.value + 3f, proj.durationSec.toFloat())
                    textOverlay.value = parsedResult.scriptHook
                    isAnalyzing.value = false
                    onSuccess()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isAnalyzing.value = false
                    onError(e.localizedMessage ?: "Connection error.")
                }
            }
        }
    }

    private suspend fun updateLog(msg: String) {
        withContext(Dispatchers.Main) {
            currentLog.value = msg
        }
        delay(600)
    }

    fun saveReelToDatabase(onSuccess: () -> Unit) {
        val proj = selectedProject.value ?: return
        val result = analysisResult.value
        isSaving.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val reel = GeneratedReel(
                    projectId = proj.id,
                    title = reelTitle.value,
                    startTimeSec = startTime.value,
                    endTimeSec = endTime.value,
                    filterName = selectedFilter.value,
                    audioTrackName = selectedAudio.value,
                    caption = result?.caption ?: "Optimized with ReelGenius",
                    hashtags = result?.hashtags ?: "#viral #reel",
                    scriptHook = textOverlay.value,
                    viralScore = result?.viralScore ?: 88,
                    videoAspect = selectedAspect.value
                )
                repository.insertReel(reel)

                withContext(Dispatchers.Main) {
                    isSaving.value = false
                    onSuccess()
                }
            } catch (e: java.lang.Exception) {
                withContext(Dispatchers.Main) {
                    isSaving.value = false
                }
            }
        }
    }

    fun deleteReelFromGallery(reel: GeneratedReel) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteReel(reel)
        }
    }

    fun addCustomProject(title: String, duration: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val newProj = VideoProject(
                title = title,
                durationSec = duration
            )
            val generatedId = repository.insertProject(newProj)
            val addedProj = newProj.copy(id = generatedId)
            withContext(Dispatchers.Main) {
                updateProjectSelection(addedProj)
            }
        }
    }
}

class ReelStudioViewModelFactory(private val repository: ReelRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReelStudioViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReelStudioViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// Descriptive metadata parsed by Gemini so it generates custom topic scripts
fun getDescriptionForProject(project: VideoProject): String {
    return when (project.title) {
        "Tech Setup Showcase" -> "Desk tour showing keyboard typing with RGB glow, beautiful multi-monitor IDE layouts, clean mechanical typewriter sounds, morning coffee pour, sleek setup visuals."
        "Sunset Coast Drone" -> "Stunning coastline, blue ocean waves crashing on dark rocks, drone slow panorama sweep, hiking runner active, rich golden orange misty background."
        "Beast Mode Gym Session" -> "High intensity workout motivation. Heavy barbell training, sweat pouring, active feedback cable cross, sports pre-shake mix, epic neon aesthetics."
        "Sizzling Steak ASMR" -> "Cooking food closeups. Heavy ribe eye cut onto sizzling iron pan, melting garlic butter basting, herb salt crystals, chef cutting tender medium-rare slices."
        else -> "Custom footage: ${project.title}. High contrast scenery, energetic camera flow, detailed product framing."
    }
}

// Clean model Markdown code blocks safely
fun cleanJsonResponse(raw: String): String {
    var cleaned = raw.trim()
    if (cleaned.startsWith("```json")) {
        cleaned = cleaned.substringAfter("```json").substringBeforeLast("```").trim()
    } else if (cleaned.startsWith("```")) {
        cleaned = cleaned.substringAfter("```").substringBeforeLast("```").trim()
    }
    return cleaned
}

@Composable
fun ReelGeniusStudioApp(
    viewModel: ReelStudioViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val savedReels by viewModel.reels.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showPlayerDialog by remember { mutableStateOf(false) }

    // Read the injected API Key securely
    val apiKey = BuildConfig.GEMINI_API_KEY

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBackground)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Elegant Premium Header
            StudioHeader(
                apiKeyProvided = apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY",
                onImportClick = { showAddDialog = true }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Adaptive layout: Sidebar + main workspace or clean scroll list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Segment 1: Video Workspace picker
                    item {
                        SectionTitle(
                            title = "1. Active Footage Selection",
                            icon = Icons.Default.VideoLibrary,
                            actionText = "Import Custom",
                            onActionClick = { showAddDialog = true }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(projects) { project ->
                                val isSelected = viewModel.selectedProject.value?.id == project.id
                                VideoWorkspaceCard(
                                    project = project,
                                    isSelected = isSelected,
                                    onClick = {
                                        viewModel.updateProjectSelection(project)
                                    }
                                )
                            }
                        }
                    }

                    // Segment 2: Render Workspace of selected project
                    val currentProject = viewModel.selectedProject.value
                    if (currentProject != null) {
                        item {
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = StudioCard),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("editor_studio_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = "Reel Editing Studio",
                                                color = StudioSecondary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.5.sp
                                            )
                                            Text(
                                                text = currentProject.title,
                                                color = TextWhite,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        }

                                        // Player trigger button
                                        Button(
                                            onClick = {
                                                viewModel.isPlaying.value = true
                                                showPlayerDialog = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = StudioSecondary),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Preview Reel",
                                                tint = StudioBackground
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Preview Draft",
                                                color = StudioBackground,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(18.dp))

                                    // Dynamic Video Scrubber representation
                                    Text(
                                        text = "High-Quality Trim Range: ${String.format("%.1f", viewModel.startTime.value)}s to ${String.format("%.1f", viewModel.endTime.value)}s",
                                        color = TextWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Duration: ${String.format("%.1f", viewModel.endTime.value - viewModel.startTime.value)} seconds",
                                        color = TextGray,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )

                                    // Custom visual slider with interactive gradient background track
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(StudioCardPressed)
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        // Visual audio spike simulation representation
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            repeat(16) { index ->
                                                val h = (15..40).random()
                                                Box(
                                                    modifier = Modifier
                                                        .width(4.dp)
                                                        .height(h.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (index / 16f >= (viewModel.startTime.value / currentProject.durationSec) &&
                                                                index / 16f <= (viewModel.endTime.value / currentProject.durationSec)
                                                            ) StudioPrimary else TextMuted
                                                        )
                                                )
                                            }
                                        }

                                        RangeSlider(
                                            value = viewModel.startTime.value..viewModel.endTime.value,
                                            onValueChange = { range ->
                                                val startVal = range.start
                                                val endVal = range.endInclusive
                                                if (endVal - startVal >= 3f) {
                                                    viewModel.startTime.value = startVal
                                                    viewModel.endTime.value = endVal
                                                }
                                            },
                                            valueRange = 0f..currentProject.durationSec.toFloat(),
                                            colors = SliderDefaults.colors(
                                                thumbColor = StudioPrimary,
                                                activeTrackColor = StudioPrimary.copy(alpha = 0.5f),
                                                inactiveTrackColor = Color.Transparent
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(18.dp))

                                    // Interactive editing choices
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            StudioSelector(
                                                title = "Filter",
                                                selected = viewModel.selectedFilter.value,
                                                options = listOf("Cinematic", "Cyber Neon", "Dark Noir", "Vibrant Glow"),
                                                onSelected = { viewModel.selectedFilter.value = it }
                                            )
                                        }

                                        Box(modifier = Modifier.weight(1f)) {
                                            StudioSelector(
                                                title = "Soundtrack",
                                                selected = viewModel.selectedAudio.value,
                                                options = listOf("Cyber Beats", "Lofi Sunset", "Future Bass", "Acoustic Chill"),
                                                onSelected = { viewModel.selectedAudio.value = it }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            StudioSelector(
                                                title = "Aspect Ratio",
                                                selected = viewModel.selectedAspect.value,
                                                options = listOf("9:16", "1:1", "16:9"),
                                                onSelected = { viewModel.selectedAspect.value = it }
                                            )
                                        }

                                        Box(modifier = Modifier.weight(1f)) {
                                            Column {
                                                Text(
                                                    text = "Text Overlay",
                                                    color = TextGray,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(bottom = 4.dp)
                                                )
                                                OutlinedTextField(
                                                    value = viewModel.textOverlay.value,
                                                    onValueChange = { viewModel.textOverlay.value = it },
                                                    textStyle = LocalTextStyle.current.copy(
                                                        color = TextWhite,
                                                        fontSize = 13.sp
                                                    ),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = StudioPrimary,
                                                        unfocusedBorderColor = StudioCardPressed,
                                                        focusedContainerColor = StudioCardPressed,
                                                        unfocusedContainerColor = StudioCardPressed
                                                    ),
                                                    shape = RoundedCornerShape(12.dp),
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(24.dp))

                                    // Run Gemini AI button
                                    Button(
                                        onClick = {
                                            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                                                Toast.makeText(
                                                    context,
                                                    "API Key is missing. Please enter your GEMINI_API_KEY in the Secrets Panel.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                viewModel.runGeminiAnalysis(
                                                    apiKey = apiKey,
                                                    onSuccess = {
                                                        Toast.makeText(context, "AI Analysis Completed! Timestamps updated.", Toast.LENGTH_SHORT).show()
                                                    },
                                                    onError = { err ->
                                                        Toast.makeText(context, "Gemini Error: $err", Toast.LENGTH_LONG).show()
                                                    }
                                                )
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("gemini_run_button"),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.linearGradient(
                                                        colors = listOf(
                                                            StudioPrimary,
                                                            StudioAccent
                                                        )
                                                    )
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (viewModel.isAnalyzing.value) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    CircularProgressIndicator(
                                                        color = TextWhite,
                                                        modifier = Modifier.size(20.dp),
                                                        strokeWidth = 2.5.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Text(
                                                        text = viewModel.currentLog.value,
                                                        color = TextWhite,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                            } else {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.AutoAwesome,
                                                        contentDescription = null,
                                                        tint = TextWhite
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "Auto-Craft Best Reel with Gemini AI",
                                                        color = TextWhite,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // AI suggestions Results
                        val aiResult = viewModel.analysisResult.value
                        if (aiResult != null && !viewModel.isAnalyzing.value) {
                            item {
                                AIResponsePanel(
                                    result = aiResult,
                                    onSaveClick = {
                                        viewModel.saveReelToDatabase {
                                            Toast.makeText(context, "Reel exported & saved to Gallery!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    isSaving = viewModel.isSaving.value
                                )
                            }
                        }
                    } else {
                        // Empty choice instruction
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ElectricBolt,
                                        contentDescription = null,
                                        tint = StudioPrimary.copy(alpha = 0.5f),
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Welcome to ReelGenius Studio",
                                        color = TextWhite,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 18.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Select one of our 4 pre-loaded high-quality workspace videos above or import your custom clip to begin.",
                                        color = TextGray,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.widthIn(max = 300.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Section 3: Captured Reels Gallery
                    item {
                        SectionTitle(
                            title = "Reel Masters Gallery",
                            icon = Icons.Default.PhotoLibrary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (savedReels.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(StudioCardPressed)
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Analytics,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Gallery is empty",
                                        color = TextGray,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Your exported viral clips will appear here.",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    } else {
                        items(savedReels) { reel ->
                            SavedReelGalleryCard(
                                reel = reel,
                                onDelete = { viewModel.deleteReelFromGallery(reel) }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(60.dp))
                    }
                }
            }
        }

        // Add Custom Video dialog
        if (showAddDialog) {
            AddVideoDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { title, duration ->
                    viewModel.addCustomProject(title, duration)
                    showAddDialog = false
                    Toast.makeText(context, "$title added to workspace!", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Beautiful Interactive Mock Reels Player Dialog
        if (showPlayerDialog && viewModel.selectedProject.value != null) {
            ReelsPlayerDialog(
                viewModel = viewModel,
                onDismiss = {
                    viewModel.isPlaying.value = false
                    showPlayerDialog = false
                }
            )
        }
    }
}

// Custom drop down styled selector to fit the premium dark theme
@Composable
fun StudioSelector(
    title: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = title,
            color = TextGray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(StudioCardPressed)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected,
                    color = TextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = StudioSecondary
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(StudioCard)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                color = if (option == selected) StudioSecondary else TextWhite,
                                fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StudioHeader(
    apiKeyProvided: Boolean,
    onImportClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF14121B),
                        Color.Transparent
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.sweepGradient(
                                colors = listOf(StudioPrimary, StudioAccent, StudioPrimary)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = TextWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = "REELGENIUS AI",
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Viral Clip Engine",
                        color = StudioSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Key Status Badge / Trigger
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (apiKeyProvided) SuccessGreen.copy(alpha = 0.15f) else StudioAccent.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (apiKeyProvided) SuccessGreen else StudioAccent)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (apiKeyProvided) "AI Active" else "Setup API Key",
                    color = if (apiKeyProvided) SuccessGreen else StudioAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SectionTitle(
    title: String,
    icon: ImageVector,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = StudioPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = TextWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
        }

        if (actionText != null && onActionClick != null) {
            Text(
                text = actionText,
                color = StudioSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onActionClick() }
                    .padding(4.dp)
            )
        }
    }
}

@Composable
fun VideoWorkspaceCard(
    project: VideoProject,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    // Beautiful gradient backgrounds based on title name
    val gradColors = when (project.title) {
        "Tech Setup Showcase" -> listOf(Color(0xFF4B6CB7), Color(0xFF182848))
        "Sunset Coast Drone" -> listOf(Color(0xFFFC4A1A), Color(0xFFF7B733))
        "Beast Mode Gym Session" -> listOf(Color(0xFFE55D87), Color(0xFF5FC3E4))
        "Sizzling Steak ASMR" -> listOf(Color(0xFF1F1C2C), Color(0xFF928DAB))
        else -> listOf(Color(0xFF11998e), Color(0xFF38ef7d))
    }

    Box(
        modifier = Modifier
            .width(140.dp)
            .height(115.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                brush = if (isSelected) Brush.linearGradient(
                    colors = listOf(
                        StudioSecondary,
                        StudioPrimary
                    )
                ) else SolidColor(StudioCardPressed),
                shape = RoundedCornerShape(18.dp)
            )
            .background(Brush.linearGradient(colors = gradColors))
            .clickable { onClick() }
    ) {
        // Shadow/Mute layer at bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        )

        // Overlay video detail indicators
        Box(
            modifier = Modifier
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 5.dp, vertical = 2.dp)
                .align(Alignment.TopEnd)
        ) {
            Text(
                text = "${project.durationSec}s",
                color = TextWhite,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        ) {
            Text(
                text = project.title,
                color = TextWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "Footage Source",
                color = TextGray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun AIResponsePanel(
    result: ReelAnalysisResult,
    onSaveClick: () -> Unit,
    isSaving: Boolean
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = StudioCard),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioGold.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Stars,
                        contentDescription = null,
                        tint = StudioGold,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Gemini High-Retention Analysis",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                // Small rating gauge inside card
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(StudioGold.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${result.viralScore}",
                        color = StudioGold,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // AI recommended trims details
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(StudioCardPressed)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "AI Suggested Trim Boundaries",
                        color = TextGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${String.format("%.1f", result.recommendedStartSec)}s - ${String.format("%.1f", result.recommendedEndSec)}s (${String.format("%.1f", result.recommendedEndSec - result.recommendedStartSec)}s total)",
                        color = StudioSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Icon(
                    imageVector = Icons.Default.ContentCut,
                    contentDescription = null,
                    tint = StudioSecondary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Text overlay / Script Hook details
            Text(
                text = "Viral Overlay Screen Hook",
                color = TextGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "\"${result.scriptHook}\"",
                color = TextWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // AI Caption details
            Text(
                text = "Optimized Video Caption",
                color = TextGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StudioCardPressed)
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = result.caption,
                        color = TextWhite,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = result.hashtags,
                        color = StudioPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // AI retention tips
            Row {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Viral Retention Insight",
                        color = SuccessGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = result.retentionTip,
                        color = TextWhite,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Save exported Reel action trigger
            Button(
                onClick = onSaveClick,
                colors = ButtonDefaults.buttonColors(containerColor = StudioGold),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = StudioBackground, modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.SaveAlt,
                        contentDescription = "Save Reel",
                        tint = StudioBackground
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Export & Save Beautiful Reel to Gallery",
                        color = StudioBackground,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SavedReelGalleryCard(
    reel: GeneratedReel,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StudioCard),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Fake Visual Miniature Thumbnail Representation
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                StudioPrimary.copy(alpha = 0.5f),
                                StudioSecondary.copy(alpha = 0.5f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VideoFile,
                    contentDescription = null,
                    tint = TextWhite,
                    modifier = Modifier.size(24.dp)
                )

                // Miniature Aspect indicator
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = reel.videoAspect,
                        color = TextWhite,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = reel.title,
                    color = TextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "Trim: ${String.format("%.1f", reel.startTimeSec)}s - ${String.format("%.1f", reel.endTimeSec)}s • Filter: ${reel.filterName}",
                    color = TextGray,
                    fontSize = 11.sp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.TrendingUp,
                        contentDescription = null,
                        tint = StudioGold,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Score: ${reel.viralScore}%",
                        color = StudioGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Delete action
            IconButton(
                onClick = onDelete
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = StudioAccent.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// Dialog to add custom video files to editing workspace
@Composable
fun AddVideoDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Int) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("30") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = StudioCard),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(1.dp, StudioPrimary.copy(alpha = 0.2f), RoundedCornerShape(28.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(StudioPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryAdd,
                        contentDescription = null,
                        tint = StudioPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Import Device Footage",
                    color = TextWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "Add a video project model workspace into your studio list.",
                    color = TextGray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Video Segment Title") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioPrimary,
                        unfocusedBorderColor = StudioCardPressed,
                        focusedLabelColor = StudioPrimary,
                        unfocusedLabelColor = TextGray,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it },
                    label = { Text("Duration (seconds)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioPrimary,
                        unfocusedBorderColor = StudioCardPressed,
                        focusedLabelColor = StudioPrimary,
                        unfocusedLabelColor = TextGray,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
                        border = BorderStroke(1.dp, StudioCardPressed),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val durInt = duration.toIntOrNull() ?: 30
                            if (title.isNotBlank()) {
                                onAdd(title, durInt)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StudioPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("confirm_import_button")
                    ) {
                        Text("Import Clip", color = TextWhite, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Gorgeous simulated full screen vertical mobile reels viewer
@Composable
fun ReelsPlayerDialog(
    viewModel: ReelStudioViewModel,
    onDismiss: () -> Unit
) {
    val project = viewModel.selectedProject.value ?: return
    val startS = viewModel.startTime.value
    val endS = viewModel.endTime.value
    val scaleFactor = (viewModel.playPosition.value - startS) / (endS - startS)
    val widthClass = viewModel.selectedAspect.value

    var mockLikes by remember { mutableStateOf(1450) }
    var isLiked by remember { mutableStateOf(false) }

    // Spinning disc animation for simulated playing audio
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    // Dynamic filtering color mask based on chosen style
    val filterColor = when (viewModel.selectedFilter.value) {
        "Cyber Neon" -> Color(0xFFFF0055).copy(alpha = 0.12f)
        "Dark Noir" -> Color(0xFF000000).copy(alpha = 0.5f)
        "Vibrant Glow" -> Color(0xFFFFD700).copy(alpha = 0.12f)
        else -> Color.Transparent
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxHeight(0.85f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black)
                .border(2.dp, StudioPrimary, RoundedCornerShape(28.dp))
        ) {
            // Simulated video background based on aspect ratio chosen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                // Adaptive box container matching specified project sizes
                val aspectModifier = when (widthClass) {
                    "1:1" -> Modifier.aspectRatio(1f)
                    "16:9" -> Modifier.aspectRatio(16f / 9f)
                    else -> Modifier.fillMaxSize()
                }

                // Video container simulation representational gradient block matching the title
                val gradColors = when (project.title) {
                    "Tech Setup Showcase" -> listOf(Color(0xFF4B6CB7), Color(0xFF182848))
                    "Sunset Coast Drone" -> listOf(Color(0xFFFC4A1A), Color(0xFFF7B733))
                    "Beast Mode Gym Session" -> listOf(Color(0xFFE55D87), Color(0xFF5FC3E4))
                    "Sizzling Steak ASMR" -> listOf(Color(0xFF1F1C2C), Color(0xFF928DAB))
                    else -> listOf(Color(0xFF11998e), Color(0xFF38ef7d))
                }

                Box(
                    modifier = aspectModifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(colors = gradColors))
                ) {
                    // Video mock visual details (lines and rings like waves/frequencies)
                    Canvas(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val strokeW = 4f
                        drawCircle(
                            color = Color.White.copy(alpha = 0.08f),
                            radius = size.minDimension / 1.5f,
                            style = Stroke(strokeW)
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.04f),
                            radius = size.minDimension / 2.5f,
                            style = Stroke(strokeW)
                        )
                    }

                    // Apply active aesthetic overlay color filter chosen in studio
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(filterColor)
                    )

                    // Overlay glowing script text centered high
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                            .align(Alignment.TopCenter),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(6.dp)
                        ) {
                            Text(
                                text = viewModel.textOverlay.value,
                                color = TextWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Bottom progress track representing selected trims
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.2f))
                            .align(Alignment.BottomStart)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(scaleFactor)
                                .background(StudioSecondary)
                        )
                    }
                }
            }

            // Top action buttons bar (Dismiss + Info)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextWhite
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "LIVE REEL PREVIEW",
                        color = StudioSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Right side overlay controllers (Standard Reels Likes/Comment buttons)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Likes button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        isLiked = !isLiked
                        mockLikes += if (isLiked) 1 else -1
                    }
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isLiked) StudioAccent else TextWhite,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "$mockLikes",
                        color = TextWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Comment button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = TextWhite,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "284",
                        color = TextWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Send icon
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    tint = TextWhite,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Spinning record player overlay representation
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .rotate(rotation)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(1.dp, StudioSecondary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = StudioSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Bottom Profile and Caption info panel
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.75f)
                    .padding(start = 24.dp, bottom = 32.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar placeholder
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(StudioPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "R",
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "reels_creator",
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Transparent)
                            .border(1.dp, TextWhite, RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Follow",
                            color = TextWhite,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Caption
                val aiResult = viewModel.analysisResult.value
                Text(
                    text = aiResult?.caption ?: "Applying AI Trims over timeline! Editing visual highlights...",
                    color = TextWhite,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = aiResult?.hashtags ?: "#viral #reelgenius",
                    color = StudioSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Music track
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = TextWhite,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${viewModel.selectedAudio.value} soundtrack • Audio original",
                        color = TextWhite,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
