package io.github.camelbaggy.livresaudio.e1

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.camelbaggy.livresaudio.core.drive.auth.AccountStore
import io.github.camelbaggy.livresaudio.core.drive.auth.ActivityAuthUi
import io.github.camelbaggy.livresaudio.core.drive.rest.APP_DATA_FOLDER
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveFile
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveRestClient
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.drive.rest.FOLDER_MIME_TYPE
import io.github.camelbaggy.livresaudio.core.drive.rest.describe
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogLine(val time: String, val ok: Boolean, val text: String)

/** Single UI state of the E1 trial screen. */
data class E1UiState(
    val accounts: List<ConnectedAccount> = emptyList(),
    val busy: Boolean = false,
    val log: List<LogLine> = emptyList()
)

private const val TEST_FILE = "essai-e1.json"
private const val TEST_FOLDER = ".livres-audio-essai"
private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

@HiltViewModel
class E1ViewModel @Inject constructor(
    private val auth: DriveAuthProvider,
    private val drive: DriveRestClient,
    store: AccountStore
) : ViewModel() {
    private val local = MutableStateFlow(E1UiState())

    val state: StateFlow<E1UiState> = combine(local, store.accounts) { l, accounts -> l.copy(accounts = accounts) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), E1UiState())

    fun addAccount(ui: ActivityAuthUi, role: AccountRole) = run("Ajout du compte (${roleLabel(role)})") {
        when (val result = auth.addAccount(ui, role)) {
            is AuthResult.Success ->
                ok("Compte ajouté : ${result.value.email} (${roleLabel(result.value.role)})")

            AuthResult.Cancelled -> fail("Ajout annulé")

            AuthResult.NeedsUserAction -> fail("Action requise : consentement à refaire")

            AuthResult.NetworkUnavailable -> fail("Réseau indisponible")

            is AuthResult.Failure -> fail("Échec : ${result.message}")
        }
    }

    fun removeAccount(account: ConnectedAccount) = run("Retrait de ${account.email}") {
        auth.removeAccount(account.id)
        ok("Compte retiré (les autorisations Google restent accordées)")
    }

    fun listRoot(account: ConnectedAccount) = run("Lister la racine (${account.email})") {
        val result = drive.listFiles(account.id, "'root' in parents and trashed=false", pageSize = 10)
        when (result) {
            is DriveResult.Failure -> fail(result.error.describe())

            is DriveResult.Success -> {
                val names = result.value.files.joinToString(", ") { it.name ?: "?" }
                ok("${result.value.files.size} élément(s) : ${names.ifEmpty { "(vide)" }}")
            }
        }
    }

    fun silentToken(account: ConnectedAccount) = run("Jeton silencieux (${account.email})") {
        when (val result = auth.getAccessToken(account.id)) {
            is AuthResult.Success -> ok("Jeton obtenu sans interaction")
            AuthResult.NeedsUserAction -> fail("Consentement à refaire : action de l'utilisateur requise")
            AuthResult.Cancelled -> fail("Annulé")
            AuthResult.NetworkUnavailable -> fail("Réseau indisponible (pas une perte d'autorisation)")
            is AuthResult.Failure -> fail("Échec : ${result.message}")
        }
    }

    fun writeAppData(account: ConnectedAccount) = run("Écrire appDataFolder (${account.email})") {
        val found = drive.listFiles(account.id, "name='$TEST_FILE'", spaces = APP_DATA_FOLDER)
        val existing = when (found) {
            is DriveResult.Failure -> return@run fail("Recherche : ${found.error.describe()}")
            is DriveResult.Success -> found.value.files.firstOrNull()
        }
        writeAndReadBack(account.id, existing, APP_DATA_FOLDER, "appDataFolder")
    }

    fun writeDriveFile(account: ConnectedAccount) = run("Écrire drive.file (${account.email})") {
        val id = account.id
        // Test (c): what does this phone see of what the other phone created?
        val anywhere = drive.listAllFiles(id, "name='$TEST_FILE' and trashed=false")
        if (anywhere is DriveResult.Success) {
            val lines = anywhere.value.joinToString("; ") { "${it.id.take(6)}… modifié ${it.modifiedTime}" }
            ok("Recherche globale de $TEST_FILE : ${anywhere.value.size} résultat(s) ${lines.ifEmpty { "" }}")
        } else if (anywhere is DriveResult.Failure) {
            fail("Recherche globale : ${anywhere.error.describe()}")
        }
        val folders = drive.listAllFiles(
            id,
            "name='$TEST_FOLDER' and mimeType='$FOLDER_MIME_TYPE' and trashed=false"
        )
        val foundFolder = when (folders) {
            is DriveResult.Failure -> return@run fail("Recherche du dossier : ${folders.error.describe()}")
            is DriveResult.Success -> folders.value.firstOrNull()
        }
        val folderId: String
        if (foundFolder != null) {
            folderId = foundFolder.id
            ok("Dossier $TEST_FOLDER existant trouvé")
        } else {
            ok("Dossier $TEST_FOLDER absent : création (si l'autre téléphone l'a déjà créé, c'est un échec du test c)")
            folderId = when (val created = drive.createFolder(id, TEST_FOLDER, parentId = null)) {
                is DriveResult.Failure -> return@run fail("Création du dossier : ${created.error.describe()}")
                is DriveResult.Success -> created.value.id
            }
        }
        val inFolder = drive.listFiles(id, "name='$TEST_FILE' and '$folderId' in parents and trashed=false")
        val existing = when (inFolder) {
            is DriveResult.Failure -> return@run fail("Recherche du fichier : ${inFolder.error.describe()}")
            is DriveResult.Success -> inFolder.value.files.firstOrNull()
        }
        writeAndReadBack(id, existing, folderId, "drive.file")
    }

    fun clearLog() = local.update { it.copy(log = emptyList()) }

    private suspend fun writeAndReadBack(accountId: AccountId, existing: DriveFile?, parentId: String, label: String) {
        val content = """{"horodatage":"${Instant.now()}","telephone":"${Build.MANUFACTURER} ${Build.MODEL}"}"""
        val written = if (existing == null) {
            drive.createJsonFile(accountId, TEST_FILE, parentId, content)
        } else {
            drive.updateFileContent(accountId, existing.id, content)
        }
        val verb = if (existing == null) "créé" else "mis à jour"
        val file = when (written) {
            is DriveResult.Failure -> return fail("Écriture $label : ${written.error.describe()}")
            is DriveResult.Success -> written.value
        }
        ok("Fichier $verb ($label)")
        val meta = drive.getFile(accountId, file.id)
        val text = drive.downloadText(accountId, file.id)
        when (text) {
            is DriveResult.Failure -> fail("Relecture : ${text.error.describe()}")
            is DriveResult.Success -> ok("Relu : ${text.value}")
        }
        if (meta is DriveResult.Success) {
            val user = meta.value.lastModifyingUser
            ok(
                "Dernière modification : ${meta.value.modifiedTime} par ${user?.displayName ?: "?"} <${user?.emailAddress ?: "?"}>"
            )
        } else if (meta is DriveResult.Failure) {
            fail("Métadonnées : ${meta.error.describe()}")
        }
    }

    private fun run(title: String, block: suspend () -> Unit) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        log(true, "> $title")
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(false, "Exception inattendue : ${e.javaClass.simpleName}")
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    private fun ok(text: String) = log(true, text)

    private fun fail(text: String) = log(false, text)

    private fun log(ok: Boolean, text: String) {
        val line = LogLine(TIME_FORMAT.format(Instant.now()), ok, text)
        local.update { it.copy(log = it.log + line) }
    }

    private fun roleLabel(role: AccountRole) = if (role == AccountRole.SHARED) "commun" else "personnel"
}

/** Text of the journal for the clipboard. */
fun List<LogLine>.asText(): String = joinToString("\n") { "${it.time} ${if (it.ok) "OK " else "KO "} ${it.text}" }
