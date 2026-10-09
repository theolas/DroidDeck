package com.droiddeck.launcher.ui

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.stores.formatBytes
import java.io.File

/** A place an exe can be picked from: internal storage or a card, at its root. */
internal class ExeVolume(val root: File, val label: String, val removable: Boolean) {
    val free: Long get() = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)
}

internal fun exeVolumes(context: Context): List<ExeVolume> =
    listOf(ExeVolume(Environment.getExternalStorageDirectory(), context.getString(R.string.games_internal), removable = false)) +
        GameStorage.options(context).mapNotNull { o ->
            File(o.path.substringBefore("/Android/")).takeIf { it.isDirectory }?.let { ExeVolume(it, o.name, removable = true) }
        }.distinctBy { it.root.absolutePath }

/** A folder's subfolders, then its .exe files, by name; hidden ones left out. */
internal fun exeListing(dir: File): Pair<List<File>, List<File>> {
    val all = dir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty().sortedBy { it.name.lowercase() }
    return all.filter { it.isDirectory } to all.filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
}

/**
 * The compact .exe picker: [title] and Cancel on one line, then the storage rows; inside a storage
 * a breadcrumb, "..", folders and .exe files (name and size), scrolling in the dialog. B or Back
 * goes up a folder, and closes from the storage rows. [start] opens in a folder instead.
 */
@Composable
internal fun ExePickerDialog(title: String, start: File? = null, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val volumes = remember { exeVolumes(ctx) }
    var dir by remember { mutableStateOf(start?.takeIf { s -> s.isDirectory && volumes.any { s.path.startsWith(it.root.path) } }) }
    val volume = dir?.let { d -> volumes.filter { d.path == it.root.path || d.path.startsWith(it.root.path + "/") }.maxByOrNull { it.root.path.length } }
    val up = { dir = dir?.let { d -> if (volume == null || d.path == volume.root.path) null else d.parentFile } }
    val back = { if (dir == null) close() else up() }
    val first = remember(dir) { FocusRequester() }
    LaunchedEffect(dir) { focusWithinFrames({ false }) { first } }
    val listing = remember(dir) { dir?.let { exeListing(it) } }
    AppDialog(shown, back, "exePicker", wide = false, maxWidth = 440.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, fontSize = 15.sp, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.common_cancel), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant,
                    modifier = Modifier.clip(Shape12).clickable(role = Role.Button, onClick = close).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (dir != null && volume != null) {
                val crumbs = listOf(volume.label) + dir!!.path.removePrefix(volume.root.path).split('/').filter { it.isNotEmpty() }
                Text(crumbs.joinToString(" › "), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(top = 4.dp),
            ) {
                if (dir == null) {
                    volumes.forEachIndexed { i, v ->
                        key(v.root.path) {
                            PickerRow(if (v.removable) Icons.Outlined.SdCard else Icons.Outlined.Smartphone, v.label,
                                stringResource(R.string.stores_target_free, formatBytes(v.free)), tall = true, focus = if (i == 0) first else null) { dir = v.root }
                        }
                    }
                } else {
                    PickerRow(Icons.AutoMirrored.Outlined.ArrowBack, "..", null, focus = first, onClick = up)
                    val (folders, exes) = listing ?: (emptyList<File>() to emptyList())
                    for (f in folders) key(f.path) { PickerRow(Icons.Outlined.Folder, f.name, null) { dir = f } }
                    for (f in exes) key(f.path) {
                        PickerRow(Icons.Outlined.InsertDriveFile, f.name, formatBytes(f.length()), exe = true) { onPick(f); close() }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    icon: ImageVector, name: String, detail: String?, tall: Boolean = false, exe: Boolean = false,
    focus: FocusRequester? = null, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier).clip(Shape12)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else colors.surface.copy(alpha = 0.6f))
            .glideBorder(hot, Shape12, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (tall) 12.dp else 9.dp),
    ) {
        Icon(icon, null, tint = if (hot || exe) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(name, fontSize = 14.sp, fontWeight = if (tall) FontWeight.SemiBold else FontWeight.Normal, color = colors.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}
