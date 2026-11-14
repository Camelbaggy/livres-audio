package io.github.camelbaggy.livresaudio.onboarding

import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val tree = mapOf(
        "root-id" to listOf(FolderEntry("f10", "Livres 10"), FolderEntry("f2", "Livres 2"), FolderEntry("fa", "Audio")),
        "fa" to listOf(FolderEntry("fa1", "Romans"))
    )
    private lateinit var store: FakeAccountStore
    private lateinit var auth: FakeAuth
    private lateinit var repo: FakeAccountRepository
    private lateinit var browser: FakeFolderBrowser

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = FakeAccountStore()
        auth = FakeAuth(store)
        repo = FakeAccountRepository()
        browser = FakeFolderBrowser(tree)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(): OnboardingViewModel {
        val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
        return OnboardingViewModel(auth, store, repo, browser, clock)
    }

    @Test
    fun startsAtWelcomeThenRoleChoice() {
        val vm = vm()
        assertEquals(OnboardingStep.Welcome, vm.state.value.step)
        vm.start()
        assertEquals(OnboardingStep.ChooseRole, vm.state.value.step)
        assertTrue(vm.state.value.sharedRoleAvailable)
    }

    @Test
    fun startsAtSummaryWhenAccountsAlreadyExist() {
        store = FakeAccountStore(listOf(account("x@example.org", AccountRole.PERSONAL)))
        auth = FakeAuth(store)
        assertEquals(OnboardingStep.Summary, vm().state.value.step)
    }

    @Test
    fun connectingSharedAccountOpensFolders() {
        val vm = vm()
        vm.start()
        vm.chooseRole(AccountRole.SHARED, NoUi)
        val s = vm.state.value
        assertEquals(OnboardingStep.ChooseFolders, s.step)
        assertEquals(AccountRole.SHARED, s.accounts.single().role)
        assertFalse(s.sharedRoleAvailable)
        // Listing as returned by the browser (sorting is the browser's job, see FolderBrowserTest).
        assertEquals(listOf("Livres 10", "Livres 2", "Audio"), s.browser.folders.map { it.name })
        assertEquals(listOf("Mon Drive"), s.browser.path.map { it.name })
    }

    @Test
    fun secondSharedAccountIsRefusedWithoutCallingAuth() {
        val vm = vm()
        vm.chooseRole(AccountRole.SHARED, NoUi)
        vm.addAnotherAccount()
        vm.chooseRole(AccountRole.SHARED, NoUi)
        assertEquals(1, auth.calls)
        assertEquals(ConnectError.SharedAlreadyConnected, vm.state.value.error)
        assertEquals(OnboardingStep.ChooseRole, vm.state.value.step)
        // A personal account is still accepted.
        auth.nextEmail = "p@example.org"
        vm.chooseRole(AccountRole.PERSONAL, NoUi)
        assertEquals(2, vm.state.value.accounts.size)
        assertNull(vm.state.value.error)
    }

    @Test
    fun authOutcomesReturnToRoleChoiceWithMatchingError() {
        val cases = listOf(
            AuthResult.Cancelled to ConnectError.Cancelled,
            AuthResult.NeedsUserAction to ConnectError.NeedsUserAction,
            AuthResult.NetworkUnavailable to ConnectError.NetworkUnavailable,
            AuthResult.Failure("boom") to ConnectError.Failure("boom")
        )
        for ((result, expected) in cases) {
            auth.next = result
            val vm = vm()
            vm.start()
            vm.chooseRole(AccountRole.PERSONAL, NoUi)
            assertEquals(OnboardingStep.ChooseRole, vm.state.value.step, "step for $result")
            assertEquals(expected, vm.state.value.error)
            assertTrue(vm.state.value.accounts.isEmpty())
        }
    }

    @Test
    fun breadcrumbNavigation() {
        val vm = vm()
        vm.chooseRole(AccountRole.PERSONAL, NoUi)
        vm.openFolder(FolderEntry("fa", "Audio"))
        assertEquals(listOf("Mon Drive", "Audio"), vm.state.value.browser.path.map { it.name })
        assertEquals(listOf("Romans"), vm.state.value.browser.folders.map { it.name })
        vm.openFolder(FolderEntry("fa1", "Romans"))
        assertTrue(vm.state.value.browser.folders.isEmpty())
        vm.goToCrumb(0)
        assertEquals(listOf("Mon Drive"), vm.state.value.browser.path.map { it.name })
        assertEquals(3, vm.state.value.browser.folders.size)
        vm.openFolder(FolderEntry("fa", "Audio"))
        assertTrue(vm.up())
        assertEquals("root-id", vm.state.value.browser.current?.id)
        assertFalse(vm.up())
    }

    @Test
    fun addAndRemoveRoots() {
        val vm = vm()
        vm.chooseRole(AccountRole.PERSONAL, NoUi)
        vm.openFolder(FolderEntry("fa", "Audio"))
        assertFalse(vm.state.value.currentFolderChosen)
        vm.chooseCurrentFolder()
        assertTrue(vm.state.value.currentFolderChosen)
        vm.chooseCurrentFolder() // no duplicate
        vm.goToCrumb(0)
        vm.chooseCurrentFolder()
        assertEquals(listOf("Audio", "Mon Drive"), vm.state.value.roots.map { it.name })
        assertEquals("a@example.org", vm.state.value.roots.first().accountEmail)
        vm.removeRoot(vm.state.value.roots.first().id)
        assertEquals(listOf("Mon Drive"), vm.state.value.roots.map { it.name })
    }

    @Test
    fun finishNeedsAtLeastOneRoot() {
        val vm = vm()
        vm.chooseRole(AccountRole.PERSONAL, NoUi)
        vm.showSummary()
        vm.finish()
        assertFalse(vm.state.value.finished)
        vm.browseAccount(vm.state.value.accounts.single().id)
        vm.chooseCurrentFolder()
        vm.showSummary()
        vm.finish()
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun driveErrorIsShownAndRetryWorks() {
        browser.failWith = FolderError.NetworkUnavailable
        val vm = vm()
        vm.chooseRole(AccountRole.PERSONAL, NoUi)
        assertEquals(FolderError.NetworkUnavailable, vm.state.value.browser.error)
        browser.failWith = null
        vm.retry()
        assertNull(vm.state.value.browser.error)
        assertEquals(3, vm.state.value.browser.folders.size)
    }

    @Test
    fun backNavigation() {
        val vm = vm()
        assertFalse(vm.back()) // Welcome
        vm.start()
        assertTrue(vm.back())
        assertEquals(OnboardingStep.Welcome, vm.state.value.step)
        vm.start()
        vm.chooseRole(AccountRole.PERSONAL, NoUi)
        vm.openFolder(FolderEntry("fa", "Audio"))
        assertTrue(vm.back())
        assertEquals(OnboardingStep.ChooseFolders, vm.state.value.step)
        assertTrue(vm.back()) // at root: summary
        assertEquals(OnboardingStep.Summary, vm.state.value.step)
        vm.addAnotherAccount()
        assertTrue(vm.back())
        assertEquals(OnboardingStep.Summary, vm.state.value.step)
        assertIs<OnboardingStep>(vm.state.value.step)
    }
}
