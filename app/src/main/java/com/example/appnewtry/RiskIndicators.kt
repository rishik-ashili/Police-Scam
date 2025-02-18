package com.example.appnewtry

class RiskIndicators {
    private val urgencyPhrases = listOf(
        "तुरंत", "अभी", "जल्दी", "तत्काल", "इमरजेंसी", "समय कम है", "देर मत करो", "अभी के अभी",
        "urgent", "immediately", "emergency", "quick", "hurry", "now", "limited time", "act fast",
        "running out of time", "deadline", "asap", "crucial", "critical"
    )

    private val legalThreats = listOf(
        "गिरफ्तार", "पुलिस", "कानूनी कार्रवाई", "कोर्ट", "जेल", "वारंट", "अदालत", "न्यायालय", "कानूनी नोटिस",
        "arrest", "police", "legal action", "court", "jail", "warrant", "lawsuit", "criminal charges",
        "prosecution", "legal notice", "legal consequences", "fbi", "enforcement"
    )

    private val moneyRequests = listOf(
        "पैसे", "भुगतान", "ट्रांसफर", "बैंक", "जुर्माना", "क्रेडिट कार्ड", "डेबिट कार्ड", "वॉलेट", "यूपीआई",
        "money", "payment", "transfer", "bank", "fine", "credit card", "debit card", "wallet", "upi",
        "transaction", "deposit", "fee", "penalty", "charge", "account frozen", "funds", "wire transfer"
    )

    private val identityPhrases = listOf(
        "आधार", "पैन कार्ड", "बैंक डिटेल्स", "ओटीपी", "पासवर्ड", "खाता संख्या", "पिन नंबर",
        "aadhar", "pan card", "bank details", "otp", "password", "account number", "pin number",
        "social security", "ssn", "verify identity", "verification code", "authentication", "login details"
    )

    private val fraudPhrases = listOf(
        "suspicious activity", "account compromised", "security breach", "unauthorized", "fraud detected",
        "illegal activity", "खाता हैक", "धोखाधड़ी", "अवैध गतिविधि", "security alert", "unusual activity",
        "hacked", "blocked", "suspended", "terminated", "frozen"
    )

    fun analyzeText(text: String): RiskAnalysis {
        val flags = mutableListOf<RiskFlag>()
        var score = 0
        val maxScore = 10 // Base score for each category

        val categories = mapOf(
            "Urgency" to urgencyPhrases,
            "Legal Threats" to legalThreats,
            "Money Requests" to moneyRequests,
            "Identity" to identityPhrases,
            "Fraud" to fraudPhrases
        )

        for ((category, phrases) in categories) {
            val matchedPhrases = phrases.filter { 
                text.lowercase().contains(it.lowercase()) 
            }
            
            if (matchedPhrases.isNotEmpty()) {
                flags.add(RiskFlag(category, matchedPhrases))
                score += matchedPhrases.size
                if (flags.size > 1) score += 2 // Additional score for multiple categories
            }
        }

        val riskScore = (score.toFloat() / maxScore).coerceAtMost(1.0f) * 100
        return RiskAnalysis(riskScore, flags)
    }
}

data class RiskFlag(
    val category: String,
    val phrases: List<String>
)

data class RiskAnalysis(
    val score: Float,
    val flags: List<RiskFlag>
) 