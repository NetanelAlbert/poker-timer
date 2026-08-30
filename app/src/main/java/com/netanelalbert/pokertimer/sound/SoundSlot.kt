package com.netanelalbert.pokertimer.sound

/**
 * Which of the three sounds is being played, and therefore what to fall back to when the user has
 * not picked anything.
 *
 * The distinction matters: the blinds-up alarm wants the device's alarm tone, but a level-start
 * chime or a pre-end warning played with that same tone is far too long — it is a confirmation
 * blip, not an alarm — so those default to short bundled tones instead.
 */
enum class SoundSlot { ALARM, WARNING, CHIME }
