package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.AddedGameArt
import com.droiddeck.launcher.frontend.AddedGameEdits
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Each art slot's thumbnail, in its true shape: width, height, corner. */
private fun AddedGameArt.Slot.shape(): Triple<Dp, Dp, Dp> = when (this) {
    AddedGameArt.Slot.COVER -> Triple(28.dp, 41.dp, 5.dp)
    AddedGameArt.Slot.BACKGROUND -> Triple(60.dp, 34.dp, 5.dp)
    AddedGameArt.Slot.LOGO -> Triple(60.dp, 26.dp, 5.dp)
    AddedGameArt.Slot.ICON -> Triple(30.dp, 30.dp, 7.dp)
}

@Composable
private fun AddedGameArt.Slot.label(): String = stringResource(
    when (this) {
        AddedGameArt.Slot.COVER -> R.string.art_cover
        AddedGameArt.Slot.BACKGROUND -> R.string.art_background
        AddedGameArt.Slot.LOGO -> R.string.art_logo
        AddedGameArt.Slot.ICON -> R.string.art_icon
    },
)

@Composable
private fun sourceLabel(source: String): String = stringResource(
    when (source) {
        AddedGameArt.FOLDER -> R.string.art_src_folder
        AddedGameArt.STEAM -> R.string.art_src_steam
        AddedGameArt.SGDB -> R.string.art_src_sgdb
        AddedGameArt.FILE -> R.string.art_src_file
        AddedGameArt.EXE -> R.string.art_src_exe
        else -> R.string.art_src_auto
    },
)

/** A thumbnail of [model] (a file or URL) in [slot]'s shape, scaled by [scale]. */
@Composable
private fun SlotThumb(slot: AddedGameArt.Slot, model: Any?, scale: Float = 1f, selected: Boolean = false) {
    val pal = LocalPalette.current
    val (w, h, r) = slot.shape()
    val shape = RoundedCornerShape(r)
    Box(
        Modifier.size(w * scale, h * scale).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 2.dp else 1.dp, if (selected) pal.signal else pal.line, shape),
    ) {
        if (model != null) AsyncImage(
            model = model, contentDescription = null,
            contentScale = if (slot == AddedGameArt.Slot.LOGO || slot == AddedGameArt.Slot.ICON) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** A row of the editor: a label column, then the value. */
@Composable
private fun FieldRow(label: String, focus: FocusRequester? = null, onClick: () -> Unit, value: @Composable () -> Unit) {
    ListRow(focus = focus, onClick = onClick) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f)) { value() }
    }
}

/** Where the editor opens: its game's folder, straight on Target, and whether it came from the added-games summary. */
internal class EditRequest(val folder: String, val openTarget: Boolean, val fromSummary: Boolean)

/**
 * An added game's editor, Steam's non-Steam Properties in the app: Name, Target (the ranked exes,
 * "?" where nothing clearly won, Other exe…), Start in, Launch options, the four pieces of art, and
 * Remove (two presses; the files stay). Every change goes through the game's own Steam shortcut.
 */
@Composable
internal fun AddedGameEditor(request: EditRequest, onBack: () -> Unit, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    val folder = request.folder
    var version by remember { mutableIntStateOf(0) }
    var game by remember { mutableStateOf<AddedGames.Game?>(null) }
    var arts by remember { mutableStateOf<List<AddedGameArt.SlotState>>(emptyList()) }
    var ranked by remember { mutableStateOf<List<File>>(emptyList()) }
    var targetOpen by remember { mutableStateOf(request.openTarget) }
    var armed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var otherExe by remember { mutableStateOf(false) }
    var artSlot by remember { mutableStateOf<AddedGameArt.Slot?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            val g = AddedGameEdits.game(app, folder)
            game = g
            if (g != null) {
                arts = AddedGameArt.describe(app, g)
                ranked = AddedGames.rank(g.folder).exes.let { list -> if (list.any { it.path == g.exe.path }) list else listOf(g.exe) + list }
            }
        }
    }
    LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(4_000); armed = false } }
    val change: (() -> Unit) -> Unit = { work ->
        scope.launch {
            withContext(Dispatchers.IO) { work() }
            version++
            onChanged()
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(game != null) { if (game != null) focusWithinFrames({ false }) { first } }
    AppDialog(shown, close, "gameEditor", wide = false, maxWidth = 460.dp) {
        val g = game
        DialogTitleRow(
            g?.name ?: "", onClose = close,
            leading = if (request.fromSummary) ({ SmallIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), colors.onBackground) { onBack(); close() } }) else null,
        )
        if (g == null) return@AppDialog
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        ) {
            FieldRow(stringResource(R.string.edit_name), focus = first, onClick = { editing = "name" }) { ValueText(g.name) }
            FieldRow(stringResource(R.string.edit_target), onClick = { targetOpen = !targetOpen }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { ValueText(g.exe.name) }
                    if (g.exeUncertain) UncertainMark(stringResource(R.string.mode_added_exe_check))
                }
            }
            if (targetOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 16.dp)) {
                    for (exe in ranked) key(exe.path) {
                        val rel = exe.relativeToOrSelf(g.folder).invariantSeparatorsPath
                        PickerRow(null, exe.name, rel.substringBeforeLast('/', "").ifEmpty { null }, accent = exe.path == g.exe.path) {
                            targetOpen = false
                            change { AddedGameEdits.setExe(app, folder, exe) }
                        }
                    }
                    PickerRow(null, stringResource(R.string.edit_other_exe), null) { otherExe = true }
                }
            }
            FieldRow(stringResource(R.string.edit_start_in), onClick = { editing = "start" }) { ValueText(g.guestDir.substringAfterLast('/')) }
            FieldRow(stringResource(R.string.edit_launch_options), onClick = { editing = "launch" }) { ValueText(g.launchOptions.ifEmpty { "—" }, muted = g.launchOptions.isEmpty()) }
            Text(stringResource(R.string.edit_artwork), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            for (state in arts) key(state.slot) {
                FieldRow(state.slot.label(), onClick = { artSlot = state.slot }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SlotThumb(state.slot, state.file)
                        ValueText(sourceLabel(state.source), muted = true)
                    }
                }
            }
            RemoveRow(armed) {
                if (!armed) armed = true else {
                    armed = false
                    scope.launch {
                        withContext(Dispatchers.IO) { AddedGames.remove(app, g.folder) }
                        onChanged()
                        if (request.fromSummary) onBack()
                        close()
                    }
                }
            }
        }
        if (unreachable) Text(stringResource(R.string.games_add_unreachable), fontSize = 12.sp, color = colors.error)
    }
    val g = game ?: return
    when (editing) {
        "name" -> TextEntryDialog(stringResource(R.string.edit_name), SessionPrefs.addedGameName(ctx, folder).ifEmpty { g.name }, onDismiss = { editing = null }) { value ->
            change { AddedGameEdits.setName(app, folder, if (value == g.name && SessionPrefs.addedGameName(app, folder).isEmpty()) "" else value) }
        }
        "start" -> TextEntryDialog(stringResource(R.string.edit_start_in), hostPath(ctx, g), onDismiss = { editing = null }) { value ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { AddedGameEdits.setStartIn(app, folder, if (value == g.exe.parentFile?.path) "" else value) }
                unreachable = !ok
                version++
                onChanged()
            }
        }
        "launch" -> TextEntryDialog(stringResource(R.string.edit_launch_options), g.launchOptions, onDismiss = { editing = null }) { value ->
            change { AddedGameEdits.setLaunchOptions(app, folder, value) }
        }
    }
    if (otherExe) ExePickerDialog(stringResource(R.string.games_pick_exe_title), start = g.folder, onDismiss = { otherExe = false }, onPick = { exe ->
        targetOpen = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AddedGameEdits.setExe(app, folder, exe) }
            unreachable = !ok
            version++
            onChanged()
        }
    })
    artSlot?.let { slot ->
        val current = arts.firstOrNull { it.slot == slot }
        ArtChooserDialog(g, slot, current, onBack = { artSlot = null }, onCloseAll = { artSlot = null; close() }) { version++; onChanged() }
    }
}

/** Start in as a path on the device: the override mapped back under the game's folder, else the exe's folder. */
private fun hostPath(context: android.content.Context, game: AddedGames.Game): String {
    val folderGuest = AddedGames.guestPath(context, game.folder) ?: return game.exe.parentFile?.path.orEmpty()
    return when {
        game.guestDir == folderGuest -> game.folder.path
        game.guestDir.startsWith("$folderGuest/") -> game.folder.path + game.guestDir.removePrefix(folderGuest)
        else -> game.exe.parentFile?.path.orEmpty()
    }
}

@Composable
private fun ValueText(text: String, muted: Boolean = false) {
    Text(
        text, fontSize = 14.sp, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** "Remove" in red; pressed once it asks, a second press within four seconds removes. */
@Composable
private fun RemoveRow(armed: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape12)
            .background(if (armed) colors.error.copy(alpha = 0.18f) else if (hot) colors.error.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .glideBorder(hot, Shape12, colors.error, colors.error.copy(alpha = 0.4f))
            .hoverable(src)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            stringResource(if (armed) R.string.mode_added_remove_confirm else R.string.edit_remove),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error,
        )
    }
}

/** One line of text in a compact dialog: [title] and a red X, the field, OK. */
@Composable
internal fun TextEntryDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithinFrames({ false }) { focus } }
    AppDialog(shown, close, "textEntry", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(title, onClose = close)
        androidx.compose.material3.OutlinedTextField(
            value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSave(value.trim()); close() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SecondaryButton(stringResource(R.string.common_ok), compact = true) { onSave(value.trim()); close() }
        }
    }
}

/**
 * The art chooser for one [slot]: back, the slot's name and a red X; the images grouped by where
 * they come from (the game's folder, Steam, SteamGridDB, fetched as it opens), the current one
 * outlined, a tap applies it; "Pick image…" and "Reset to automatic".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtChooserDialog(
    game: AddedGames.Game, slot: AddedGameArt.Slot, current: AddedGameArt.SlotState?,
    onBack: () -> Unit, onCloseAll: () -> Unit, onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val shown = rememberShown(onBack)
    val back = { shown.targetState = false }
    var options by remember { mutableStateOf<List<AddedGameArt.Option>?>(null) }
    var picking by remember { mutableStateOf(false) }
    val chosenRef = remember(current) { AddedGameArt.chosenSource(app, game, slot).second }
    LaunchedEffect(slot) { options = withContext(Dispatchers.IO) { runCatching { AddedGameArt.options(app, game, slot) }.getOrDefault(emptyList()) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithinFrames({ false }) { first } }
    val apply: (AddedGameArt.Option) -> Unit = { option ->
        scope.launch {
            withContext(Dispatchers.IO) { AddedGameEdits.setArt(app, game.folder.path, slot, option) }
            onChanged()
            back()
        }
    }
    val slotName = slot.label()
    AppDialog(shown, back, "artChooser", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(slotName, onClose = { onCloseAll() }, leading = {
            SmallIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), colors.onBackground, onClick = back)
        })
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        ) {
            val opts = options
            if (opts == null) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            } else {
                var firstGiven = false
                for (group in listOf(AddedGameArt.FOLDER, AddedGameArt.STEAM, AddedGameArt.SGDB)) {
                    val inGroup = opts.filter { it.group == group }
                    if (inGroup.isEmpty()) continue
                    Text(sourceLabel(group), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in inGroup) key(option.full) {
                            val focus = if (!firstGiven) first.also { firstGiven = true } else null
                            ArtOption(slot, option, selected = current?.chosen == true && option.full == chosenRef, focus = focus, description = "${sourceLabel(group)} $slotName") { apply(option) }
                        }
                    }
                }
            }
            PickerRow(Icons.Outlined.Image, stringResource(R.string.art_pick_image), null, focus = if (opts?.isEmpty() != false) first else null) { picking = true }
            PickerRow(null, stringResource(R.string.art_reset), null) {
                scope.launch {
                    withContext(Dispatchers.IO) { AddedGameEdits.resetArt(app, game.folder.path, slot) }
                    onChanged()
                    back()
                }
            }
        }
    }
    if (picking) ExePickerDialog(stringResource(R.string.art_pick_title, slotName), kind = PickKind.IMAGE, onDismiss = { picking = false }, onPick = { file ->
        apply(AddedGameArt.Option(AddedGameArt.FILE, file.path, file.path))
    })
}

@Composable
private fun ArtOption(slot: AddedGameArt.Slot, option: AddedGameArt.Option, selected: Boolean, focus: FocusRequester?, description: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hot = rememberHot(src)
    val scale = when (slot) { AddedGameArt.Slot.COVER -> 1.9f; AddedGameArt.Slot.ICON -> 1.6f; else -> 1.5f }
    Box(
        Modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier).clip(RoundedCornerShape(8.dp))
            .glideBorder(hot, RoundedCornerShape(8.dp), pal.signal)
            .hoverable(src)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(2.dp),
    ) {
        SlotThumb(slot, if (option.thumb.startsWith("https://")) option.thumb else File(option.thumb), scale, selected)
    }
}

/**
 * After "Add all games in this folder": how many were added and how many were there already, then
 * one row per added game (cover, name, exe, "?" where unsure); a row opens that game's editor.
 * [folders] are the added games' folders; the rows follow edits and removals.
 */
@Composable
internal fun AddedGamesSummaryDialog(folders: List<String>, already: Int, onOpen: (folder: String, uncertain: Boolean) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val colors = MaterialTheme.colorScheme
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var rows by remember { mutableStateOf<List<Pair<AddedGames.Game, File?>>?>(null) }
    LaunchedEffect(folders) {
        rows = withContext(Dispatchers.IO) {
            val games = AddedGames.scan(app).associateBy { it.folder.path }
            folders.mapNotNull { f -> games[f]?.let { g -> g to AddedGameArt.resolve(app, g).portrait } }
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(rows != null) { if (rows != null) focusWithinFrames({ false }) { first } }
    val list = rows ?: emptyList()
    AppDialog(shown, close, "addedSummary", wide = false, maxWidth = 440.dp) {
        DialogTitleRow(
            if (rows != null && list.isEmpty()) stringResource(R.string.games_added_none) else pluralStringResource(R.plurals.games_added_n, list.size, list.size),
            onClose = close,
        )
        if (already > 0) Text(pluralStringResource(R.plurals.games_already_n, already, already), fontSize = 12.sp, color = colors.onSurfaceVariant)
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        ) {
            list.forEachIndexed { i, (g, cover) ->
                key(g.folder.path) {
                    ListRow(focus = if (i == 0) first else null, onClick = { onOpen(g.folder.path, g.exeUncertain); close() }) {
                        SlotThumb(AddedGameArt.Slot.COVER, cover, 0.9f)
                        Column(Modifier.weight(1f)) {
                            Text(g.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(g.exe.name, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (g.exeUncertain) UncertainMark(stringResource(R.string.mode_added_exe_check))
                    }
                }
            }
        }
    }
}
