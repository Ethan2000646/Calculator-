package com.calcanator.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Screen { Setup, Auth, Home, Dev }

enum class Tab { Calc, Casino, Nfts, Board }

data class Ui(
    val screen: Screen = Screen.Setup,
    val tab: Tab = Tab.Calc,
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val demo: Boolean = false,
    val backendUrl: String = "",
    val me: Profile? = null,
    val settings: AppSettings = AppSettings(),
    val users: List<Profile> = emptyList(),
    val items: List<NftItem> = emptyList(),
    val owned: Map<Int, Int> = emptyMap(),
    val tiers: List<SpinTier> = emptyList(),
    val board: List<LeaderRow> = emptyList(),
    val calcAmount: String = "",
    val preview: Preview? = null,
    val bet: String = "",
    val game: String = "flip",
    val pick: String = "heads",
    val spin: SpinResult? = null,
    val jackpot: JackpotResult? = null,
)

class AppState(context: Context) {
    private val prefs = context.getSharedPreferences("calcanator", Context.MODE_PRIVATE)
    private val session = SessionStore(prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var ui by mutableStateOf(Ui())
        private set

    private var backend: Backend? = null

    fun bootstrap() {
        val demo = prefs.getBoolean("demo", false)
        val url = prefs.getString("url", "").orEmpty()
        val key = prefs.getString("key", "").orEmpty()
        ui = ui.copy(demo = demo, backendUrl = url)
        backend = when {
            demo -> DemoBackend(prefs)
            url.isNotBlank() && key.isNotBlank() -> SupabaseBackend(url, key, session)
            else -> null
        }
        val b = backend ?: return
        if (b.hasSession()) refresh() else ui = ui.copy(screen = Screen.Auth)
    }

    fun saveBackend(url: String, key: String, demo: Boolean) {
        prefs.edit()
            .putString("url", url.trim())
            .putString("key", key.trim())
            .putBoolean("demo", demo)
            .commit()
        session.clear()
        backend = null
        ui = Ui(demo = demo, backendUrl = if (demo) "" else url.trim())
        bootstrap()
    }

    private fun run(doing: String, block: suspend (Backend) -> Unit) {
        val b = backend ?: return
        if (ui.busy) return
        ui = ui.copy(busy = true, error = null, info = doing)
        scope.launch {
            try {
                block(b)
                ui = if (ui.info == doing) ui.copy(busy = false, info = null) else ui.copy(busy = false)
            } catch (e: Exception) {
                ui = ui.copy(busy = false, info = null, error = e.message ?: e.toString())
            }
        }
    }

    private suspend fun loadEverything(b: Backend) {
        val me = b.me()
        val settings = b.loadSettings()
        val items = b.items()
        val owned = b.owned()
        val tiers = b.tiers()
        val board = b.leaderboard()
        val users = if (me.isAdmin) b.listUsers() else emptyList()
        ui = ui.copy(
            screen = Screen.Home,
            me = me,
            settings = settings,
            items = items,
            owned = owned,
            tiers = tiers,
            board = board,
            users = users,
        )
    }

    fun signIn(email: String, password: String) = run("Signing in…") { b ->
        b.signIn(email.trim(), password)
        loadEverything(b)
    }

    fun signUp(email: String, password: String) = run("Creating your account…") { b ->
        b.signUp(email.trim(), password)
        loadEverything(b)
    }

    fun refresh() = run("Refreshing…") { b -> loadEverything(b) }

    fun selectTab(tab: Tab) {
        ui = ui.copy(tab = tab, error = null, info = null)
        if (tab != Tab.Calc) refresh()
    }

    fun setCalcAmount(value: String) {
        ui = ui.copy(calcAmount = value, preview = null)
    }

    fun calculate() {
        val amount = ui.calcAmount.trim().replace(",", ".").toDoubleOrNull()
        if (amount == null) {
            report("Type a number first.")
            return
        }
        run("Calculating…") { b -> ui = ui.copy(preview = b.preview(amount)) }
    }

    fun setBet(value: String) {
        ui = ui.copy(bet = value)
    }

    fun setGame(game: String) {
        ui = ui.copy(
            game = game,
            pick = if (game == "flip") "heads" else "1",
            spin = null,
            jackpot = null,
        )
    }

    fun setPick(pick: String) {
        ui = ui.copy(pick = pick)
    }

    fun placeBet() {
        val amount = ui.bet.trim().toLongOrNull()
        if (amount == null || amount <= 0) {
            report("Bet a whole number of coins.")
            return
        }
        run("Rolling…") { b ->
            val result = b.bet(amount, ui.game, ui.pick)
            val me = b.me()
            ui = ui.copy(spin = result, me = me, jackpot = null)
        }
    }

    fun spinJackpot() {
        val amount = ui.bet.trim().toLongOrNull()
        if (amount == null || amount <= 0) {
            report("Bet a whole number of coins.")
            return
        }
        run("Spinning…") { b ->
            val result = b.spinJackpot(amount)
            val me = b.me()
            ui = ui.copy(jackpot = result, me = me, spin = null)
        }
    }

    fun buy(itemId: Int) = run("Buying…") { b ->
        b.buy(itemId, 1)
        loadEverything(b)
    }

    fun sell(itemId: Int) = run("Selling…") { b ->
        b.sell(itemId, 1)
        loadEverything(b)
    }

    fun claimYield() = run("Claiming…") { b ->
        val earned = b.claimYield()
        loadEverything(b)
        ui = ui.copy(info = "Claimed $earned calc coins.")
    }

    fun openDev() = run("Opening dev panel…") { b ->
        val me = b.me()
        if (!me.isAdmin) throw ApiException("Admins only.")
        ui = ui.copy(
            screen = Screen.Dev,
            me = me,
            users = b.listUsers(),
            items = b.items(),
            tiers = b.tiers(),
            settings = b.loadSettings(),
        )
    }

    fun backToHome() {
        ui = ui.copy(screen = Screen.Home, error = null, info = null)
    }

    fun grant(userId: String, amount: Long) = run("Giving coins…") { b ->
        b.grant(userId, amount)
        ui = ui.copy(users = b.listUsers(), me = b.me())
    }

    fun ban(userId: String, banned: Boolean) = run(if (banned) "Banning…" else "Unbanning…") { b ->
        b.ban(userId, banned)
        ui = ui.copy(users = b.listUsers())
    }

    fun saveSettings(settings: AppSettings) = run("Saving…") { b ->
        b.saveSettings(settings)
        ui = ui.copy(settings = b.loadSettings())
    }

    fun updateNft(item: NftItem) = run("Saving NFT…") { b ->
        b.updateNft(item)
        ui = ui.copy(items = b.items())
    }

    fun updateTier(tier: SpinTier) = run("Saving paytable…") { b ->
        b.updateTier(tier)
        ui = ui.copy(tiers = b.tiers())
    }

    fun createNft(name: String, price: Long, yieldPerDay: Long, supply: Int) = run("Minting…") { b ->
        b.createNft(name, price, yieldPerDay, supply)
        ui = ui.copy(items = b.items(), info = "Created $name.")
    }

    fun openSetup() {
        ui = ui.copy(screen = Screen.Setup, error = null, info = null)
    }

    fun report(message: String) {
        ui = ui.copy(error = message, info = null)
    }

    fun signOut() {
        val b = backend ?: return
        scope.launch {
            try {
                b.signOut()
            } catch (_: Exception) {
            }
            ui = ui.copy(
                screen = Screen.Auth,
                me = null,
                users = emptyList(),
                items = emptyList(),
                owned = emptyMap(),
                preview = null,
                spin = null,
                error = null,
                info = null,
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = AppState(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root(state)
                }
            }
        }
        state.bootstrap()
    }
}

@Composable
private fun Root(state: AppState) {
    val ui = state.ui
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Calcanator", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            if (ui.demo) "demo · this phone only" else "calc coins",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ui.me?.let { me ->
            Spacer(Modifier.height(8.dp))
            Text("${me.coins} calc coins", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                me.email + if (me.isAdmin) "  ·  dev" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))

        ui.error?.let { Banner(it, error = true) }
        ui.info?.let { Banner(it, error = false) }
        if (ui.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        when (ui.screen) {
            Screen.Setup -> SetupScreen(state, ui)
            Screen.Auth -> AuthScreen(state, ui)
            Screen.Home -> HomeScreen(state, ui)
            Screen.Dev -> DevScreen(state, ui)
        }

        Spacer(Modifier.height(18.dp))
        if (ui.screen == Screen.Home || ui.screen == Screen.Dev) {
            TextButton(onClick = { state.signOut() }, enabled = !ui.busy) { Text("Sign out") }
        }
    }
}

@Composable
private fun Banner(text: String, error: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text,
                fontSize = 13.sp,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SetupScreen(state: AppState, ui: Ui) {
    var url by remember { mutableStateOf(ui.backendUrl) }
    var key by remember { mutableStateOf("") }

    Text("Connect your backend", fontWeight = FontWeight.SemiBold)
    Text(
        "Paste your Supabase project URL and anon key. Or use demo mode, which keeps everything on this phone.",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Project URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("anon public key") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { state.saveBackend(url, key, false) },
        enabled = !ui.busy && url.isNotBlank() && key.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Connect") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { state.saveBackend("", "", true) },
        enabled = !ui.busy,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Try demo mode") }
}

@Composable
private fun AuthScreen(state: AppState, ui: Ui) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Text("Sign in", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { state.signIn(email, password) },
        enabled = !ui.busy && email.isNotBlank() && password.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Sign in") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { state.signUp(email, password) },
        enabled = !ui.busy && email.isNotBlank() && password.length >= 6,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Create account") }
    if (ui.demo) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Demo logins:\ndev@calcanator.app / dev1234 (dev)\nalex@calcanator.app / alex1234\nsam@calcanator.app / sam1234 (banned)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { state.openSetup() }) { Text("Change backend") }
}

@Composable
private fun HomeScreen(state: AppState, ui: Ui) {
    val me = ui.me ?: return

    if (me.banned) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Account banned", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text("The dev banned this account, so it cannot play for calc coins.", fontSize = 13.sp)
            }
        }
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val tabs = listOf(Tab.Calc, Tab.Casino, Tab.Nfts, Tab.Board)
        tabs.forEach { tab ->
            if (tab == ui.tab) {
                Button(onClick = { state.selectTab(tab) }, enabled = !ui.busy) { Text(tabName(tab)) }
            } else {
                OutlinedButton(onClick = { state.selectTab(tab) }, enabled = !ui.busy) { Text(tabName(tab)) }
            }
        }
    }
    Spacer(Modifier.height(14.dp))

    when (ui.tab) {
        Tab.Calc -> CalcScreen(state, ui)
        Tab.Casino -> CasinoScreen(state, ui)
        Tab.Nfts -> NftScreen(state, ui)
        Tab.Board -> BoardScreen(state, ui)
    }

    if (me.isAdmin) {
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { state.openDev() }, enabled = !ui.busy) { Text("Dev panel") }
    }
}

private fun tabName(tab: Tab): String = when (tab) {
    Tab.Calc -> "Calc"
    Tab.Casino -> "Casino"
    Tab.Nfts -> "NFTs"
    Tab.Board -> "Board"
}

@Composable
private fun CalcScreen(state: AppState, ui: Ui) {
    Text("Profit calculator", fontWeight = FontWeight.SemiBold)
    Text(
        "What a bet pays out at the current settings. Calculating never adds coins — those have to be earned.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "rate ×${fmt(ui.settings.calcRate)}   multiplier ×${fmt(ui.settings.calcMultiplier)}",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ui.calcAmount,
        onValueChange = { state.setCalcAmount(it) },
        label = { Text("Amount") },
        singleLine = true,
        enabled = !ui.busy,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { state.calculate() },
        enabled = !ui.busy && ui.calcAmount.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Calculate") }

    ui.preview?.let { preview ->
        Spacer(Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("payout ${preview.payout}", fontWeight = FontWeight.Bold)
                Text("profit ${preview.profit}", fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("Earn coins in the Casino, or from NFT yield.", fontSize = 12.sp)
}

@Composable
private fun CasinoScreen(state: AppState, ui: Ui) {
    Text("Casino", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        gameTab(state, ui, "flip", "Coin flip")
        gameTab(state, ui, "dice", "Dice")
        gameTab(state, ui, "jackpot", "Jackpot")
    }
    Spacer(Modifier.height(10.dp))

    if (ui.game == "jackpot") {
        Text(
            "Three reels, odds set by the paytable.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        val totalWeight = ui.tiers.sumOf { it.weight }
        ui.tiers.forEach { tier ->
            val chance = if (totalWeight > 0) tier.weight * 100.0 / totalWeight else 0.0
            Text(
                "${tier.label}  ·  ×${fmt(tier.payout)}  ·  ${(chance * 10).roundToInt() / 10.0}%",
                fontSize = 13.sp
            )
        }
    } else if (ui.game == "flip") {
        Text(
            "Win chance ${(ui.settings.winChance * 100).roundToInt()}%  ·  pays ×${fmt(ui.settings.payout)}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pickButton(selected = ui.pick == "heads", label = "Heads", enabled = !ui.busy) {
                state.setPick("heads")
            }
            pickButton(selected = ui.pick == "tails", label = "Tails", enabled = !ui.busy) {
                state.setPick("tails")
            }
        }
    } else {
        Text(
            "One number out of six  ·  pays ×${fmt(ui.settings.dicePayout)}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            (1..6).forEach { number ->
                val label = number.toString()
                val selected = ui.pick == label
                if (selected) {
                    Button(
                        onClick = { state.setPick(label) },
                        enabled = !ui.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = { state.setPick(label) },
                        enabled = !ui.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = ui.bet,
        onValueChange = { state.setBet(it) },
        label = { Text("Bet") },
        singleLine = true,
        enabled = !ui.busy,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { if (ui.game == "jackpot") state.spinJackpot() else state.placeBet() },
        enabled = !ui.busy && ui.bet.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (ui.game == "jackpot") "Spin" else "Bet") }

    ui.jackpot?.let { jackpot ->
        val reels = remember(jackpot) { reelsFor(jackpot.kind) }
        Spacer(Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(reels.joinToString("   "), fontSize = 26.sp)
                Text(
                    if (jackpot.prize > 0) "${jackpot.label}  +${jackpot.prize} calc coins"
                    else "${jackpot.label}  nothing this time",
                    fontWeight = FontWeight.Bold,
                    color = if (jackpot.prize > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Text("balance ${jackpot.coins}", fontSize = 13.sp)
            }
        }
    }

    ui.spin?.let { spin ->
        Spacer(Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (spin.win) "WIN  +${spin.prize} calc coins" else "LOST  ${ui.bet} calc coins",
                    fontWeight = FontWeight.Bold,
                    color = if (spin.win) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                if (ui.game == "dice") {
                    Text("dice rolled ${spin.roll}", fontSize = 13.sp)
                }
                Text("balance ${spin.coins}", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun pickButton(selected: Boolean, label: String, enabled: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}

@Composable
private fun gameTab(state: AppState, ui: Ui, game: String, label: String) {
    if (ui.game == game) {
        Button(onClick = { state.setGame(game) }, enabled = !ui.busy) { Text(label) }
    } else {
        OutlinedButton(onClick = { state.setGame(game) }, enabled = !ui.busy) { Text(label) }
    }
}

private fun reelsFor(kind: String): List<String> {
    val symbols = listOf("🍒", "🔔", "💎")
    return when (kind) {
        "jackpot" -> listOf("💎", "💎", "💎")
        "triple" -> {
            val symbol = symbols.random()
            listOf(symbol, symbol, symbol)
        }
        "pair" -> {
            val symbol = symbols.random()
            val other = symbols.first { it != symbol }
            val three = mutableListOf(symbol, symbol, other)
            three.shuffle()
            three
        }
        else -> symbols.shuffled()
    }
}

@Composable
private fun BoardScreen(state: AppState, ui: Ui) {
    Text("Leaderboard", fontWeight = FontWeight.SemiBold)
    Text(
        "Biggest calc coin holders. Banned accounts are hidden.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))

    if (ui.board.isEmpty()) {
        Text("Nobody on the board yet.", fontSize = 13.sp)
    }

    ui.board.forEachIndexed { index, row ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("${index + 1}.", fontSize = 13.sp)
                Text(row.handle, modifier = Modifier.weight(1f), fontSize = 15.sp)
                Text("${row.coins}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { state.refresh() }, enabled = !ui.busy) { Text("Refresh") }
}

@Composable
private fun NftScreen(state: AppState, ui: Ui) {
    Text("NFTs", fontWeight = FontWeight.SemiBold)
    Text(
        "Buy an NFT to earn calc coins every day. Yield can be claimed once every 24 hours. Selling back pays 90%.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { state.claimYield() },
        enabled = !ui.busy,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Claim daily yield") }
    Spacer(Modifier.height(12.dp))

    ui.items.forEach { item ->
        val qty = ui.owned[item.id] ?: 0
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(item.name, fontWeight = FontWeight.Medium)
                Text("price ${item.price}  ·  yield ${item.yieldPerDay}/day", fontSize = 13.sp)
                Text(
                    buildString {
                        append("left ${item.left} of ${item.maxSupply}")
                        if (qty > 0) append("  ·  you own $qty")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { state.buy(item.id) },
                        enabled = !ui.busy && !item.soldOut
                    ) { Text(if (item.soldOut) "Sold out" else "Buy 1") }
                    if (qty > 0) {
                        OutlinedButton(
                            onClick = { state.sell(item.id) },
                            enabled = !ui.busy
                        ) { Text("Sell 1") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DevScreen(state: AppState, ui: Ui) {
    var calcRate by remember(ui.settings.calcRate) { mutableStateOf(fmt(ui.settings.calcRate)) }
    var calcMultiplier by remember(ui.settings.calcMultiplier) { mutableStateOf(fmt(ui.settings.calcMultiplier)) }
    var winChance by remember(ui.settings.winChance) { mutableStateOf(fmt(ui.settings.winChance)) }
    var payout by remember(ui.settings.payout) { mutableStateOf(fmt(ui.settings.payout)) }
    var dicePayout by remember(ui.settings.dicePayout) { mutableStateOf(fmt(ui.settings.dicePayout)) }

    var newName by remember { mutableStateOf("") }
    var newPrice by remember { mutableStateOf("10000") }
    var newYield by remember { mutableStateOf("1000") }
    var newSupply by remember { mutableStateOf("10") }

    var editItem by remember { mutableStateOf<NftItem?>(null) }
    var editPrice by remember { mutableStateOf("") }
    var editYield by remember { mutableStateOf("") }
    var editSupply by remember { mutableStateOf("") }

    var grantTarget by remember { mutableStateOf<Profile?>(null) }
    var grantAmount by remember { mutableStateOf("1000") }

    var editTier by remember { mutableStateOf<SpinTier?>(null) }
    var tierWeight by remember { mutableStateOf("") }
    var tierPayout by remember { mutableStateOf("") }

    Text("Dev panel", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Spacer(Modifier.height(10.dp))

    Text("Calculator and casino numbers", fontWeight = FontWeight.SemiBold)
    Text("win chance is 0 to 1, the rest are multipliers.", fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = calcRate,
            onValueChange = { calcRate = it },
            label = { Text("rate") },
            singleLine = true,
            enabled = !ui.busy,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = calcMultiplier,
            onValueChange = { calcMultiplier = it },
            label = { Text("calc mult") },
            singleLine = true,
            enabled = !ui.busy,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = winChance,
            onValueChange = { winChance = it },
            label = { Text("win chance") },
            singleLine = true,
            enabled = !ui.busy,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = payout,
            onValueChange = { payout = it },
            label = { Text("flip payout") },
            singleLine = true,
            enabled = !ui.busy,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = dicePayout,
        onValueChange = { dicePayout = it },
        label = { Text("dice payout") },
        singleLine = true,
        enabled = !ui.busy,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            val rate = num(calcRate)
            val mult = num(calcMultiplier)
            val chance = num(winChance)
            val pay = num(payout)
            val dice = num(dicePayout)
            if (rate == null || mult == null || chance == null || pay == null || dice == null) {
                state.report("All five fields must be numbers.")
            } else {
                state.saveSettings(
                    AppSettings(
                        calcRate = rate,
                        calcMultiplier = mult,
                        winChance = chance,
                        payout = pay,
                        dicePayout = dice,
                    )
                )
            }
        },
        enabled = !ui.busy
    ) { Text("Save numbers") }

    Spacer(Modifier.height(18.dp))
    Text("Mint an NFT", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = newName,
        onValueChange = { newName = it },
        label = { Text("name") },
        singleLine = true,
        enabled = !ui.busy,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = newPrice,
            onValueChange = { newPrice = it },
            label = { Text("price") },
            singleLine = true,
            enabled = !ui.busy,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = newYield,
            onValueChange = { newYield = it },
            label = { Text("yield/day") },
            singleLine = true,
            enabled = !ui.busy,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = newSupply,
            onValueChange = { newSupply = it },
            label = { Text("supply") },
            singleLine = true,
            enabled = !ui.busy,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = {
                val price = newPrice.trim().toLongOrNull()
                val yield = newYield.trim().toLongOrNull()
                val supply = newSupply.trim().toIntOrNull()
                if (newName.isBlank() || price == null || yield == null || supply == null) {
                    state.report("Name, price, yield and supply are needed.")
                } else {
                    state.createNft(newName, price, yield, supply)
                }
            },
            enabled = !ui.busy,
            modifier = Modifier.weight(1f)
        ) { Text("Mint") }
    }

    Spacer(Modifier.height(18.dp))
    Text("NFTs", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    ui.items.forEach { item ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(item.name, fontWeight = FontWeight.Medium)
                Text(
                    "price ${item.price}  ·  yield ${item.yieldPerDay}/day  ·  ${item.minted}/${item.maxSupply} minted",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        editItem = item
                        editPrice = item.price.toString()
                        editYield = item.yieldPerDay.toString()
                        editSupply = item.maxSupply.toString()
                    },
                    enabled = !ui.busy
                ) { Text("Edit") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(18.dp))
    Text("Jackpot paytable", fontWeight = FontWeight.SemiBold)
    Text(
        "weights are relative, payout multiplies the bet",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    ui.tiers.forEach { tier ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(tier.label, fontWeight = FontWeight.Medium)
                Text(
                    "weight ${tier.weight}  ·  pays ×${fmt(tier.payout)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        editTier = tier
                        tierWeight = tier.weight.toString()
                        tierPayout = fmt(tier.payout)
                    },
                    enabled = !ui.busy
                ) { Text("Edit") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(14.dp))
    Text("Accounts", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    ui.users.forEach { user ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(user.email, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append("${user.coins} coins")
                        if (user.isAdmin) append("  ·  dev")
                        if (user.banned) append("  ·  banned")
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            grantTarget = user
                            grantAmount = "1000"
                        },
                        enabled = !ui.busy
                    ) { Text("Give coins") }
                    if (user.id != ui.me?.id) {
                        OutlinedButton(
                            onClick = { state.ban(user.id, !user.banned) },
                            enabled = !ui.busy
                        ) { Text(if (user.banned) "Unban" else "Ban") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    TextButton(onClick = { state.backToHome() }) { Text("Back") }

    val target = grantTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { grantTarget = null },
            title = { Text("Give coins") },
            text = {
                Column {
                    Text(target.email, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = grantAmount,
                        onValueChange = { grantAmount = it },
                        label = { Text("How many coins") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = grantAmount.trim().toLongOrNull()
                    if (amount == null || amount == 0L) {
                        state.report("Enter a whole number of coins.")
                    } else {
                        state.grant(target.id, amount)
                        grantTarget = null
                    }
                }) { Text("Give") }
            },
            dismissButton = {
                TextButton(onClick = { grantTarget = null }) { Text("Cancel") }
            }
        )
    }

    val editing = editItem
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { editItem = null },
            title = { Text(editing.name) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editPrice,
                        onValueChange = { editPrice = it },
                        label = { Text("price") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = editYield,
                        onValueChange = { editYield = it },
                        label = { Text("yield per day") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = editSupply,
                        onValueChange = { editSupply = it },
                        label = { Text("supply (cannot go below minted)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val price = editPrice.trim().toLongOrNull()
                    val yield = editYield.trim().toLongOrNull()
                    val supply = editSupply.trim().toIntOrNull()
                    if (price == null || yield == null || supply == null) {
                        state.report("Price, yield and supply must be numbers.")
                    } else {
                        state.updateNft(
                            editing.copy(
                                price = price,
                                yieldPerDay = yield,
                                maxSupply = supply,
                            )
                        )
                        editItem = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editItem = null }) { Text("Cancel") }
            }
        )
    }

    val tierBeingEdited = editTier
    if (tierBeingEdited != null) {
        AlertDialog(
            onDismissRequest = { editTier = null },
            title = { Text(tierBeingEdited.label) },
            text = {
                Column {
                    OutlinedTextField(
                        value = tierWeight,
                        onValueChange = { tierWeight = it },
                        label = { Text("weight") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = tierPayout,
                        onValueChange = { tierPayout = it },
                        label = { Text("payout multiplier") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val weight = tierWeight.trim().toIntOrNull()
                    val payout = num(tierPayout)
                    if (weight == null || payout == null) {
                        state.report("Weight and payout must be numbers.")
                    } else {
                        state.updateTier(tierBeingEdited.copy(weight = weight, payout = payout))
                        editTier = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editTier = null }) { Text("Cancel") }
            }
        )
    }
}

private fun fmt(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun num(text: String): Double? = text.trim().replace(",", ".").toDoubleOrNull()
