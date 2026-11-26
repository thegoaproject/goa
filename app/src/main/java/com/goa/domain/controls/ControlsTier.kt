package com.goa.domain.controls

/**
 * How trustworthy a game's control layout is, shown to the player so a guess never looks like a verified match.
 * Most trusted first: the player's own mappings, then the game's own documentation, then its engine, then its genre, then a plain guess.
 */
enum class ControlsTier(val code: String, val label: String, val blurb: String) {
    CUSTOM("CUSTOM", "Custom", "Your own button mappings"),
    EXACT("EXACT", "Exact match", "Taken from this game's own manual or readme, or tuned by hand"),
    ENGINE("ENGINE", "Engine layout", "The controls games on this engine share"),
    GENRE("GENRE", "Genre layout", "The standard controls for this type of game"),
    // Shown to players: says what GOA did (set up from the files), never how unsure it is
    GUESS("GUESS", "Smart setup", "Set up from the game's own files");

    companion object {
        /** The tier a registry profile earns from where its controls came from (the harvest `evidence` field). */
        fun ofProfile(evidence: String?, confidence: Float?, id: String = ""): ControlsTier = when {
            id.startsWith("ai-") || id.startsWith("published-") -> EXACT      // chosen on this device or by players
            evidence == null -> EXACT                                          // hand-curated profile
            evidence == "manual" || evidence == "web" -> EXACT
            evidence == "readme" || evidence == "config" -> if ((confidence ?: 1f) >= 0.5f) EXACT else GUESS
            evidence == "mixed" -> if ((confidence ?: 0f) >= 0.6f) EXACT else GUESS
            evidence == "genre" -> GENRE
            evidence == "engine" -> ENGINE
            // "digest": the first bulk harvest's layouts. A model read the game's files but no key was checked against a
            // quote, so they are labelled as the guesses they are (they used to claim "readme"/"config" and show as exact)
            evidence == "digest" -> GUESS
            else -> GUESS                                                      // "knowledge", "default": the model's memory
        }

        /** True when [candidate] is a less trustworthy source than what is already applied ([stored]): never replace better with worse. */
        fun isDowngrade(stored: ControlsTier?, candidate: ControlsTier): Boolean = stored != null && candidate.ordinal > stored.ordinal

        /** The text stored as a game's control-scheme origin: "ENGINE: Build engine detected ...". */
        fun tag(tier: ControlsTier, detail: String) = "${tier.code}: $detail"

        /** Splits a stored origin tag into its tier and detail; null for older tags without a tier prefix. */
        fun parseTag(tag: String?): Pair<ControlsTier, String>? {
            if (tag == null) return null
            val tier = entries.firstOrNull { tag.startsWith(it.code + ": ") } ?: return null
            return tier to tag.removePrefix(tier.code + ": ").trim()
        }
    }
}
