package com.nex.robotpet.v6.data

import com.nex.robotpet.v6.util.LanguageMode

/**
 * Verified facts for Montfort Senior Secondary School, Bagaha (Part K).
 * Directly verified from official website https://montfortbagaha.org/
 * (Homepage, Mandatory Public Disclosure, SARAS 6.0 PDF, and Management Team page).
 * Zero invented facts or unverified names.
 */
object SchoolKnowledgeRepository {

    const val SCHOOL_NAME = "Montfort Senior Secondary School, Bagaha"
    const val OFFICIAL_WEBSITE = "https://montfortbagaha.org/"
    const val ADDRESS = "Bankatwa, Bagaha-1, West Champaran, Bihar - 845101"
    const val FOUNDED_YEAR = 2005
    const val MANAGED_BY = "Montfort Brothers of St. Gabriel, Ranchi Province (Brothers of St. Gabriel Education Society)"
    const val CBSE_AFFILIATION = "330507"
    const val SCHOOL_CODE = "65499"
    const val PRINCIPAL = "Bro. Vincent Madhanu (B.Ed., M.Ed.)"
    const val VICE_PRINCIPAL = "Bro. Bimal Ekka (B.Ed.)"
    const val EMAIL = "montbagaha@gmail.com"
    const val CONTACT_PHONE = "06251-226503 / 9955377902"
    val CLUBS = listOf("Eco-Club", "Literary Club", "Science and Math Club", "Games & Sports Club")

    fun answerSchoolQuery(query: String, lang: LanguageMode): String? {
        val lower = query.lowercase()
        val mentionsSchool = listOf(
            "montfort", "bagaha", "मॉन्टफोर्ट", "बगहा", "our school", "hamara school", "apna school"
        ).any { lower.contains(it) }

        val asksPrincipalWithMontfort = mentionsSchool && listOf(
            "principal", "प्रिंसिपल", "प्राचार्य", "headmaster"
        ).any { lower.contains(it) }

        val asksVicePrincipal = mentionsSchool && (lower.contains("vice principal") || lower.contains("vice-principal") || lower.contains("उप प्राचार्य"))

        if (!mentionsSchool) return null

        if (asksVicePrincipal) {
            return when (lang) {
                LanguageMode.HINDI ->
                    "मॉन्टफोर्ट सीनियर सेकेंडरी स्कूल, बगहा के आधिकारिक प्रबंधन पृष्ठ के अनुसार उप-प्राचार्य (Vice Principal) ब्रदर बिमल एक्का (B.Ed.) हैं।"
                LanguageMode.HINGLISH ->
                    "Montfort Senior Secondary School, Bagaha ke official website ke anusaar Vice Principal Bro. Bimal Ekka (B.Ed.) hain."
                LanguageMode.ENGLISH ->
                    "According to the official website https://montfortbagaha.org/, the Vice Principal of Montfort Senior Secondary School, Bagaha is Bro. Bimal Ekka (B.Ed.)."
            }
        }

        if (asksPrincipalWithMontfort) {
            return when (lang) {
                LanguageMode.HINDI ->
                    "मॉन्टफोर्ट सीनियर सेकेंडरी स्कूल, बगहा के प्राचार्य (Principal) ब्रदर विन्सेंट मधानु (B.Ed., M.Ed.) हैं, जैसा कि आधिकारिक वेबसाइट montfortbagaha.org पर सत्यापित है।"
                LanguageMode.HINGLISH ->
                    "Montfort Senior Secondary School, Bagaha ke Principal Bro. Vincent Madhanu (B.Ed., M.Ed.) hain, jaisa official website montfortbagaha.org par confirmed hai."
                LanguageMode.ENGLISH ->
                    "According to the official disclosure on https://montfortbagaha.org/, the Principal of Montfort Senior Secondary School, Bagaha is Bro. Vincent Madhanu (B.Ed., M.Ed.)."
            }
        }

        if (lower.contains("website") || lower.contains("वेबसाइट")) {
            return when (lang) {
                LanguageMode.HINDI ->
                    "मॉन्टफोर्ट सीनियर सेकेंडरी स्कूल, बगहा की आधिकारिक वेबसाइट https://montfortbagaha.org/ है।"
                LanguageMode.HINGLISH ->
                    "Montfort Senior Secondary School, Bagaha ki official website https://montfortbagaha.org/ hai."
                LanguageMode.ENGLISH ->
                    "The official website of Montfort Senior Secondary School, Bagaha is https://montfortbagaha.org/."
            }
        }

        if (lower.contains("affiliation") || lower.contains("school code") || lower.contains("cbse")) {
            return when (lang) {
                LanguageMode.HINDI ->
                    "मॉन्टफोर्ट सीनियर सेकेंडरी स्कूल, बगहा का सीबीएसई संबद्धता क्रमांक (Affiliation No.) 330507 और स्कूल कोड 65499 है।"
                LanguageMode.HINGLISH ->
                    "Montfort Senior Secondary School, Bagaha ka CBSE Affiliation Number 330507 aur School Code 65499 hai."
                LanguageMode.ENGLISH ->
                    "Montfort Senior Secondary School, Bagaha is affiliated with CBSE under Affiliation Number 330507 and School Code 65499."
            }
        }

        return when (lang) {
            LanguageMode.HINDI ->
                "मॉन्टफोर्ट सीनियर सेकेंडरी स्कूल, बनकटवा, बगहा (पश्चिमी चंपारण, बिहार - 845101) वर्ष 2005 में स्थापित एक प्रतिष्ठित विद्यालय है, जिसका संचालन मॉन्टफोर्ट ब्रदर्स ऑफ सेंट गैब्रियल, रांची प्रांत द्वारा किया जाता है। इसके प्राचार्य ब्रदर विन्सेंट मधानु हैं और आधिकारिक वेबसाइट montfortbagaha.org है।"
            LanguageMode.HINGLISH ->
                "Montfort Senior Secondary School, Bankatwa, Bagaha (West Champaran, Bihar - 845101) 2005 mein sthapit hua tha aur ise Montfort Brothers of St. Gabriel, Ranchi Province manage karte hain. School ka CBSE Affiliation No. 330507 hai, Principal Bro. Vincent Madhanu hain, aur official website montfortbagaha.org hai."
            LanguageMode.ENGLISH ->
                "Montfort Senior Secondary School, located at Bankatwa, Bagaha-1, West Champaran, Bihar (845101), was founded in 2005 and is administered by the Montfort Brothers of St. Gabriel, Ranchi Province. Its CBSE Affiliation Number is 330507, School Code is 65499, Principal is Bro. Vincent Madhanu, and official website is https://montfortbagaha.org/."
        }
    }

    fun buildFormattedSummaryCard(): String = buildString {
        appendLine("School Name: $SCHOOL_NAME")
        appendLine("Official Website: $OFFICIAL_WEBSITE")
        appendLine("Address: $ADDRESS")
        appendLine("Founded: $FOUNDED_YEAR")
        appendLine("Managed By: $MANAGED_BY")
        appendLine("CBSE Affiliation No.: $CBSE_AFFILIATION  |  School Code: $SCHOOL_CODE")
        appendLine("Principal: $PRINCIPAL")
        appendLine("Vice Principal: $VICE_PRINCIPAL")
        appendLine("Email: $EMAIL  |  Phone: $CONTACT_PHONE")
        appendLine("Student Clubs: ${CLUBS.joinToString(", ")}")
        append("Mission: Holistic, value-based and knowledge-based education in a spirit of service to humanity.")
    }
}
