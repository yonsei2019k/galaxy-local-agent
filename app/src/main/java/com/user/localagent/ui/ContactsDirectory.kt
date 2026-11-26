package com.user.localagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.Contacts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role

private data class DirectoryContact(val uri: Uri, val name: String)

@Composable
internal fun ContactsDirectory(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun canRead() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(canRead()) }
    var revision by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            granted = canRead()
            revision++
            awaitCancellation()
        }
    }
    fun openSystemContact(intent: Intent) {
        status = try {
            context.startActivity(intent)
            null
        } catch (_: android.content.ActivityNotFoundException) {
            "이 휴대폰에서 연락처 앱을 열 수 없어요."
        } catch (_: SecurityException) {
            "연락처 앱을 여는 권한을 확인해 주세요."
        }
    }
    val contacts by produceState<Result<List<DirectoryContact>>?>(null, granted, query.trim(), revision) {
        value = null
        if (!granted) return@produceState
        delay(180)
        value = withContext(Dispatchers.IO) {
            val term = query.trim()
            val source = if (term.isBlank()) Contacts.CONTENT_URI
                         else Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(term))
            try {
                val columns = arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY)
                val rows = context.contentResolver.query(source, columns, null, null, Contacts.SORT_KEY_PRIMARY)?.use { cursor ->
                    buildList {
                        while (size < 80 && cursor.moveToNext()) {
                            val label = cursor.getString(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                            val key = cursor.getString(1) ?: continue
                            add(DirectoryContact(Contacts.getLookupUri(cursor.getLong(0), key), label))
                        }
                    }
                }
                if (rows == null) Result.failure(IllegalStateException("Contacts provider unavailable"))
                else Result.success(rows)
            } catch (error: SecurityException) {
                Result.failure(error)
            } catch (error: IllegalArgumentException) {
                Result.failure(error)
            }
        }
    }
    Column(modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { openSystemContact(Intent(Intent.ACTION_INSERT, Contacts.CONTENT_URI)) }) {
                ZipIcon(com.user.localagent.runtime.R.drawable.zi_plus, null, tint = ZipHapTokens.CobaltText, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                Text("연락처 추가", color = ZipHapTokens.CobaltText)
            }
        }
        status?.let { ZipHapStatusTag(it, MaterialTheme.colorScheme.onErrorContainer, MaterialTheme.colorScheme.errorContainer) }
        if (!granted) {
            ZipEmptyState(com.user.localagent.runtime.R.drawable.zi_ill_contacts, "휴대폰 연락처 연결", "읽기를 허용하면 이름을 검색할 수 있어요.") {
                ZipPrimaryButton("연락처 읽기 허용", { permission.launch(Manifest.permission.READ_CONTACTS) }, fullWidth = false)
            }
        } else {
            ZipOutlinedTextField(
                value = query, onValueChange = { query = it.take(80) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("이름으로 검색") }, singleLine = true,
                leadingIcon = { ZipIcon(com.user.localagent.runtime.R.drawable.zi_search, null, tint = ZipHapColors.TextSecondary, size = 22.dp) },
            )
            val result = contacts
            val rows = result?.getOrNull().orEmpty()
            when {
                result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                result.isFailure -> ZipEmptyState(com.user.localagent.runtime.R.drawable.zi_ill_error, "연락처 목록을 불러오지 못했어요.", "")
                rows.isEmpty() -> ZipEmptyState(com.user.localagent.runtime.R.drawable.zi_ill_contacts,
                    if (query.isBlank()) "저장된 연락처가 없어요." else "일치하는 이름이 없어요.", "")
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (rows.size == 80) item { ZipHapStatusTag("80명까지 표시해요. 이름으로 더 좁힐 수 있어요."); Spacer(Modifier.height(10.dp)) }
                    itemsIndexed(rows, key = { _, contact -> contact.uri.toString() }) { index, contact ->
                        Column(
                            Modifier.fillMaxWidth().zipGroupItem(index, rows.size)
                                .clickable(role = Role.Button) { openSystemContact(Intent(Intent.ACTION_VIEW, contact.uri)) },
                        ) {
                            if (index > 0) ZipInsetDivider()
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(shape = RoundedCornerShape(ZipHapTokens.RadiusTile), color = ZipTone.entries[index % 5].soft,
                                    contentColor = ZipTone.entries[index % 5].content) {
                                    Box(Modifier.size(40.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                                        Text(contact.name.take(Character.charCount(Character.codePointAt(contact.name, 0))),
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                                Text(contact.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
