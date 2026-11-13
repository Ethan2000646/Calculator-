package com.calcanator.app

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor

/** Login tokens, kept on the device so you don't sign in every launch. */
class SessionStore(private val prefs: SharedPreferences) {
    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().putString("token", value).apply()

    var refresh: String?
        get() = prefs.getString("refresh", null)
        set(value) = prefs.edit().putString("refresh", value).apply()

    var uid: String?
        get() = prefs.getString("uid", null)
        set(value) = prefs.edit().putString("uid", value).apply()

    var email: String?
        get() = prefs.getString("email", null)
        set(value) = prefs.edit().putString("email", value).apply()

    fun clear() = prefs.edit()
        .remove("token")
        .remove("refresh")
        .remove("uid")
        .remove("email")
        .apply()
}

/** Talks to Supabase over its REST API. No SDK, so the build stays small. */
class SupabaseBackend(
    private val baseUrl: String,
    private val anonKey: String,
    private val session: SessionStore,
) : Backend {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun execute(method: String, path: String, body: String?, auth: Boolean): Pair<Int, String> {
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("apikey", anonKey)
        if (auth) session.token?.let { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMedia))
            else -> return 400 to "Unsupported method $method"
        }
        client.newCall(builder.build()).execute().use { response ->
            return response.code to (response.body?.string().orEmpty())
        }
    }

    /** Access tokens last an hour, so a 401 gets one silent refresh + retry. */
    private fun tryRefresh(): Boolean {
        val refresh = session.refresh ?: return false
        val body = JSONObject().put("refresh_token", refresh).toString()
        val (code, text) = execute("POST", "/auth/v1/token?grant_type=refresh_token", body, auth = false)
        if (code !in 200..299) return false
        return try {
            val o = JSONObject(text)
            val token = o.optString("access_token", "")
            if (token.isBlank()) return false
            session.token = token
            o.optString("refresh_token", "").takeIf { it.isNotBlank() }?.let { session.refresh = it }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun http(
        method: String,
        path: String,
        body: String? = null,
        auth: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        var (code, text) = execute(method, path, body, auth)
        if (code == 401 && auth && tryRefresh()) {
            val retry = execute(method, path, body, auth)
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) throw ApiException(describeError(code, text))
        text
    }

    private fun describeError(code: Int, text: String): String {
        val message = try {
            val o = JSONObject(text)
            listOf("msg", "error_description", "message", "error", "hint")
                .map { o.optString(it, "") }
                .firstOrNull { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: text.take(160)
        return "HTTP $code: $message"
    }

    private fun storeSession(o: JSONObject, email: String?) {
        session.token = o.optString("access_token", "").takeIf { it.isNotBlank() }
        session.refresh = o.optString("refresh_token", "").takeIf { it.isNotBlank() }
        session.uid = o.optJSONObject("user")?.optString("id")
        if (email != null) session.email = email
    }

    private fun array(raw: String) = JSONArray(raw)

    override fun hasSession(): Boolean = !session.token.isNullOrBlank()

    override suspend fun signOut() {
        session.clear()
    }

    override suspend fun signUp(email: String, password: String): Profile {
        val body = JSONObject().put("email", email).put("password", password).toString()
        val o = JSONObject(http("POST", "/auth/v1/signup", body, auth = false))
        if (o.optString("access_token", "").isBlank()) {
            throw ApiException("Account created. Confirm it by email, then sign in.")
        }
        storeSession(o, email)
        return me()
    }

    override suspend fun signIn(email: String, password: String): Profile {
        val body = JSONObject().put("email", email).put("password", password).toString()
        val o = JSONObject(http("POST", "/auth/v1/token?grant_type=password", body, auth = false))
        if (o.optString("access_token", "").isBlank()) {
            throw ApiException("Sign-in failed. Wrong email or password?")
        }
        storeSession(o, email)
        return me()
    }

    override suspend fun me(): Profile {
        val uid = session.uid ?: throw ApiException("Not signed in.")
        val rows = array(http("GET", "/rest/v1/profiles?id=eq.$uid&select=*"))
        if (rows.length() == 0) throw ApiException("No profile row. Run supabase/schema.sql first.")
        return Profile.from(rows.getJSONObject(0))
    }

    override suspend fun loadSettings(): AppSettings {
        val rows = array(http("GET", "/rest/v1/app_settings?id=eq.1&select=*"))
        if (rows.length() == 0) return AppSettings()
        return AppSettings.from(rows.getJSONObject(0))
    }

    override suspend fun preview(amount: Double): Preview {
        val body = JSONObject().put("p_amount", amount).toString()
        val o = JSONObject(http("POST", "/rest/v1/rpc/calc_preview", body))
        return Preview(payout = o.optLong("payout"), profit = o.optLong("profit"))
    }

    override suspend fun bet(amount: Long, game: String, pick: String): SpinResult {
        val body = JSONObject()
            .put("p_bet", amount)
            .put("p_game", game)
            .put("p_pick", pick)
            .toString()
        val o = JSONObject(http("POST", "/rest/v1/rpc/casino_bet", body))
        return SpinResult(
            win = o.optBoolean("win"),
            roll = o.optInt("roll"),
            prize = o.optLong("prize"),
            coins = o.optLong("coins"),
        )
    }

    override suspend fun items(): List<NftItem> {
        val rows = array(http("GET", "/rest/v1/nft_items?select=*&order=id.asc"))
        return (0 until rows.length()).map { NftItem.from(rows.getJSONObject(it)) }
    }

    override suspend fun owned(): Map<Int, Int> {
        val rows = array(http("GET", "/rest/v1/user_nfts?select=item_id,qty"))
        val map = mutableMapOf<Int, Int>()
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            map[o.optInt("item_id")] = o.optInt("qty")
        }
        return map
    }

    override suspend fun buy(itemId: Int, qty: Int): Long {
        val body = JSONObject().put("p_item", itemId).put("p_qty", qty).toString()
        return JSONObject(http("POST", "/rest/v1/rpc/buy_nft", body)).optLong("coins")
    }

    override suspend fun sell(itemId: Int, qty: Int): Long {
        val body = JSONObject().put("p_item", itemId).put("p_qty", qty).toString()
        return JSONObject(http("POST", "/rest/v1/rpc/sell_nft", body)).optLong("coins")
    }

    override suspend fun claimYield(): Long {
        return JSONObject(http("POST", "/rest/v1/rpc/claim_yield", "{}")).optLong("earned")
    }

    override suspend fun tiers(): List<SpinTier> {
        val rows = array(http("GET", "/rest/v1/spin_tiers?select=*&order=sort.asc"))
        return (0 until rows.length()).map { SpinTier.from(rows.getJSONObject(it)) }
    }

    override suspend fun spinJackpot(amount: Long): JackpotResult {
        val body = JSONObject().put("p_bet", amount).toString()
        val o = JSONObject(http("POST", "/rest/v1/rpc/jackpot_spin", body))
        return JackpotResult(
            label = o.optString("label"),
            kind = o.optString("kind"),
            prize = o.optLong("prize"),
            coins = o.optLong("coins"),
        )
    }

    override suspend fun leaderboard(): List<LeaderRow> {
        val body = JSONObject().put("p_limit", 50).toString()
        val rows = array(http("POST", "/rest/v1/rpc/leaderboard", body))
        return (0 until rows.length()).map { LeaderRow.from(rows.getJSONObject(it)) }
    }

    override suspend fun updateTier(tier: SpinTier) {
        val body = JSONObject()
            .put("p_id", tier.id)
            .put("p_weight", tier.weight)
            .put("p_payout", tier.payout)
            .toString()
        http("POST", "/rest/v1/rpc/admin_update_tier", body)
    }

    override suspend fun listUsers(): List<Profile> {
        val rows = array(http("GET", "/rest/v1/profiles?select=*&order=created_at.desc"))
        return (0 until rows.length()).map { Profile.from(rows.getJSONObject(it)) }
    }

    override suspend fun grant(userId: String, amount: Long): Long {
        val body = JSONObject().put("p_user", userId).put("p_amount", amount).toString()
        return JSONObject(http("POST", "/rest/v1/rpc/admin_grant", body)).optLong("coins")
    }

    override suspend fun ban(userId: String, banned: Boolean) {
        val body = JSONObject().put("p_user", userId).put("p_banned", banned).toString()
        http("POST", "/rest/v1/rpc/admin_ban", body)
    }

    override suspend fun saveSettings(settings: AppSettings) {
        val body = JSONObject()
            .put("p_calc_rate", settings.calcRate)
            .put("p_calc_multiplier", settings.calcMultiplier)
            .put("p_win_chance", settings.winChance)
            .put("p_payout", settings.payout)
            .put("p_dice_payout", settings.dicePayout)
            .toString()
        http("POST", "/rest/v1/rpc/admin_set_settings", body)
    }

    override suspend fun updateNft(item: NftItem) {
        val body = JSONObject()
            .put("p_id", item.id)
            .put("p_price", item.price)
            .put("p_yield", item.yieldPerDay)
            .put("p_supply", item.maxSupply)
            .put("p_active", true)
            .toString()
        http("POST", "/rest/v1/rpc/admin_update_nft", body)
    }

    override suspend fun createNft(name: String, price: Long, yieldPerDay: Long, supply: Int) {
        val body = JSONObject()
            .put("p_name", name)
            .put("p_price", price)
            .put("p_yield", yieldPerDay)
            .put("p_supply", supply)
            .toString()
        http("POST", "/rest/v1/rpc/admin_create_nft", body)
    }
}

/**
 * Offline stand-in for the server: the same rules, stored in shared preferences on
 * this phone. It exists so the app can be tried before the Supabase project exists.
 */
class DemoBackend(private val prefs: SharedPreferences) : Backend {

    private class DUser(
        var id: String,
        var email: String,
        var password: String,
        var coins: Long,
        var banned: Boolean,
        var admin: Boolean,
        var lastYield: Long,
    )

    private class DItem(
        var id: Int,
        var name: String,
        var price: Long,
        var yieldPerDay: Long,
        var supply: Int,
        var minted: Int,
    )

    private class DTier(
        var id: Int,
        var label: String,
        var kind: String,
        var weight: Int,
        var payout: Double,
    )

    private val users = mutableListOf<DUser>()
    private val stock = mutableListOf<DItem>()
    private val tierList = mutableListOf<DTier>()
    private val owned = mutableMapOf<String, MutableMap<Int, Int>>()
    private var settings = AppSettings()
    private var currentId: String? = prefs.getString("demo_uid", null)

    init {
        load()
        if (users.isEmpty()) seed()
    }

    private fun load() {
        val raw = prefs.getString("demo_state", null) ?: return
        try {
            val o = JSONObject(raw)
            val u = o.optJSONArray("users") ?: JSONArray()
            for (i in 0 until u.length()) {
                val x = u.getJSONObject(i)
                users.add(
                    DUser(
                        x.getString("id"),
                        x.getString("email"),
                        x.getString("password"),
                        x.optLong("coins"),
                        x.optBoolean("banned"),
                        x.optBoolean("admin"),
                        x.optLong("lastYield"),
                    )
                )
            }
            val it0 = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until it0.length()) {
                val x = it0.getJSONObject(i)
                stock.add(
                    DItem(
                        x.getInt("id"),
                        x.getString("name"),
                        x.optLong("price"),
                        x.optLong("yield"),
                        x.optInt("supply"),
                        x.optInt("minted"),
                    )
                )
            }
            val ow = o.optJSONObject("owned") ?: JSONObject()
            ow.keys().forEach { uid ->
                val m = mutableMapOf<Int, Int>()
                val inner = ow.optJSONObject(uid)
                if (inner != null) {
                    inner.keys().forEach { key -> m[key.toInt()] = inner.optInt(key) }
                }
                owned[uid] = m
            }
            o.optJSONObject("settings")?.let { settings = AppSettings.from(it) }
            val tr = o.optJSONArray("tiers") ?: JSONArray()
            for (i in 0 until tr.length()) {
                val x = tr.getJSONObject(i)
                tierList.add(
                    DTier(
                        x.getInt("id"),
                        x.getString("label"),
                        x.getString("kind"),
                        x.optInt("weight"),
                        x.optDouble("payout"),
                    )
                )
            }
        } catch (_: Exception) {
            users.clear()
            stock.clear()
            tierList.clear()
            owned.clear()
            settings = AppSettings()
        }
    }

    private fun save() {
        val o = JSONObject()
        val u = JSONArray()
        users.forEach { x ->
            u.put(
                JSONObject()
                    .put("id", x.id)
                    .put("email", x.email)
                    .put("password", x.password)
                    .put("coins", x.coins)
                    .put("banned", x.banned)
                    .put("admin", x.admin)
                    .put("lastYield", x.lastYield)
            )
        }
        o.put("users", u)
        val it0 = JSONArray()
        stock.forEach { x ->
            it0.put(
                JSONObject()
                    .put("id", x.id)
                    .put("name", x.name)
                    .put("price", x.price)
                    .put("yield", x.yieldPerDay)
                    .put("supply", x.supply)
                    .put("minted", x.minted)
            )
        }
        o.put("items", it0)
        val ow = JSONObject()
        owned.forEach { (uid, m) ->
            val inner = JSONObject()
            m.forEach { (id, qty) -> inner.put(id.toString(), qty) }
            ow.put(uid, inner)
        }
        o.put("owned", ow)
        val tr = JSONArray()
        tierList.forEach { x ->
            tr.put(
                JSONObject()
                    .put("id", x.id)
                    .put("label", x.label)
                    .put("kind", x.kind)
                    .put("weight", x.weight)
                    .put("payout", x.payout)
            )
        }
        o.put("tiers", tr)
        o.put(
            "settings",
            JSONObject()
                .put("calc_rate", settings.calcRate)
                .put("calc_multiplier", settings.calcMultiplier)
                .put("win_chance", settings.winChance)
                .put("payout", settings.payout)
                .put("dice_payout", settings.dicePayout)
        )
        prefs.edit().putString("demo_state", o.toString()).putString("demo_uid", currentId).apply()
    }

    private fun seed() {
        users.add(DUser("demo-dev", "dev@calcanator.app", "dev1234", 100000L, false, true, 0L))
        users.add(DUser("demo-alex", "alex@calcanator.app", "alex1234", 20000L, false, false, 0L))
        users.add(DUser("demo-sam", "sam@calcanator.app", "sam1234", 5000L, true, false, 0L))
        stock.add(DItem(1, "Bronze block", 5000L, 500L, 100, 0))
        stock.add(DItem(2, "Silver vault", 25000L, 3000L, 25, 0))
        stock.add(DItem(3, "Gold dep", 100000L, 15000L, 5, 0))
        tierList.add(DTier(1, "JACKPOT", "jackpot", 5, 50.0))
        tierList.add(DTier(2, "Triple", "triple", 300, 12.0))
        tierList.add(DTier(3, "Pair", "pair", 4000, 1.2))
        tierList.add(DTier(4, "Nothing", "miss", 5695, 0.0))
        currentId = null
        save()
    }

    private fun current(): DUser =
        users.firstOrNull { it.id == currentId } ?: throw ApiException("Not signed in.")

    private fun admin(): DUser {
        val u = current()
        if (!u.admin) throw ApiException("Admins only.")
        return u
    }

    private fun toProfile(u: DUser) = Profile(u.id, u.email, u.coins, u.banned, u.admin)

    private fun mine(): MutableMap<Int, Int> =
        owned.getOrPut(current().id) { mutableMapOf() }

    override fun hasSession(): Boolean = users.any { it.id == currentId }

    override suspend fun signOut() {
        currentId = null
        save()
    }

    override suspend fun signUp(email: String, password: String): Profile {
        val clean = email.trim()
        if (users.any { it.email.equals(clean, ignoreCase = true) }) {
            throw ApiException("That email is already taken.")
        }
        // 1000 starter coins, same as the real server.
        val u = DUser("demo-" + System.currentTimeMillis(), clean, password, 1000L, false, false, 0L)
        users.add(u)
        currentId = u.id
        save()
        return toProfile(u)
    }

    override suspend fun signIn(email: String, password: String): Profile {
        val u = users.firstOrNull { it.email.equals(email.trim(), ignoreCase = true) }
            ?: throw ApiException("No demo account with that email.")
        if (u.password != password) throw ApiException("Wrong password.")
        currentId = u.id
        save()
        return toProfile(u)
    }

    override suspend fun me(): Profile = toProfile(current())

    override suspend fun loadSettings(): AppSettings = settings

    override suspend fun preview(amount: Double): Preview {
        if (amount < 0) throw ApiException("Amount must be 0 or more.")
        val payout = floor(amount * settings.calcRate * settings.calcMultiplier).toLong()
        return Preview(payout = payout, profit = payout - floor(amount).toLong())
    }

    override suspend fun bet(amount: Long, game: String, pick: String): SpinResult {
        val u = current()
        if (u.banned) throw ApiException("This account is banned.")
        if (amount <= 0) throw ApiException("Bet must be more than 0.")
        if (amount > u.coins) throw ApiException("Not enough calc coins.")

        var won: Boolean
        var prize = 0L
        var roll: Int
        if (game == "flip") {
            if (pick != "heads" && pick != "tails") throw ApiException("Pick heads or tails.")
            roll = (0..99).random()
            won = roll < ceil(settings.winChance * 100).toInt()
            if (won) prize = floor(amount * settings.payout).toLong()
        } else if (game == "dice") {
            val pickNumber = pick.toIntOrNull()
            if (pickNumber == null || pickNumber !in 1..6) throw ApiException("Pick a number from 1 to 6.")
            roll = (1..6).random()
            won = roll == pickNumber
            if (won) prize = floor(amount * settings.dicePayout).toLong()
        } else {
            throw ApiException("Unknown game.")
        }

        u.coins = u.coins - amount + prize
        save()
        return SpinResult(won, roll, prize, u.coins)
    }

    override suspend fun items(): List<NftItem> =
        stock.map { NftItem(it.id, it.name, it.price, it.yieldPerDay, it.supply, it.minted) }

    override suspend fun owned(): Map<Int, Int> = mine().toMap()

    override suspend fun buy(itemId: Int, qty: Int): Long {
        val u = current()
        if (u.banned) throw ApiException("This account is banned.")
        if (qty <= 0) throw ApiException("Quantity must be more than 0.")
        val item = stock.firstOrNull { it.id == itemId } ?: throw ApiException("NFT not found.")
        if (item.minted + qty > item.supply) throw ApiException("Only ${item.supply - item.minted} left.")
        val total = item.price * qty
        if (total > u.coins) throw ApiException("You need $total calc coins.")

        item.minted += qty
        val m = mine()
        m[itemId] = (m[itemId] ?: 0) + qty
        u.coins -= total
        save()
        return u.coins
    }

    override suspend fun sell(itemId: Int, qty: Int): Long {
        val u = current()
        if (qty <= 0) throw ApiException("Quantity must be more than 0.")
        val item = stock.firstOrNull { it.id == itemId } ?: throw ApiException("NFT not found.")
        val m = mine()
        val have = m[itemId] ?: 0
        if (have < qty) throw ApiException("You do not own that many.")

        val refund = floor(item.price * 0.9).toLong() * qty
        val left = have - qty
        if (left == 0) m.remove(itemId) else m[itemId] = left
        item.minted = (item.minted - qty).coerceAtLeast(0)
        u.coins += refund
        save()
        return u.coins
    }

    override suspend fun claimYield(): Long {
        val u = current()
        if (u.banned) throw ApiException("This account is banned.")
        val m = mine()
        var earned = 0L
        stock.forEach { item -> earned += item.yieldPerDay * (m[item.id] ?: 0) }
        if (earned <= 0) throw ApiException("You own no NFTs yet, so there is nothing to claim.")
        val now = System.currentTimeMillis()
        if (u.lastYield > 0 && now - u.lastYield < 24L * 60 * 60 * 1000) {
            val hours = ((24L * 60 * 60 * 1000 - (now - u.lastYield)) / 3600000L) + 1
            throw ApiException("Already claimed. Come back in $hours hours.")
        }
        u.lastYield = now
        u.coins += earned
        save()
        return earned
    }

    override suspend fun tiers(): List<SpinTier> =
        tierList.map { SpinTier(it.id, it.label, it.kind, it.weight, it.payout) }

    override suspend fun spinJackpot(amount: Long): JackpotResult {
        val u = current()
        if (u.banned) throw ApiException("This account is banned.")
        if (amount <= 0) throw ApiException("Bet must be more than 0.")
        if (amount > u.coins) throw ApiException("Not enough calc coins.")

        val active = tierList.filter { it.weight > 0 }.sortedBy { it.id }
        val total = active.sumOf { it.weight }
        if (total <= 0) throw ApiException("The paytable is empty.")

        var roll = (1..total).random()
        var chosen = active.last()
        for (tier in active) {
            if (roll <= tier.weight) {
                chosen = tier
                break
            }
            roll -= tier.weight
        }

        val prize = floor(amount * chosen.payout).toLong()
        u.coins = u.coins - amount + prize
        save()
        return JackpotResult(chosen.label, chosen.kind, prize, u.coins)
    }

    override suspend fun leaderboard(): List<LeaderRow> =
        users.filter { !it.banned && it.coins > 0 }
            .sortedByDescending { it.coins }
            .take(50)
            .map { LeaderRow(it.email.substringBefore("@"), it.coins) }

    override suspend fun updateTier(tier: SpinTier) {
        admin()
        val existing = tierList.firstOrNull { it.id == tier.id }
            ?: throw ApiException("Tier not found.")
        existing.weight = tier.weight
        existing.payout = tier.payout
        save()
    }

    override suspend fun listUsers(): List<Profile> {
        admin()
        return users.map { toProfile(it) }
    }

    override suspend fun grant(userId: String, amount: Long): Long {
        admin()
        if (amount == 0L) throw ApiException("Amount must not be zero.")
        val u = users.firstOrNull { it.id == userId } ?: throw ApiException("User not found.")
        u.coins = (u.coins + amount).coerceAtLeast(0L)
        save()
        return u.coins
    }

    override suspend fun ban(userId: String, banned: Boolean) {
        val me = admin()
        if (me.id == userId) throw ApiException("You cannot ban yourself.")
        val u = users.firstOrNull { it.id == userId } ?: throw ApiException("User not found.")
        u.banned = banned
        save()
    }

    override suspend fun saveSettings(settings: AppSettings) {
        admin()
        this.settings = settings
        save()
    }

    override suspend fun updateNft(item: NftItem) {
        admin()
        val existing = stock.firstOrNull { it.id == item.id } ?: throw ApiException("NFT not found.")
        existing.price = item.price
        existing.yieldPerDay = item.yieldPerDay
        existing.supply = item.maxSupply.coerceAtLeast(existing.minted)
        save()
    }

    override suspend fun createNft(name: String, price: Long, yieldPerDay: Long, supply: Int) {
        admin()
        if (name.isBlank()) throw ApiException("Give the NFT a name.")
        if (supply <= 0) throw ApiException("Supply must be more than 0.")
        val id = (stock.maxOfOrNull { it.id } ?: 0) + 1
        stock.add(DItem(id, name.trim(), price, yieldPerDay, supply, 0))
        save()
    }
}
