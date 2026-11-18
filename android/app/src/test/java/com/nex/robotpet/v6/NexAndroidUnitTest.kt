package com.nex.robotpet.v6

import com.nex.robotpet.v6.data.AnimalSoundCatalog
import com.nex.robotpet.v6.data.NexExpression
import com.nex.robotpet.v6.data.SchoolKnowledgeRepository
import com.nex.robotpet.v6.games.MiniGameCatalog
import com.nex.robotpet.v6.memory.MemoryManager
import com.nex.robotpet.v6.network.BackendResult
import com.nex.robotpet.v6.network.NexBackendClient
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
    fun all32Expressions_existMapAndRouteByVoice() {
        assertEquals(32, NexExpression.entries.size)
        assertEquals(NexExpression.LOVE, NexExpression.fromBackendTag("LOVE_HEART"))
        assertEquals(NexExpression.HEART, NexExpression.fromBackendTag("HEART"))
        assertEquals(NexExpression.DANCE, NexExpression.fromBackendTag("dance"))
        assertEquals(NexExpression.SLEEP, NexExpression.fromBackendTag("SLEEPING"))
        assertEquals(NexExpression.SLEEPY, NexExpression.fromBackendTag("SLEEPY"))
        assertEquals(NexExpression.WINKING, NexExpression.fromBackendTag("WINK"))
        assertEquals(NexExpression.SPEAKING, NexExpression.fromBackendTag("SPEAK"))
        assertEquals(NexExpression.GREETING, NexExpression.fromBackendTag("GREET"))
        assertEquals(NexExpression.CUSTOM, NexExpression.fromBackendTag("CUSTOM"))

        val voiceToExpression = mapOf(
            "normal eyes" to NexExpression.NORMAL,
            "blink your eyes" to NexExpression.BLINK,
            "double blink" to NexExpression.DOUBLE_BLINK,
            "Hey NEX show happy eyes" to NexExpression.HAPPY,
            "very happy eyes" to NexExpression.VERY_HAPPY,
            "look sad" to NexExpression.SAD,
            "show angry face" to NexExpression.ANGRY,
            "look surprised" to NexExpression.SURPRISED,
            "sleepy eyes" to NexExpression.SLEEPY,
            "go to sleep" to NexExpression.SLEEP,
            "thinking face" to NexExpression.THINKING,
            "listening eyes" to NexExpression.LISTENING,
            "speaking eyes" to NexExpression.SPEAKING,
            "laughing face" to NexExpression.LAUGH,
            "crying eyes" to NexExpression.CRY,
            "love eyes" to NexExpression.LOVE,
            "show heart eyes" to NexExpression.HEART,
            "look excited" to NexExpression.EXCITED,
            "look curious" to NexExpression.CURIOUS,
            "confused face" to NexExpression.CONFUSED,
            "look shy" to NexExpression.SHY,
            "winking eyes" to NexExpression.WINKING,
            "gaming mode" to NexExpression.GAMING,
            "let's dance" to NexExpression.DANCE,
            "look bored" to NexExpression.BORED,
            "look scared" to NexExpression.SCARED,
            "look tired" to NexExpression.TIRED,
            "look proud" to NexExpression.PROUD,
            "look suspicious" to NexExpression.SUSPICIOUS,
            "look focused" to NexExpression.FOCUSED,
            "greeting eyes" to NexExpression.GREETING,
            "custom eyes" to NexExpression.CUSTOM
        )
        assertEquals(32, voiceToExpression.size)
        for ((phrase, expectedExpr) in voiceToExpression) {
            val routed = CommandRouter.route(phrase)
            assertTrue("Expected SetExpression for '$phrase', got $routed", routed is RoutedAction.SetExpression)
            assertEquals(expectedExpr, (routed as RoutedAction.SetExpression).expression)
        }
    }

    @Test
    fun cameraOnFaceAndPreferenceAndVoiceHelpers_workCorrectly() {
        val whatIsThis = CommandRouter.route("What is this?")
        assertTrue(whatIsThis is RoutedAction.AnalyzeCameraOnFace)

        val sceneDesc = CommandRouter.route("Describe what you see")
        assertTrue(sceneDesc is RoutedAction.AnalyzeCameraOnFace)

        val seeMe = CommandRouter.route("Can you see me?")
        assertTrue(seeMe is RoutedAction.SetFaceTrackingOnFace)
        assertTrue((seeMe as RoutedAction.SetFaceTrackingOnFace).checkPresenceOnly)

        val trackFace = CommandRouter.route("Track my face")
        assertTrue(trackFace is RoutedAction.SetFaceTrackingOnFace)
        assertTrue((trackFace as RoutedAction.SetFaceTrackingOnFace).enabled)
        assertFalse(trackFace.checkPresenceOnly)

        val stopTrack = CommandRouter.route("Stop face tracking")
        assertTrue(stopTrack is RoutedAction.SetFaceTrackingOnFace)
        assertFalse((stopTrack as RoutedAction.SetFaceTrackingOnFace).enabled)

        val openCam = CommandRouter.route("Open camera")
        assertTrue(openCam is RoutedAction.OpenScreen)
        assertEquals(ScreenTarget.VISION, (openCam as RoutedAction.OpenScreen).screen)

        val pref = CommandRouter.route("Remember that I like robotics and cricket")
        assertTrue(pref is RoutedAction.SaveUserPreference)

        assertTrue(VoiceSystemManager.isMeaningfulUtterance("Hello friend"))
        assertFalse(VoiceSystemManager.isMeaningfulUtterance("uh"))
        assertFalse(VoiceSystemManager.isMeaningfulUtterance(" "))
        assertEquals("Hello NEX friend", VoiceSystemManager.cleanTextForNaturalSpeech("**Hello** _NEX_ `friend`"))
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
        // Auto-heal missing -backend suffix typo to live deployed Render service
        assertEquals(
            "https://nex-ai-robot-pet-6-backend.onrender.com",
            MemoryManager.validateHttpsBackendUrl("https://nex-ai-robot-pet-6.onrender.com")
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
        val visionOnFace = CommandRouter.route("Hey NEX what is this?")
        assertTrue(visionOnFace is RoutedAction.AnalyzeCameraOnFace)

        val visionScreen = CommandRouter.route("Hey NEX open camera")
        assertTrue(visionScreen is RoutedAction.OpenScreen)
        assertEquals(ScreenTarget.VISION, (visionScreen as RoutedAction.OpenScreen).screen)

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

    @Test
    fun liveDeployedRenderBackend_healthCreatorChatVisionAndErrorHandling() {
        val client = NexBackendClient()
        val liveUrl = MemoryManager.DEFAULT_NEX6_BACKEND_URL

        kotlinx.coroutines.runBlocking {
            // 1. Live /health check against https://nex-ai-robot-pet-6-backend.onrender.com
            val health = client.checkHealth(liveUrl)
            assertTrue("Expected live HealthStatus Success, got $health", health is BackendResult.Success)
            val healthData = (health as BackendResult.Success).data
            assertTrue("Expected status=ok on deployed Render backend", healthData.ok)
            assertTrue("Expected gemini_configured=true on deployed Render backend", healthData.geminiConfigured)
            assertEquals("gemini-3.5-flash-lite", healthData.configuredModel)

            // 2. Live /chat creator attribution check
            val creator = client.sendChat(
                baseUrlRaw = liveUrl,
                message = "Who created you?",
                languageHint = LanguageMode.ENGLISH,
                userName = null,
                allowMemory = false,
                history = emptyList()
            )
            assertTrue("Expected live creator chat Success, got $creator", creator is BackendResult.Success)
            assertEquals("ARNAV, DIVYA AND ARYAN created me.", (creator as BackendResult.Success).data.reply)

            // 3. Real live Google Gemini AI /chat roundtrip via NexBackendClient
            val liveChat = client.sendChat(
                baseUrlRaw = liveUrl,
                message = "Hello NEX! Share one short fact about planets in one sentence.",
                languageHint = LanguageMode.ENGLISH,
                userName = "Arnav",
                allowMemory = true,
                history = emptyList()
            )
            assertTrue("Expected real live Gemini chat Success, got $liveChat", liveChat is BackendResult.Success)
            val chatData = (liveChat as BackendResult.Success).data
            assertTrue("Expected non-blank Gemini reply", chatData.reply.length > 10)
            assertFalse(chatData.reply.contains("aura", ignoreCase = true))
            assertEquals("gemini-3.5-flash-lite", chatData.modelUsed)

            // 4. Honest error reporting (never fakes AI replies) when hitting a nonexistent Render host
            val deadHost = client.sendChat(
                baseUrlRaw = "https://nex-ai-robot-pet-6-nonexistent-audit-probe.onrender.com",
                message = "Hello",
                languageHint = LanguageMode.ENGLISH,
                userName = null,
                allowMemory = false,
                history = emptyList()
            )
            assertTrue("Expected BackendResult.Error for nonexistent Render service", deadHost is BackendResult.Error)
            assertEquals("RENDER_SERVICE_NOT_FOUND", (deadHost as BackendResult.Error).code)
        }
    }
}
