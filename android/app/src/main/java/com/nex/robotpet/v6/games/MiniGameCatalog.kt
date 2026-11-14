package com.nex.robotpet.v6.games

data class MiniGameSpec(
    val index: Int,
    val id: String,
    val title: String,
    val shortSummary: String,
    val instructions: String
)

object MiniGameCatalog {

    val ALL_GAMES: List<MiniGameSpec> = listOf(
        MiniGameSpec(
            index = 0,
            id = "tic_tac_toe",
            title = "1. Tic-Tac-Toe vs NEX",
            shortSummary = "Outsmart NEX's tactical AI on a 3×3 cyber grid.",
            instructions = "Tap any empty square to place X. Get 3 in a row horizontally, vertically, or diagonally before NEX places 3 O's! Tap Restart anytime for a fresh board."
        ),
        MiniGameSpec(
            index = 1,
            id = "memory_match",
            title = "2. Memory Matrix Match",
            shortSummary = "Flip 12 cyber cards to match all 6 hidden tech pairs.",
            instructions = "Tap two cards to reveal their symbols. Match all 6 pairs in as few moves as possible to earn a higher score!"
        ),
        MiniGameSpec(
            index = 2,
            id = "star_catcher",
            title = "3. Catch the Falling Stars",
            shortSummary = "Slide NEX's energy shield to catch stars & dodge red glitches.",
            instructions = "Slide your finger left or right anywhere in the arena to move NEX's cyan collector paddle. Catch Gold Stars (+10) & Cyan Cores (+25), and dodge Red Glitch Bolts (3 lives)!"
        ),
        MiniGameSpec(
            index = 3,
            id = "simon_cyber",
            title = "4. NEX Simon Sequence",
            shortSummary = "Watch NEX flash a color sequence and repeat it from memory.",
            instructions = "Watch the 4 glowing pads carefully while NEX plays the sequence. When the banner says 'Your Turn!', tap the pads in the exact same order!"
        ),
        MiniGameSpec(
            index = 4,
            id = "whack_glitch",
            title = "5. Whack-a-Glitch (30s)",
            shortSummary = "Tap red cyber-glitches fast while avoiding friendly NEX bots.",
            instructions = "You have 30 seconds! Tap RED GLITCH BUGS (+10 pts) before they disappear. Avoid tapping friendly CYAN NEX tiles (-5 pts)!"
        ),
        MiniGameSpec(
            index = 5,
            id = "reaction_reflex",
            title = "6. Reaction Speed Reflex",
            shortSummary = "Measure your millisecond reflex speed over 5 rounds.",
            instructions = "Tap the arena to arm. Wait patiently while it is RED—do not tap early! The instant it flashes NEON GREEN, tap as fast as humanly possible!"
        ),
        MiniGameSpec(
            index = 6,
            id = "math_blaster",
            title = "7. Math Blaster Quiz",
            shortSummary = "Solve rapid-fire math puzzles in 45 seconds for combo points.",
            instructions = "Solve as many addition, subtraction, and multiplication questions as you can in 45 seconds! Consecutive correct answers build a streak bonus."
        )
    )
}
