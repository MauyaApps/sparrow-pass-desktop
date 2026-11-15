package sparrow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sparrow.vault.Entry
import sparrow.vault.Importers
import sparrow.vault.Kind
import sparrow.vault.PasswordGenerator
import sparrow.vault.VaultSession
import sparrow.vault.WrongPasswordException
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Path
import java.util.Properties

// ---------- helpers ----------
object Config {
    private val file = File(System.getProperty("user.home"), ".sparrow/config.properties")
    fun lastPath(): String? = runCatching { Properties().apply { file.inputStream().use { load(it) } }.getProperty("last") }.getOrNull()
    fun setLast(p: String) {
        runCatching {
            file.parentFile.mkdirs()
            val pr = Properties().apply { setProperty("last", p) }
            file.outputStream().use { pr.store(it, null) }
        }
    }
}

fun pickFile(save: Boolean): String? {
    val d = FileDialog(null as Frame?, if (save) "Создать хранилище" else "Выбрать файл", if (save) FileDialog.SAVE else FileDialog.LOAD)
    d.isVisible = true
    val f = d.file ?: return null
    return File(d.directory, if (save && !f.contains('.')) "$f.sparrow" else f).path
}

/** Copies to the clipboard and clears it after 30 seconds if it still holds the same text. */
fun copySecret(text: String, scope: CoroutineScope) {
    val cb = Toolkit.getDefaultToolkit().systemClipboard
    cb.setContents(StringSelection(text), null)
    scope.launch {
        delay(30_000)
        runCatching {
            if (cb.getData(DataFlavor.stringFlavor) == text) cb.setContents(StringSelection(""), null)
        }
    }
}

private fun kindName(k: Kind) = when (k) { Kind.LOGIN -> "Пароль"; Kind.NOTE -> "Заметка"; Kind.CARD -> "Карта" }

// ---------- unlock / create ----------
@Composable
fun UnlockScreen(state: AppState) {
    var path by remember { mutableStateOf(Config.lastPath() ?: "") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exists = path.isNotBlank() && File(path).isFile

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sparrow", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(if (exists) "Введите мастер-пароль" else "Создайте новое хранилище или откройте существующее")
            OutlinedTextField(path, { path = it }, label = { Text("Файл хранилища") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ pickFile(false)?.let { path = it } }) { Text("Открыть файл…") }
                TextButton({ pickFile(true)?.let { path = it } }) { Text("Создать файл…") }
            }
            OutlinedTextField(pw, { pw = it }, label = { Text("Мастер-пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (!exists) {
                OutlinedTextField(pw2, { pw2 = it }, label = { Text("Повторите пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("Минимум 10 символов. Мастер-пароль нельзя восстановить: если забудете, данные не открыть.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && path.isNotBlank() && pw.isNotEmpty(),
                onClick = {
                    if (!exists && (pw.length < 10 || pw != pw2)) {
                        error = if (pw.length < 10) "Пароль короче 10 символов" else "Пароли не совпадают"
                        return@Button
                    }
                    scope.launch {
                        busy = true; error = null
                        try {
                            val s = withContext(Dispatchers.Default) {
                                val chars = pw.toCharArray()
                                try {
                                    if (exists) VaultSession.open(Path.of(path), chars) else VaultSession.create(Path.of(path), chars)
                                } finally { chars.fill('\u0000') }
                            }
                            Config.setLast(path); pw = ""; pw2 = ""
                            state.open(s)
                        } catch (e: WrongPasswordException) {
                            error = "Неверный пароль или файл повреждён"
                        } catch (e: Exception) {
                            error = e.message ?: "Ошибка"
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Подождите…" else if (exists) "Открыть" else "Создать хранилище") }
        }
    }
}

// ---------- main window ----------
@Composable
fun MainScreen(state: AppState) {
    val data = state.data
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    var showGen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }

    val tags = remember(data) { data.entries.flatMap { it.tags }.distinct().sorted() }
    val shown = remember(data, query, tag) {
        data.entries
            .filter { e -> (tag == null || tag in e.tags) && (query.isBlank() || listOf(e.title, e.username, e.url, e.notes).any { it.contains(query, ignoreCase = true) }) }
            .sortedBy { it.title.lowercase() }
    }
    val selected = data.entries.firstOrNull { it.id == selectedId }

    LaunchedEffect(state.message) { if (state.message != null) { delay(4_000); state.message = null } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sparrow", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            state.message?.let { Text("   $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            TextButton({ showGen = true }) { Text("Генератор") }
            TextButton({
                pickFile(false)?.let { f ->
                    try {
                        val found = Importers.detect(File(f).readText())
                        val fresh = found.filter { n -> data.entries.none { it.title == n.title && it.username == n.username && it.url == n.url && it.password == n.password } }
                        state.update(data.copy(entries = data.entries + fresh))
                        state.message = "Импортировано: ${fresh.size}" + if (found.size > fresh.size) " (дубликатов пропущено: ${found.size - fresh.size})" else ""
                    } catch (e: Exception) { state.message = "Импорт не удался: ${e.message}" }
                }
            }) { Text("Импорт") }
            TextButton({ showSettings = true }) { Text("Настройки") }
            Button({ state.lock() }) { Text("Заблокировать") }
        }

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(430.dp).fillMaxHeight().padding(start = 16.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Поиск") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(tag == null, { tag = null }, label = { Text("Все") }) }
                    items(tags) { t -> FilterChip(tag == t, { tag = if (tag == t) null else t }, label = { Text(t) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ state.update(data.copy(prefs = data.prefs.copy(tiles = !data.prefs.tiles))) }) { Text(if (data.prefs.tiles) "Вид: плитки" else "Вид: список") }
                    Spacer(Modifier.weight(1f))
                    Box {
                        Button({ addMenu = true }) { Text("+ Добавить") }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            Kind.values().forEach { k -> DropdownMenuItem(text = { Text(kindName(k)) }, onClick = { addMenu = false; editing = Entry(kind = k) }) }
                        }
                    }
                }
                if (shown.isEmpty()) Text("Ничего нет. Добавьте запись или импортируйте файл.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (data.prefs.tiles) {
                    LazyVerticalGrid(GridCells.Adaptive(190.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.id }) { e -> EntryCard(e, e.id == selectedId) { selectedId = e.id } }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown, key = { it.id }) { e -> EntryCard(e, e.id == selectedId) { selectedId = e.id } }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 16.dp, bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)) {
                if (selected == null) {
                    Text("Выберите запись слева", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Detail(selected, onEdit = { editing = selected }, onDelete = { deleting = selected }, onCopy = { copySecret(it, scope); state.message = "Скопировано, буфер очистится через 30 с" })
                }
            }
        }
    }

    editing?.let { e0 ->
        EditDialog(e0, onCancel = { editing = null }) { e ->
            val isNew = data.entries.none { it.id == e.id }
            state.update(data.copy(entries = if (isNew) data.entries + e else data.entries.map { if (it.id == e.id) e else it }))
            selectedId = e.id; editing = null
        }
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить запись?") },
            text = { Text("«${e.title}» будет удалена из хранилища.") },
            confirmButton = { Button({ state.update(data.copy(entries = data.entries.filter { it.id != e.id })); selectedId = null; deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Отмена") } },
        )
    }
    if (showGen) GeneratorDialog(onUse = null, onClose = { showGen = false }, scope = scope)
    if (showSettings) SettingsDialog(state) { showSettings = false }
}

@Composable
fun EntryCard(e: Entry, selected: Boolean, onClick: () -> Unit) {
    val sub = when (e.kind) {
        Kind.LOGIN -> e.username.ifEmpty { e.url }
        Kind.NOTE -> e.notes.lineSequence().firstOrNull() ?: ""
        Kind.CARD -> if (e.cardNumber.length >= 4) "•••• " + e.cardNumber.takeLast(4) else ""
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text(e.title.ifEmpty { "(без названия)" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kindName(e.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun Detail(e: Entry, onEdit: () -> Unit, onDelete: () -> Unit, onCopy: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title.ifEmpty { "(без названия)" }, style = MaterialTheme.typography.headlineSmall)
                Text(kindName(e.kind) + if (e.tags.isNotEmpty()) " · " + e.tags.joinToString(", ") else "", color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onEdit) { Text("Изменить") }
            TextButton(onDelete) { Text("Удалить") }
        }
        when (e.kind) {
            Kind.LOGIN -> { FieldRow("Логин", e.username, false, onCopy); FieldRow("Пароль", e.password, true, onCopy); FieldRow("Адрес", e.url, false, onCopy) }
            Kind.CARD -> { FieldRow("Владелец", e.cardHolder, false, onCopy); FieldRow("Номер карты", e.cardNumber, true, onCopy); FieldRow("Срок", e.expiry, false, onCopy); FieldRow("CVV", e.cvv, true, onCopy) }
            Kind.NOTE -> {}
        }
        FieldRow("Заметки", e.notes, false, onCopy)
    }
}

@Composable
fun FieldRow(label: String, value: String, secret: Boolean, onCopy: (String) -> Unit) {
    if (value.isBlank()) return
    var shown by remember(value) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(if (secret && !shown) "••••••••••" else value, style = MaterialTheme.typography.bodyLarge, fontFamily = if (secret) FontFamily.Monospace else null) }
        }
        if (secret) TextButton({ shown = !shown }) { Text(if (shown) "Скрыть" else "Показать") }
        TextButton({ onCopy(value) }) { Text("Копировать") }
    }
}

// ---------- dialogs ----------
@Composable
fun EditDialog(initial: Entry, onCancel: () -> Unit, onSave: (Entry) -> Unit) {
    var title by remember { mutableStateOf(initial.title) }
    var user by remember { mutableStateOf(initial.username) }
    var pass by remember { mutableStateOf(initial.password) }
    var url by remember { mutableStateOf(initial.url) }
    var holder by remember { mutableStateOf(initial.cardHolder) }
    var number by remember { mutableStateOf(initial.cardNumber) }
    var expiry by remember { mutableStateOf(initial.expiry) }
    var cvv by remember { mutableStateOf(initial.cvv) }
    var notes by remember { mutableStateOf(initial.notes) }
    var tagText by remember { mutableStateOf(initial.tags.joinToString(", ")) }
    val k = initial.kind
    @Composable fun field(label: String, v: String, set: (String) -> Unit, multi: Boolean = false) =
        OutlinedTextField(v, set, label = { Text(label) }, singleLine = !multi, minLines = if (multi) 4 else 1, modifier = Modifier.fillMaxWidth())

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(kindName(k)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field("Название", title, { title = it })
                when (k) {
                    Kind.LOGIN -> {
                        field("Логин", user, { user = it })
                        field("Пароль", pass, { pass = it })
                        TextButton({ pass = PasswordGenerator.generate() }) { Text("Сгенерировать пароль") }
                        field("Адрес сайта", url, { url = it })
                    }
                    Kind.CARD -> {
                        field("Владелец", holder, { holder = it }); field("Номер карты", number, { number = it })
                        field("Срок (ММ/ГГ)", expiry, { expiry = it }); field("CVV", cvv, { cvv = it })
                    }
                    Kind.NOTE -> {}
                }
                field("Заметки", notes, { notes = it }, multi = true)
                field("Теги через запятую", tagText, { tagText = it })
            }
        },
        confirmButton = {
            Button(enabled = title.isNotBlank(), onClick = {
                onSave(initial.copy(
                    title = title.trim(), username = user, password = pass, url = url.trim(), cardHolder = holder, cardNumber = number.trim(),
                    expiry = expiry.trim(), cvv = cvv.trim(), notes = notes, updatedAt = System.currentTimeMillis(),
                    tags = tagText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                ))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onCancel) { Text("Отмена") } },
    )
}

@Composable
fun GeneratorDialog(onUse: ((String) -> Unit)?, onClose: () -> Unit, scope: CoroutineScope) {
    var o by remember { mutableStateOf(PasswordGenerator.Options()) }
    var seed by remember { mutableStateOf(0) }
    val pwd = remember(o, seed) { PasswordGenerator.generate(o) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Генератор паролей") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionContainer { Text(pwd, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace) }
                Text("Длина: ${o.length}")
                Slider(o.length.toFloat(), { o = o.copy(length = it.toInt()) }, valueRange = 8f..64f)
                @Composable fun opt(label: String, v: Boolean, set: (Boolean) -> Unit) =
                    Row(Modifier.clickable { set(!v) }, verticalAlignment = Alignment.CenterVertically) { Checkbox(v, { set(it) }); Text(label) }
                opt("Строчные буквы", o.lower) { o = o.copy(lower = it).let { n -> if (n.lower || n.upper || n.digits || n.symbols) n else o } }
                opt("Заглавные буквы", o.upper) { o = o.copy(upper = it).let { n -> if (n.lower || n.upper || n.digits || n.symbols) n else o } }
                opt("Цифры", o.digits) { o = o.copy(digits = it).let { n -> if (n.lower || n.upper || n.digits || n.symbols) n else o } }
                opt("Символы", o.symbols) { o = o.copy(symbols = it).let { n -> if (n.lower || n.upper || n.digits || n.symbols) n else o } }
                opt("Без похожих символов (Il1O0o)", o.noAmbiguous) { o = o.copy(noAmbiguous = it) }
            }
        },
        confirmButton = { Button({ copySecret(pwd, scope) }) { Text("Копировать") } },
        dismissButton = { Row { TextButton({ seed++ }) { Text("Ещё") }; TextButton(onClose) { Text("Закрыть") } } },
    )
}

@Composable
fun SettingsDialog(state: AppState, onClose: () -> Unit) {
    val prefs = state.data.prefs
    var accent by remember { mutableStateOf(prefs.accent) }
    var bg by remember { mutableStateOf(prefs.background) }
    fun apply(a: String, b: String) { if (isHex(a) && isHex(b)) state.update(state.data.copy(prefs = state.data.prefs.copy(accent = a.trim(), background = b.trim()))) }
    val presets = listOf("#3DDC84" to "#06110A", "#00BFA5" to "#04110F", "#8BC34A" to "#0B1205", "#FFB300" to "#140E02", "#4FC3F7" to "#04101A")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Цвета (меняются сразу)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (a, b) ->
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(parseColor(a, androidx.compose.ui.graphics.Color.Green)).clickable { accent = a; bg = b; apply(a, b) })
                    }
                }
                OutlinedTextField(accent, { accent = it; apply(it, bg) }, label = { Text("Акцентный цвет (HEX)") }, singleLine = true, isError = !isHex(accent))
                OutlinedTextField(bg, { bg = it; apply(accent, it) }, label = { Text("Цвет фона (HEX)") }, singleLine = true, isError = !isHex(bg))
                Text("Автоблокировка: через 15 минут без действий.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClose) { Text("Готово") } },
    )
}
