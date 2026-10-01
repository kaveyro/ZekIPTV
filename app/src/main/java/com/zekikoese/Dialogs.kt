package com.zekikoese

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.tvFocusFrame
import kotlinx.coroutines.android.awaitFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fokussierbare Dialog-Zeile mit sichtbarem D-Pad-Fokus. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DialogRow(
    label: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    maxLines: Int = 1,
    iconRes: Int? = null,
    color: Color? = null,
    onClick: () -> Unit
) {
    val textColor = color ?: if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .tvFocusFrame(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                restColor = Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            color = textColor,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/** Einheitlicher Dialog-Rahmen: TV mit fester 10-Fuß-Breite, Handy in Systembreite. */
@Composable
private fun DialogFrame(
    onDismiss: () -> Unit,
    tvWidth: Int = 500,
    content: @Composable () -> Unit
) {
    val widthModifier = if (LocalIsTv.current) Modifier.width(tvWidth.dp) else Modifier.fillMaxWidth()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp).then(widthModifier)) {
                content()
            }
        }
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

/** Abbrechen/Bestätigen-Knöpfe am Dialogende. */
@Composable
private fun DialogButtons(
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    cancelFocus: FocusRequester? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TvButton(
            text = "Abbrechen",
            onClick = onDismiss,
            modifier = (if (cancelFocus != null) Modifier.focusRequester(cancelFocus) else Modifier).padding(end = 8.dp),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        TvButton(
            text = confirmText,
            onClick = onConfirm,
            containerColor = if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
            contentColor = if (destructive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
        )
    }
}

/** Fokus nach dem ersten Frame setzen (der Dialog ist vorher noch nicht attached). */
@Composable
private fun RequestInitialFocus(requester: FocusRequester) {
    LaunchedEffect(Unit) {
        repeat(10) {
            awaitFrame()
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/**
 * Sicherheitsabfrage vor dem Löschen. Der Fokus startet auf „Abbrechen“ — ein versehentliches
 * doppeltes OK auf der Fernbedienung löscht so nichts.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocus = remember { FocusRequester() }
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(title)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        DialogButtons(
            confirmText = confirmText,
            onConfirm = { onConfirm(); onDismiss() },
            onDismiss = onDismiss,
            destructive = true,
            cancelFocus = cancelFocus
        )
    }
    if (LocalIsTv.current) RequestInitialFocus(cancelFocus)
}

/** Eintrag eines Aktionsdialogs; [destructive] färbt die Zeile in der Fehlerfarbe. */
data class DialogAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/** Aktionsauswahl (z. B. für eine Playlist): eine Zeile je Aktion plus „Schließen“. */
@Composable
fun ActionsDialog(
    title: String,
    subtitle: String? = null,
    actions: List<DialogAction>,
    onDismiss: () -> Unit
) {
    DialogFrame(onDismiss = onDismiss, tvWidth = 460) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(12.dp))
        actions.forEach { action ->
            DialogRow(
                label = action.label,
                color = if (action.destructive) MaterialTheme.colorScheme.error else null,
                onClick = { onDismiss(); action.onClick() }
            )
        }
        DialogRow("Schließen", onClick = onDismiss)
    }
}

/**
 * Dialog zum Hinzufügen einer Playlist: entweder als M3U-URL oder per Xtream-Login
 * (Server, Benutzer, Passwort — daraus wird die get.php-URL gebaut). Unvollständige Eingaben
 * werden am Feld markiert, statt den Dialog kommentarlos zu schließen.
 */
@Composable
fun PlaylistInputDialog(
    onConfirm: (name: String, url: String) -> Unit,
    onConfirmXtream: (name: String, server: String, username: String, password: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var xtreamMode by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    // Fehler erst nach dem ersten Bestätigen anzeigen, nicht schon beim Tippen.
    var showErrors by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val urlError = validateStreamUrl(url)
    val serverError = if (server.isBlank()) "Bitte den Server eingeben." else null
    val userError = if (username.isBlank()) "Bitte den Benutzernamen eingeben." else null
    val passwordError = if (password.isBlank()) "Bitte das Passwort eingeben." else null

    fun submit() {
        showErrors = true
        if (xtreamMode) {
            if (serverError != null || userError != null || passwordError != null) return
            onConfirmXtream(name, server, username, password)
        } else {
            if (urlError != null) return
            onConfirm(name, url)
        }
        onDismiss()
    }

    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }

    val next = KeyboardOptions(imeAction = ImeAction.Next)
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle("Playlist hinzufügen")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GroupChip("M3U-URL", selected = !xtreamMode) { xtreamMode = false; showErrors = false }
            GroupChip("Xtream-Login", selected = xtreamMode) { xtreamMode = true; showErrors = false }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Anzeigename (optional)") },
            singleLine = true,
            keyboardOptions = next,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
        )
        Spacer(Modifier.height(12.dp))
        if (xtreamMode) {
            InputField(
                value = server,
                onValueChange = { server = it },
                label = "Server",
                placeholder = "http://anbieter.tv:8080",
                error = serverError.takeIf { showErrors },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            )
            InputField(
                value = username,
                onValueChange = { username = it },
                label = "Benutzername",
                error = userError.takeIf { showErrors },
                keyboardOptions = next
            )
            InputField(
                value = password,
                onValueChange = { password = it },
                label = "Passwort",
                error = passwordError.takeIf { showErrors },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                onDone = ::submit,
                trailing = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Verbergen" else "Zeigen")
                    }
                }
            )
        } else {
            InputField(
                value = url,
                onValueChange = { url = it },
                label = "M3U-URL",
                error = urlError.takeIf { showErrors },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                onDone = ::submit
            )
        }
        Spacer(Modifier.height(12.dp))
        DialogButtons(confirmText = "Hinzufügen", onConfirm = ::submit, onDismiss = onDismiss)
    }
}

/** Einzeiliges Eingabefeld mit Fehlertext darunter. */
@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onDone: (() -> Unit)? = null,
    placeholder: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = trailing,
        modifier = modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(if (error != null) 4.dp else 12.dp))
}

/** Hinweis auf eine neuere App-Version (GitHub-Release). */
@Composable
fun UpdateDialog(release: AppRelease, onInstall: () -> Unit, onLater: () -> Unit) {
    DialogFrame(onDismiss = onLater, tvWidth = 620) {
        DialogTitle("Update verfügbar: ${release.version}")
        if (release.notes.isNotBlank()) {
            Text(
                text = release.notes.take(600),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        DialogRow("Herunterladen und installieren", onClick = onInstall)
        DialogRow("Später", onClick = onLater)
    }
}

/**
 * Hinweis nach dem Laden einer M3U-Playlist: Die Streams stammen von einem Xtream-Server,
 * dessen Zugang der Anbieter bestätigt hat — Umwandlung anbieten.
 */
@Composable
fun XtreamSuggestionDialog(
    suggestion: XtreamSuggestion,
    onLink: () -> Unit,
    onConvert: () -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit
) {
    val host = remember(suggestion) { suggestion.account.baseUrl.substringAfter("://") }
    DialogFrame(onDismiss = onLater, tvWidth = 620) {
        DialogTitle("Xtream-Zugang erkannt")
        Text(
            text = "Die Sender dieser Playlist kommen von $host (Benutzer ${suggestion.account.username}). " +
                "Mit dem Xtream-Zugang gibt es zusätzlich Filme, Serien, Catch-up und die Konto-Info.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        DialogRow("Verknüpfen — meine Senderliste behalten", maxLines = 2, onClick = onLink)
        DialogRow("Komplett auf Xtream umstellen — volle Anbieter-Liste + EPG", maxLines = 2, onClick = onConvert)
        DialogRow("Später (in den Einstellungen)", onClick = onLater)
        DialogRow("Nein danke", onClick = onNever)
    }
}

/**
 * Einfacher Dialog für eine einzelne Texteingabe (z. B. EPG-URL). [validate] liefert einen
 * Fehlertext (Dialog bleibt offen) oder null (Eingabe wird übernommen).
 */
@Composable
fun SingleTextInputDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Hinzufügen",
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: (String) -> String? = { null }
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    fun submit() {
        error = validate(text)
        if (error == null) {
            onConfirm(text.trim())
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }

    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(title)
        InputField(
            value = text,
            onValueChange = { text = it; error = null },
            label = label,
            error = error,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            onDone = ::submit,
            modifier = Modifier.focusRequester(focusRequester)
        )
        Spacer(Modifier.height(12.dp))
        DialogButtons(confirmText = confirmText, onConfirm = ::submit, onDismiss = onDismiss)
    }
}

/**
 * PIN-Dialog für den Jugendschutz. [confirmTwice] = neue PIN festlegen (zweites Feld zur
 * Kontrolle). [onSubmit] prüft die PIN und liefert einen Fehlertext (Dialog bleibt offen)
 * oder null (Dialog schließt).
 */
@Composable
fun PinDialog(
    title: String,
    confirmText: String,
    confirmTwice: Boolean,
    onSubmit: (String) -> String?,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var repeatError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    fun submit() {
        repeatError = null
        if (confirmTwice && pin.length >= MIN_PIN_LENGTH && pin != repeat) {
            repeatError = "Die PINs stimmen nicht überein."
            return
        }
        error = onSubmit(pin)
        if (error == null) onDismiss()
    }

    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }

    // PIN: nur Ziffern, maskiert, Zifferntastatur.
    fun digits(value: String) = value.filter(Char::isDigit).take(8)
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(title)
        InputField(
            value = pin,
            onValueChange = { pin = digits(it); error = null },
            label = if (confirmTwice) "Neue PIN (mind. $MIN_PIN_LENGTH Ziffern)" else "PIN",
            error = error,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = if (confirmTwice) ImeAction.Next else ImeAction.Done
            ),
            onDone = ::submit,
            modifier = Modifier.focusRequester(focusRequester)
        )
        if (confirmTwice) {
            InputField(
                value = repeat,
                onValueChange = { repeat = digits(it); repeatError = null },
                label = "PIN wiederholen",
                error = repeatError,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                onDone = ::submit
            )
        }
        Spacer(Modifier.height(12.dp))
        DialogButtons(confirmText = confirmText, onConfirm = ::submit, onDismiss = onDismiss)
    }
}

internal const val MIN_PIN_LENGTH = 4

/** Lang-Druck-Menü eines Senders: Tagesprogramm und Favoriten-Toggle. */
@Composable
fun ChannelActionsDialog(
    channel: Channel,
    isFavorite: Boolean,
    hasEpg: Boolean,
    onShowEpg: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit
) {
    DialogFrame(onDismiss = onDismiss, tvWidth = 420) {
        Text(
            text = channel.name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (hasEpg) {
            DialogRow("Tagesprogramm anzeigen") { onShowEpg() }
        }
        DialogRow(if (isFavorite) "★ Aus Favoriten entfernen" else "☆ Zu Favoriten hinzufügen") {
            onToggleFavorite()
            onDismiss()
        }
        DialogRow("Schließen") { onDismiss() }
    }
}

/**
 * Tagesprogramm eines Senders aus den geladenen EPG-Daten. Hat der Sender ein Archiv
 * ([catchupFrom] = früheste abrufbare Startzeit), sind vergangene Sendungen per OK abspielbar.
 */
@Composable
fun EpgDayDialog(
    channelName: String,
    programmes: List<EpgProgramme>,
    catchupFrom: Long? = null,
    onPlayCatchup: (EpgProgramme) -> Unit = {},
    onDismiss: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val now = System.currentTimeMillis()
    val isTv = LocalIsTv.current

    DialogFrame(onDismiss = onDismiss, tvWidth = 560) {
        Text(
            text = "Programm: $channelName",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyColumn(modifier = Modifier.heightIn(max = if (isTv) 560.dp else 440.dp)) {
            items(programmes) { programme ->
                val isNow = now >= programme.startMs && now < programme.stopMs
                val replayable = catchupFrom != null &&
                    programme.stopMs <= now && programme.startMs >= catchupFrom
                DialogRow(
                    label = "%s – %s   %s".format(
                        timeFormat.format(Date(programme.startMs)),
                        timeFormat.format(Date(programme.stopMs)),
                        programme.title
                    ),
                    highlighted = isNow,
                    iconRes = if (replayable) R.drawable.ic_fast_rewind else null,
                    onClick = { if (replayable) onPlayCatchup(programme) }
                )
            }
        }
        if (catchupFrom != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_fast_rewind),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "= vergangene Sendung aus dem Archiv abspielen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DialogRow("Schließen") { onDismiss() }
    }
}
