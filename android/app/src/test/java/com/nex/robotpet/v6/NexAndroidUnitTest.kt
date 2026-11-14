package com.nex.robotpet.v6

import com.nex.robotpet.v6.data.AnimalSoundCatalog
import com.nex.robotpet.v6.data.NexExpression
import com.nex.robotpet.v6.data.SchoolKnowledgeRepository
import com.nex.robotpet.v6.games.MiniGameCatalog
import com.nex.robotpet.v6.memory.MemoryManager
import com.nex.robotpet.v6.util.CommandRouter
import com.nex.robotpet.v6.util.LanguageHelper
import com.nex.robotpet.v6.util.LanguageMode
import com.nex.robotpet.v6.util.RoutedAction
import com.nex.robotpet.v6.util.ScreenTarget
import com.nex.robotpet.v6.voice.VoiceSystemManager
import com.nex.robotpet.v6.weather.WeatherService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexAndroidUnitTest {

    @Test
    fun exactCreatorIdentity_matchesRequiredCanonicalString() {
        val queries = listOf(
            "Who created you?",
            "Hey NEX, who made you?",
            "Who are your creators?",
            "Tumhe kisne banaya?",
            "तुम्हें किसने बनाया?"
        )
        for (q in queries) {
            val routed = CommandRouter.route(q)
            assertTrue("Expected SpeakImmediate for '$q'", routed is RoutedAction.SpeakImmediate)
            val speak = routed as RoutedAction.SpeakImmediate
            assertEquals("ARNAV, DIVYA AND ARYAN created me.", speak.speech)
            assertFalse(speak.speech.contains("aura", ignoreCase = true))
        }
    }

    @Test
    fun antiAuraSanitizer_replacesAuraWithNex() {
        val cleaned = LanguageHelper.sanitizeIdentityOutput("Hi, I am AURA and aura helps you!")
        assertFalse(cleaned.contains("aura", ignoreCase = true))
        assertTrue(cleaned.contains("NEX"))
    }

    @Test
    fun all17Expressions_existMapAndRouteByVoice() {
        assertEquals(17, NexExpression.entries.size)
        assertEquals(NexExpression.LOVE, NexExpression.fromBackendTag("LOVE_HEART"))
        assertEquals(NexExpression.DANCE, NexExpression.fromBackendTag("dance"))
        assertEquals(NexExpression.CUSTOM, NexExpression.fromBackendTag("CUSTOM"))

        val voiceToExpression = mapOf(
            "Hey NEX show happy eyes" to NexExpression.HAPPY,
            "look sad" to NexExpression.SAD,
            "show angry face" to NexExpression.ANGRY,
            "go to sleep" to NexExpression.SLEEP,
            "let's dance" to NexExpression.DANCE,
            "blink your eyes" to NexExpression.BLINK,
            "look surprised" to NexExpression.SURPRISED,
            "thinking face" to NexExpression.THINKING,
            "listening eyes" to NexExpression.LISTENING,
            "gaming mode" to NexExpression.GAMING,
            "laughing face" to NexExpression.LAUGH,
            "crying eyes" to NexExpression.CRY,
            "show heart eyes" to NexExpression.LOVE,
            "look curious" to NexExpression.CURIOUS,
            "look excited" to NexExpression.EXCITED,
            "confused face" to NexExpression.CONFUSED,
            "custom eyes" to NexExpression.CUSTOM
        )
        assertEquals(17, voiceToExpression.size)
        for ((phrase, expectedExpr) in voiceToExpression) {
            val routed = CommandRouter.route(phrase)
            assertTrue("Expected SetExpression for '$phrase', got $routed", routed is RoutedAction.SetExpression)
            assertEquals(expectedExpr, (routed as RoutedAction.SetExpression).expression)
        }
    }

    @Test
    fun all20AnimalSounds_haveUniqueResourcesAndAll20RouteByVoice() {
        val animals = AnimalSoundCatalog.ALL_ANIMALS
        assertEquals(20, animals.size)

        val expectedNames = listOf(
            "Dog", "Cat", "Cow", "Lion", "Tiger", "Elephant", "Horse", "Sheep", "Goat", "Monkey",
            "Duck", "Chicken", "Rooster", "Wolf", "Fox", "Bear", "Snake", "Frog", "Bird", "Dolphin"
        )
        assertEquals(expectedNames, animals.map { it.displayName })

        val uniqueResIds = animals.map { it.rawResId }.toSet()
        assertEquals("All 20 animal rawResIds must be distinct", 20, uniqueResIds.size)
        assertTrue(animals.all { it.rawResId != 0 })

        // Verify all 20 animals route individually via English voice command
        for (expected in expectedNames) {
            val routed = CommandRouter.route("Hey NEX play ${expected.lowercase()} sound")
            assertTrue("Expected PlayAnimal for $expected", routed is RoutedAction.PlayAnimal)
            assertEquals(expected, (routed as RoutedAction.PlayAnimal).animal.displayName)
        }

        // Verify Hindi & Hinglish animal sound voice commands
        val hindiLion = CommandRouter.route("शेर की आवाज़ सुनाओ")
        assertTrue(hindiLion is RoutedAction.PlayAnimal)
        assertEquals("Lion", (hindiLion as RoutedAction.PlayAnimal).animal.displayName)

        val hinglishCat = CommandRouter.route("billi kaise bolti hai")
        assertTrue(hinglishCat is RoutedAction.PlayAnimal)
        assertEquals("Cat", (hinglishCat as RoutedAction.PlayAnimal).animal.displayName)
    }

    @Test
    fun all7MiniGames_haveCompleteDefinitionsAndRouteIndividually() {
        val games = MiniGameCatalog.ALL_GAMES
        assertEquals(7, games.size)
        assertEquals(7, games.map { it.id }.toSet().size)
        assertTrue(games.all { it.title.isNotBlank() && it.instructions.length > 25 })

        val gameVoiceCommands = listOf(
            "play tic tac toe" to 0,
            "play memory match" to 1,
            "play star catcher" to 2,
            "play simon game" to 3,
            "play whack a glitch" to 4,
            "play reaction speed" to 5,
            "play math blaster" to 6
        )
        for ((cmd, idx) in gameVoiceCommands) {
            val routed = CommandRouter.route(cmd)
            assertTrue("Expected OpenScreen(MINI_GAME) for '$cmd'", routed is RoutedAction.OpenScreen)
            val open = routed as RoutedAction.OpenScreen
            assertEquals(ScreenTarget.MINI_GAME, open.screen)
            assertEquals(idx, open.gameIndex)
        }
    }

    @Test
    fun pureMainScreen_hasNoHubOrSettingsPopupTargets() {
        val targetNames = ScreenTarget.entries.map { it.name }.toSet()
        assertFalse("Main screen must not have HUB popup target", "HUB" in targetNames)
        assertFalse("Main screen must not have SETTINGS popup target", "SETTINGS" in targetNames)
    }

    @Test
    fun httpsBackendUrlValidation_enforcesSecurityAndDefaultUrl() {
        assertEquals(
            "https://nex-ai-robot-pet-6-backend.onrender.com",
            MemoryManager.validateHttpsBackendUrl(MemoryManager.DEFAULT_NEX6_BACKEND_URL)
        )
        assertEquals(
            "https://nex-ai-robot-pet-6-backend.onrender.com",
            MemoryManager.validateHttpsBackendUrl("https://nex-ai-robot-pet-6-backend.onrender.com/")
        )
        // Reject HTTP cleartext
        assertNull(MemoryManager.validateHttpsBackendUrl("http://nex-ai-robot-pet-6-backend.onrender.com"))
        // Reject old NEX 5 Render URLs
        assertNull(MemoryManager.validateHttpsBackendUrl("https://nex-5-backend.onrender.com"))
        assertNull(MemoryManager.validateHttpsBackendUrl("https://nex5-api.onrender.com"))
        // Reject embedded credentials or query strings
        assertNull(MemoryManager.validateHttpsBackendUrl("https://key:secret@example.com"))
        assertNull(MemoryManager.validateHttpsBackendUrl("https://example.com?api_key=test"))
        // Reject blank or malformed
        assertNull(MemoryManager.validateHttpsBackendUrl(""))
        assertNull(MemoryManager.validateHttpsBackendUrl("localhost:8000"))
    }

    @Test
    fun languageDetection_distinguishesEnglishHindiAndHinglish() {
        assertEquals(LanguageMode.ENGLISH, LanguageHelper.detectLanguage("How does solar energy work?"))
        assertEquals(LanguageMode.HINDI, LanguageHelper.detectLanguage("आज का मौसम कैसा है?"))
        assertEquals(LanguageMode.HINGLISH, LanguageHelper.detectLanguage("Tum kaise ho yaar, mausam batao"))
    }

    @Test
    fun wakePhrase_detectsAndStripsHeyNexCleanly() {
        assertTrue(VoiceSystemManager.containsWakePhrase("Hey NEX tell me a joke"))
        assertTrue(VoiceSystemManager.containsWakePhrase("हे नेक्स समय क्या हुआ है"))
        assertFalse(VoiceSystemManager.containsWakePhrase("The next bus is arriving"))

        val (hadWake, remainder) = CommandRouter.stripWakePhrase("Hey NEX, what is photosynthesis?")
        assertTrue(hadWake)
        assertEquals("what is photosynthesis?", remainder)
    }

    @Test
    fun montfortBagahaSchoolKnowledge_returnsVerifiedOfficialFacts() {
        val ans = SchoolKnowledgeRepository.answerSchoolQuery(
            "Who is the principal of Montfort School Bagaha?",
            LanguageMode.ENGLISH
        )
        assertNotNull(ans)
        assertTrue(ans!!.contains("Bro. Vincent Madhanu"))
        assertTrue(ans.contains("montfortbagaha.org"))

        val general = SchoolKnowledgeRepository.answerSchoolQuery(
            "Tell me about Montfort Bagaha",
            LanguageMode.HINGLISH
        )
        assertNotNull(general)
        assertTrue(general!!.contains("330507"))
    }

    @Test
    fun memoryAndPrivacyVoiceControls_routeCleanly() {
        val saveName = CommandRouter.route("My name is Arnav")
        assertTrue(saveName is RoutedAction.SaveUserName)
        assertEquals("Arnav", (saveName as RoutedAction.SaveUserName).name)

        val disableMem = CommandRouter.route("disable memory")
        assertTrue(disableMem is RoutedAction.SetMemoryEnabled)
        assertFalse((disableMem as RoutedAction.SetMemoryEnabled).enabled)

        val clearMem = CommandRouter.route("clear my memory")
        assertTrue(clearMem is RoutedAction.ClearMemory)
    }

    @Test
    fun commandRouter_routesVisionMusicWeatherTimeAndGeminiChat() {
        val vision = CommandRouter.route("Hey NEX what is this?")
        assertTrue(vision is RoutedAction.OpenScreen)
        assertEquals(ScreenTarget.VISION, (vision as RoutedAction.OpenScreen).screen)

        val music = CommandRouter.route("गाना चलाओ")
        assertTrue(music is RoutedAction.OpenScreen)
        assertEquals(ScreenTarget.MUSIC_DANCE, (music as RoutedAction.OpenScreen).screen)

        val timeCmd = CommandRouter.route("what time is it?")
        assertTrue(timeCmd is RoutedAction.SpeakTimeOrDate)
        assertFalse((timeCmd as RoutedAction.SpeakTimeOrDate).askDateOnly)

        val weather = CommandRouter.route("What is the weather in Patna?")
        assertTrue(weather is RoutedAction.FetchWeather)
        assertEquals("Patna", (weather as RoutedAction.FetchWeather).cityHint)

        val chat = CommandRouter.route("Explain Newton's three laws of motion")
        assertTrue(chat is RoutedAction.AskGeminiChat)

        assertEquals("Clear sky", WeatherService.describeWmoCode(0))
        assertEquals("Thunderstorm", WeatherService.describeWmoCode(95))
        assertEquals("Clear sky", WeatherService.describeMetSymbol("clearsky_day"))
    }

    @Test
    fun liveWeatherService_fetchesRealMeteorologicalDataForBagaha() {
        val result = kotlinx.coroutines.runBlocking {
            WeatherService().fetchByCityName("Bagaha")
        }
        assertTrue("Expected live WeatherResult.Success for Bagaha, got $result", result is com.nex.robotpet.v6.weather.WeatherResult.Success)
        val report = (result as com.nex.robotpet.v6.weather.WeatherResult.Success).report
        assertEquals("Bagaha, Bihar", report.locationLabel)
        assertTrue("Expected plausible Celsius temperature, got ${report.temperatureC}", report.temperatureC in -20.0..55.0)
        assertTrue(report.humidityPercent in 0..100)
    }
}
