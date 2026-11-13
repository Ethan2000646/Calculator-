package com.calcanator.app

import org.json.JSONObject

/** A Calcanator account, mirrored from the `profiles` table. */
data class Profile(
    val id: String,
    val email: String,
    val coins: Long,
    val banned: Boolean,
    val isAdmin: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = Profile(
            id = o.optString("id"),
            email = o.optString("email"),
            coins = o.optLong("coins", 0L),
            banned = o.optBoolean("banned", false),
            isAdmin = o.optBoolean("is_admin", false),
        )
    }
}

/** The numbers the dev controls. */
data class AppSettings(
    val calcRate: Double = 1.0,
    val calcMultiplier: Double = 2.0,
    val winChance: Double = 0.48,
    val payout: Double = 2.0,
    val dicePayout: Double = 5.5,
) {
    companion object {
        fun from(o: JSONObject) = AppSettings(
            calcRate = o.optDouble("calc_rate", 1.0),
            calcMultiplier = o.optDouble("calc_multiplier", 2.0),
            winChance = o.optDouble("win_chance", 0.48),
            payout = o.optDouble("payout", 2.0),
            dicePayout = o.optDouble("dice_payout", 5.5),
        )
    }
}

data class NftItem(
    val id: Int,
    val name: String,
    val price: Long,
    val yieldPerDay: Long,
    val maxSupply: Int,
    val minted: Int,
) {
    val soldOut: Boolean get() = minted >= maxSupply
    val left: Int get() = (maxSupply - minted).coerceAtLeast(0)

    companion object {
        fun from(o: JSONObject) = NftItem(
            id = o.optInt("id"),
            name = o.optString("name"),
            price = o.optLong("price", 0L),
            yieldPerDay = o.optLong("yield_per_day", 0L),
            maxSupply = o.optInt("max_supply", 0),
            minted = o.optInt("minted", 0),
        )
    }
}

/** What the calculator says a bet would pay out. Coins are NOT added. */
data class Preview(val payout: Long, val profit: Long)

data class SpinResult(val win: Boolean, val roll: Int, val prize: Long, val coins: Long)

/** One row of the jackpot paytable. chance = weight / total weight. */
data class SpinTier(
    val id: Int,
    val label: String,
    val kind: String,
    val weight: Int,
    val payout: Double,
) {
    companion object {
        fun from(o: JSONObject) = SpinTier(
            id = o.optInt("id"),
            label = o.optString("label"),
            kind = o.optString("kind"),
            weight = o.optInt("weight"),
            payout = o.optDouble("payout", 0.0),
        )
    }
}

data class JackpotResult(val label: String, val kind: String, val prize: Long, val coins: Long)

data class LeaderRow(val handle: String, val coins: Long) {
    companion object {
        fun from(o: JSONObject) = LeaderRow(
            handle = o.optString("handle"),
            coins = o.optLong("coins", 0L),
        )
    }
}

class ApiException(message: String) : Exception(message)

/**
 * Everything the UI can do. [SupabaseBackend] is the real server;
 * [DemoBackend] is the offline copy for trying the UI.
 */
interface Backend {
    fun hasSession(): Boolean
    suspend fun signOut()
    suspend fun signUp(email: String, password: String): Profile
    suspend fun signIn(email: String, password: String): Profile
    suspend fun me(): Profile
    suspend fun loadSettings(): AppSettings
    suspend fun preview(amount: Double): Preview
    suspend fun bet(amount: Long, game: String, pick: String): SpinResult
    suspend fun items(): List<NftItem>
    suspend fun owned(): Map<Int, Int>
    suspend fun buy(itemId: Int, qty: Int): Long
    suspend fun sell(itemId: Int, qty: Int): Long
    suspend fun claimYield(): Long
    suspend fun tiers(): List<SpinTier>
    suspend fun spinJackpot(amount: Long): JackpotResult
    suspend fun leaderboard(): List<LeaderRow>
    suspend fun listUsers(): List<Profile>
    suspend fun grant(userId: String, amount: Long): Long
    suspend fun ban(userId: String, banned: Boolean)
    suspend fun saveSettings(settings: AppSettings)
    suspend fun updateNft(item: NftItem)
    suspend fun createNft(name: String, price: Long, yieldPerDay: Long, supply: Int)
    suspend fun updateTier(tier: SpinTier)
}
