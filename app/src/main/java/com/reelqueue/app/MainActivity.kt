package com.reelqueue.app

import android.Manifest
import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

data class VideoRef(val uri: Uri, val name: String)

fun naturalCompare(a: String, b: String): Int {
    val re = Regex("\\d+|\\D+")
    val ra = re.findAll(a.lowercase()).map { it.value }.toList()
    val rb = re.findAll(b.lowercase()).map { it.value }.toList()
    for (k in 0 until minOf(ra.size, rb.size)) {
        val x = ra[k]
        val y = rb[k]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val n = x.toBigInteger().compareTo(y.toBigInteger())
            if (n != 0) n else x.length.compareTo(y.length)
        } else x.compareTo(y)
        if (c != 0) return c
    }
    return ra.size.compareTo(rb.size)
}

class MainVm(private val app: Application) : AndroidViewModel(app) {
    var pageId by mutableStateOf(Prefs.pageId(app))
    var token by mutableStateOf(Prefs.token(app))
    var videos by mutableStateOf<List<VideoRef>>(emptyList())
    var rows by mutableStateOf<List<CsvRow>>(emptyList())
    var csvName by mutableStateOf("")
    var startMillis by mutableStateOf(defaultStart())
    var intervalText by mutableStateOf("180")

    private val intervalMin: Int get() = intervalText.toIntOrNull() ?: 0

    private fun defaultStart(): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.HOUR_OF_DAY, 1)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun nameOf(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: uri.lastPathSegment.orEmpty()
        }
        return uri.lastPathSegment.orEmpty()
    }

    fun pickVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        videos = uris.map { u ->
            try {
                app.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            VideoRef(u, nameOf(u))
        }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }

    fun pickCsv(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        val text = String(bytes, Charsets.UTF_8)
        rows = if (TxtParser.looksLikeBlocks(text)) TxtParser.parse(text) else CsvParser.parse(text)
        csvName = nameOf(uri)
    }

    private fun timeFor(i: Int): Long = startMillis + i.toLong() * intervalMin * 60_000L

    /** Finds which row belongs to a video: by file name, then by the number in the name, else by order. */
    private fun rowIndexFor(i: Int, v: VideoRef): Int? {
        val keyed = rows.any { it.fileName != null || it.number != null }
        if (!keyed) return if (i in rows.indices) i else null
        val base = v.name.substringBeforeLast('.').lowercase()
        val byName = rows.indexOfFirst { it.fileName?.substringBeforeLast('.')?.lowercase() == base }
        if (byName >= 0) return byName
        val n = Regex("(\\d+)(?!.*\\d)").find(base)?.value?.toIntOrNull()
        if (n != null) {
            val byNum = rows.indexOfFirst { it.number == n }
            if (byNum >= 0) return byNum
        }
        return null
    }

    private fun rowFor(i: Int, v: VideoRef): CsvRow? = rowIndexFor(i, v)?.let { rows[it] }

    fun previewItems(): List<Item> = videos.mapIndexed { i, v ->
        val r = rowFor(i, v)
        Item(
            index = i,
            videoUri = v.uri.toString(),
            videoName = v.name,
            title = r?.title.orEmpty(),
            description = r?.description.orEmpty(),
            publishAtSec = timeFor(i) / 1000
        )
    }

    fun problems(): List<String> {
        val p = mutableListOf<String>()
        if (pageId.isBlank()) p += "Page ID দিন"
        if (token.isBlank()) p += "Access Token দিন"
        if (videos.isEmpty()) p += "ভিডিও বাছাই করুন"
        if (rows.isEmpty()) p += "টাইটেল ফাইল বাছাই করুন"
        if (videos.isNotEmpty() && rows.isNotEmpty()) {
            val missing = videos.indices.filter { rowIndexFor(it, videos[it]) == null }
            if (missing.isNotEmpty()) {
                val names = missing.take(3).joinToString(", ") { videos[it].name }
                p += "${missing.size}টি ভিডিওর টাইটেল ফাইলে পাওয়া যায়নি: $names" + if (missing.size > 3) " …" else ""
            }
        }
        if (intervalMin < 1) p += "ব্যবধান কমপক্ষে ১ মিনিট দিন"
        if (startMillis < System.currentTimeMillis() + 11 * 60_000L)
            p += "শুরুর সময় এখন থেকে কমপক্ষে ১১ মিনিট পরে দিন"
        return p
    }

    fun warnings(): List<String> {
        val w = mutableListOf<String>()
        if (videos.isNotEmpty() && rows.isNotEmpty()) {
            val used = videos.indices.mapNotNull { rowIndexFor(it, videos[it]) }.toSet().size
            if (rows.size > used) w += "ফাইলে ${rows.size}টি এন্ট্রি, ভিডিও মিলেছে ${used}টির — বাকি এন্ট্রি বাদ যাবে"
            if (videos.indices.any { rowFor(it, videos[it])?.title.isNullOrBlank() })
                w += "কিছু ভিডিওর টাইটেল ফাঁকা"
            if (videos.indices.any { (rowFor(it, videos[it])?.description?.length ?: 0) > 5000 })
                w += "কিছু ডেসক্রিপশন ৫০০০ অক্ষরের বেশি লম্বা — ফেসবুক না নিতে পারে"
        }
        if (videos.isNotEmpty() && timeFor(videos.size - 1) > System.currentTimeMillis() + 30L * 24 * 3600_000)
            w += "শেষ ভিডিওর সময় ৩০ দিনের বেশি দূরে — ফেসবুক না নিলে ব্যবধান কমান"
        return w
    }

    fun begin(ctx: Context, retryOnly: Boolean) {
        Prefs.save(ctx, pageId, token)
        if (!retryOnly) {
            JobState.intervalSec.value = intervalMin * 60L
            JobState.items.value = previewItems()
        }
        ContextCompat.startForegroundService(ctx, Intent(ctx, UploadService::class.java))
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, UploadService::class.java).setAction(UploadService.ACTION_STOP))
    }
}

private val dateFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH)

fun fmt(sec: Long): String = dateFmt.format(Date(sec * 1000))

fun pickDateTime(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(
        ctx,
        { _, y, m, d ->
            TimePickerDialog(
                ctx,
                { _, h, min ->
                    val r = Calendar.getInstance().apply {
                        set(y, m, d, h, min, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(r.timeInMillis)
                },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false
            ).show()
        },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
fun App(vm: MainVm = viewModel()) {
    val ctx = LocalContext.current
    val jobItems by JobState.items.collectAsStateWithLifecycle()
    val running by JobState.running.collectAsStateWithLifecycle()

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.pickVideos(uris)
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickCsv(uri)
    }

    val pendingRetry = remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.begin(ctx, pendingRetry.value)
    }

    fun startWithPermission(retry: Boolean) {
        pendingRetry.value = retry
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.begin(ctx, retry)
    }

    val hasJob = jobItems.isNotEmpty()
    val shown = if (hasJob) jobItems else vm.previewItems()
    val problems = vm.problems()
    val warnings = vm.warnings()

    LazyColumn(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("ReelQueue", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("ভিডিও সিরিয়ালি ফেসবুক পেজে অটো শিডিউল", color = Color.Gray)
        }

        if (!hasJob) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("১. ফেসবুক পেজ", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = vm.pageId, onValueChange = { vm.pageId = it },
                            label = { Text("Page ID") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = vm.token, onValueChange = { vm.token = it },
                            label = { Text("Page Access Token") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("২. ভিডিও ও টাইটেল ফাইল", fontWeight = FontWeight.SemiBold)
                        Button(
                            onClick = { videoPicker.launch(arrayOf("video/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (vm.videos.isEmpty()) "ভিডিও বাছাই করুন" else "${vm.videos.size}টি ভিডিও বাছাই হয়েছে (বদলান)") }
                        Text("ভিডিওর ফাইলের নাম আর টাইটেল ফাইলের নাম/নম্বর মিলিয়ে টাইটেল বসবে", fontSize = 12.sp, color = Color.Gray)
                        OutlinedButton(
                            onClick = { csvPicker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (vm.rows.isEmpty()) "টাইটেল ফাইল বাছাই করুন (TXT / CSV)" else "${vm.csvName} — ${vm.rows.size}টি এন্ট্রি") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("৩. সময়", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(
                            onClick = { pickDateTime(ctx, vm.startMillis) { vm.startMillis = it } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("প্রথম ভিডিও: " + fmt(vm.startMillis / 1000)) }
                        OutlinedTextField(
                            value = vm.intervalText,
                            onValueChange = { vm.intervalText = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("প্রতি ভিডিওর মাঝে ব্যবধান (মিনিট)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            if (problems.isNotEmpty()) {
                item {
                    Column {
                        problems.forEach { Text("• $it", color = Color(0xFFB00020), fontSize = 13.sp) }
                    }
                }
            }
            if (warnings.isNotEmpty()) {
                item {
                    Column {
                        warnings.forEach { Text("⚠ $it", color = Color(0xFF8A5A00), fontSize = 13.sp) }
                    }
                }
            }
            item {
                Button(
                    onClick = { startWithPermission(false) },
                    enabled = problems.isEmpty() && !running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("আপলোড ও শিডিউল শুরু করুন") }
            }
        } else {
            item {
                val ok = jobItems.count { it.status == Status.SCHEDULED }
                val bad = jobItems.count { it.status == Status.FAILED }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("শিডিউল হয়েছে: $ok / ${jobItems.size}    ব্যর্থ: $bad", fontWeight = FontWeight.SemiBold)
                        if (running) {
                            Text("আপলোড চলছে — অ্যাপ বন্ধ করলেও ব্যাকগ্রাউন্ডে চলবে। ইন্টারনেট চালু রাখুন।", fontSize = 12.sp)
                            OutlinedButton(onClick = { vm.stop(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("বন্ধ করুন") }
                        } else {
                            if (jobItems.any { it.status != Status.SCHEDULED }) {
                                Button(onClick = { startWithPermission(true) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("বাকি / ব্যর্থগুলো আবার চালান")
                                }
                            }
                            OutlinedButton(
                                onClick = { JobState.items.value = emptyList() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("নতুন ব্যাচ শুরু করুন") }
                        }
                    }
                }
            }
        }

        if (shown.isNotEmpty()) {
            item { Text(if (hasJob) "অবস্থা" else "প্রিভিউ (ভিডিও → টাইটেল → সময়)", fontWeight = FontWeight.SemiBold) }
        }
        itemsIndexed(shown) { _, it ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("#${it.index + 1}", fontWeight = FontWeight.Bold)
                        Text(it.videoName, fontSize = 13.sp, color = Color.Gray)
                    }
                    Text(it.title.ifBlank { "(টাইটেল নেই)" }, fontWeight = FontWeight.Medium)
                    if (it.description.isNotBlank()) {
                        Text(it.description, fontSize = 12.sp, color = Color.Gray, maxLines = 2)
                    }
                    Text("🕒 " + fmt(it.publishAtSec), fontSize = 12.sp)
                    if (hasJob) {
                        val (label, color) = when (it.status) {
                            Status.PENDING -> "অপেক্ষায়" to Color.Gray
                            Status.UPLOADING -> "আপলোড হচ্ছে ${it.progress}%" to Color(0xFF1565C0)
                            Status.SCHEDULED -> "✓ ${it.message}" to Color(0xFF2E7D32)
                            Status.FAILED -> "✗ ব্যর্থ: ${it.message}" to Color(0xFFB00020)
                        }
                        Text(label, color = color, fontSize = 12.sp)
                        if (it.status == Status.UPLOADING) {
                            LinearProgressIndicator(
                                progress = { it.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
