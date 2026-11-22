package app.compresor.execution

import app.compresor.tx

import app.compresor.engine.VideoSettings
import app.compresor.engine.AudioSettings
import app.compresor.engine.InfoAudio
import app.compresor.engine.CutRange
import app.compresor.engine.CropRegion

/** What the service knows how to do with a video. */
sealed interface Task {
    data class Compress(val settings: VideoSettings) : Task
    data class Cut(val cutRange: CutRange) : Task
    data class Crop(val cropArea: CropRegion) : Task
    data class Audio(val settings: AudioSettings, val info: InfoAudio) : Task
}

val Task.verb: String
    get() = when (this) { is Task.Compress -> tx("Compressing"); is Task.Cut -> tx("Cutting"); is Task.Crop -> tx("Cropping"); is Task.Audio -> tx("Converting") }

/** Added to the name of the saved file: video_comp.mp4, video_cut.mp4, video_crop.mp4. */
val Task.suffix: String
    get() = when (this) { is Task.Compress -> "_comp"; is Task.Cut -> "_cut"; is Task.Crop -> "_crop"; is Task.Audio -> "_conv" }

/** Mark added to the name when saving: video_comp, video_cut, video_crop (with a number if it already exists). */
val Task.mark: String
    get() = when (this) { is Task.Compress -> "comp"; is Task.Cut -> "cut"; is Task.Crop -> "crop"; is Task.Audio -> "conv" }
