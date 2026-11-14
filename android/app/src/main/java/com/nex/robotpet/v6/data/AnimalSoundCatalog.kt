package com.nex.robotpet.v6.data

import androidx.annotation.RawRes
import com.nex.robotpet.v6.R

data class AnimalSoundItem(
    val index: Int,
    val id: String,
    val displayName: String,
    @RawRes val rawResId: Int,
    val reactionExpression: NexExpression,
    val voiceAliases: List<String>,
)

/**
 * Verified catalog of all 20 real playable animal audio assets (Part F).
 */
object AnimalSoundCatalog {

    val ALL_ANIMALS: List<AnimalSoundItem> = listOf(
        AnimalSoundItem(
            1, "dog", "Dog", R.raw.animal_dog, NexExpression.HAPPY,
            listOf("dog", "puppy", "bark", "woof", "kutta", "कुत्ता")
        ),
        AnimalSoundItem(
            2, "cat", "Cat", R.raw.animal_cat, NexExpression.CURIOUS,
            listOf("cat", "kitten", "meow", "billi", "बिल्ली")
        ),
        AnimalSoundItem(
            3, "cow", "Cow", R.raw.animal_cow, NexExpression.HAPPY,
            listOf("cow", "cattle", "moo", "gaay", "gai", "गाय")
        ),
        AnimalSoundItem(
            4, "lion", "Lion", R.raw.animal_lion, NexExpression.ANGRY,
            listOf("lion", "sher", "शेर", "simha")
        ),
        AnimalSoundItem(
            5, "tiger", "Tiger", R.raw.animal_tiger, NexExpression.EXCITED,
            listOf("tiger", "bagh", "बाघ")
        ),
        AnimalSoundItem(
            6, "elephant", "Elephant", R.raw.animal_elephant, NexExpression.EXCITED,
            listOf("elephant", "haathi", "hathi", "हाथी")
        ),
        AnimalSoundItem(
            7, "horse", "Horse", R.raw.animal_horse, NexExpression.EXCITED,
            listOf("horse", "neigh", "whinny", "ghoda", "घोड़ा")
        ),
        AnimalSoundItem(
            8, "sheep", "Sheep", R.raw.animal_sheep, NexExpression.HAPPY,
            listOf("sheep", "lamb", "baa", "bhed", "भेड़")
        ),
        AnimalSoundItem(
            9, "goat", "Goat", R.raw.animal_goat, NexExpression.LAUGH,
            listOf("goat", "bakri", "बकरी")
        ),
        AnimalSoundItem(
            10, "monkey", "Monkey", R.raw.animal_monkey, NexExpression.LAUGH,
            listOf("monkey", "chimp", "bandar", "बंदर")
        ),
        AnimalSoundItem(
            11, "duck", "Duck", R.raw.animal_duck, NexExpression.HAPPY,
            listOf("duck", "quack", "batakh", "बतख")
        ),
        AnimalSoundItem(
            12, "chicken", "Chicken", R.raw.animal_chicken, NexExpression.CURIOUS,
            listOf("chicken", "hen", "cluck", "murgi", "मुर्गी")
        ),
        AnimalSoundItem(
            13, "rooster", "Rooster", R.raw.animal_rooster, NexExpression.SURPRISED,
            listOf("rooster", "cock", "murga", "मुर्गा")
        ),
        AnimalSoundItem(
            14, "wolf", "Wolf", R.raw.animal_wolf, NexExpression.SURPRISED,
            listOf("wolf", "howl", "bhediya", "भेड़िया")
        ),
        AnimalSoundItem(
            15, "fox", "Fox", R.raw.animal_fox, NexExpression.CURIOUS,
            listOf("fox", "lomdi", "लोमड़ी")
        ),
        AnimalSoundItem(
            16, "bear", "Bear", R.raw.animal_bear, NexExpression.ANGRY,
            listOf("bear", "grizzly", "bhalu", "भालू")
        ),
        AnimalSoundItem(
            17, "snake", "Snake", R.raw.animal_snake, NexExpression.SURPRISED,
            listOf("snake", "cobra", "hiss", "rattlesnake", "saanp", "सांप", "साँप")
        ),
        AnimalSoundItem(
            18, "frog", "Frog", R.raw.animal_frog, NexExpression.HAPPY,
            listOf("frog", "toad", "croak", "ribbit", "mendhak", "मेंढक")
        ),
        AnimalSoundItem(
            19, "bird", "Bird", R.raw.animal_bird, NexExpression.LOVE,
            listOf("bird", "sparrow", "chirp", "chidiya", "pakshi", "चिड़िया", "पक्षी")
        ),
        AnimalSoundItem(
            20, "dolphin", "Dolphin", R.raw.animal_dolphin, NexExpression.EXCITED,
            listOf("dolphin", "डॉल्फ़िन", "डॉल्फिन")
        ),
    )

    fun findByText(query: String): AnimalSoundItem? {
        val lower = query.lowercase()
        return ALL_ANIMALS.firstOrNull { item ->
            item.voiceAliases.any { alias ->
                // Unicode-aware word boundary check (handles English, Hinglish, and Devanagari Hindi accurately)
                Regex("(^|[^\\p{L}\\p{M}\\p{N}])${Regex.escape(alias.lowercase())}([^\\p{L}\\p{M}\\p{N}]|$)")
                    .containsMatchIn(lower)
            }
        }
    }
}
