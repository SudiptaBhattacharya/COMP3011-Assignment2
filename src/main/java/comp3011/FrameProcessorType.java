package comp3011;

/**
 * Identifies the frame effects selected through command-line options.
 *
 * The command-line controller records these identifiers in argument order.
 * VideoPlayerModel then creates and owns the corresponding FrameProcessor
 * instances.
 */
public enum FrameProcessorType {
    NUMBER,
    SCRATCH,
    FLICKER,
    BLACK_AND_WHITE,
    YELLOW,
    VIGNETTE,
    DUST,
    JITTER,
    MOTTLE,
    BLEED,
    PEPPER
}
