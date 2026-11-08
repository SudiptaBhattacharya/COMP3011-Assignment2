/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;

import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;
    private final List<FrameProcessorType> frameProcessorTypes =
            new ArrayList<>();

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }
    
    //new frame processor type logic
    /**
     * Returns an immutable copy in the same order as the command-line options.
     */
    public List<FrameProcessorType> getFrameProcessorTypes() {
        return List.copyOf(frameProcessorTypes);
    }


    public boolean shouldLaunchApplication() {
    	return errorMessage == null
                && !helpRequested
                && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        boolean optionsFinished = false;

        for (String arg : args) {
            /*
             * Do not stop parsing after an error. A later -h or --help must still
             * be recognised so that help is displayed, while the original error
             * continues to determine the non-zero exit status.
             */
            if (optionsFinished) {
                videoFiles.add(arg);
            } else if ("--".equals(arg)) {
                optionsFinished = true;
            } else if (arg.startsWith("--")) {
                parseLongOption(arg);
            } else if (arg.startsWith("-") && arg.length() > 1) {
                parseShortOptions(arg);
            } else {
                videoFiles.add(arg);
            }
        }

        /*
         * Preserve an option-parsing error as the first and most useful error,
         * but only after every argument has been inspected for help.
         */
        if (errorMessage != null) {
            return;
        }

        if (videoFiles.size() > 1) {
            setError("Usage: VideoPlayer [options] [video-file]");
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.getFirst());

            if (!videoFile.isFile()) {
                setError("File not found: " + videoFile.getPath());
                videoFile = null;
            }
        } else if (!helpRequested) {
            setError("No video file specified.");
        }
    }

    private void parseLongOption(String option) {
        switch (option) {
            case "--help" -> helpRequested = true;
            case "--audio" -> audioRequested = true;
            case "--maximise" -> maximiseRequested = true;
            case "--monitor-1" -> setDisplayId(1);
            case "--monitor-2" -> setDisplayId(2);

            case "--number-frames" ->
                    addProcessor(FrameProcessorType.NUMBER);
            case "--scratch-frames" ->
                    addProcessor(FrameProcessorType.SCRATCH);
            case "--flicker-frames" ->
                    addProcessor(FrameProcessorType.FLICKER);
            case "--black-and-white" ->
                    addProcessor(FrameProcessorType.BLACK_AND_WHITE);
            case "--yellow-frames" ->
                    addProcessor(FrameProcessorType.YELLOW);
            case "--vignette-frames" ->
                    addProcessor(FrameProcessorType.VIGNETTE);
            case "--dust-frames" ->
                    addProcessor(FrameProcessorType.DUST);
            case "--jitter-frames" ->
                    addProcessor(FrameProcessorType.JITTER);
            case "--mottle-frames" ->
                    addProcessor(FrameProcessorType.MOTTLE);
            case "--bleed-frames" ->
                    addProcessor(FrameProcessorType.BLEED);
            case "--pepper-frames" ->
                    addProcessor(FrameProcessorType.PEPPER);

            default -> setError("Unknown option: " + option);
        }
    }

    /**
     * Parses every character after the leading dash independently.
     *
     * For example, -nssnf is interpreted as:
     * number, scratch, scratch, number, flicker.
     */
    private void parseShortOptions(String option) {
        for (int index = 1;
                index < option.length();
                index++) {

            char shortOption = option.charAt(index);

            switch (shortOption) {
                case 'h' -> helpRequested = true;
                case 'a' -> audioRequested = true;
                case 'x' -> maximiseRequested = true;
                case '1' -> setDisplayId(1);
                case '2' -> setDisplayId(2);

                case 'n' -> addProcessor(FrameProcessorType.NUMBER);
                case 's' -> addProcessor(FrameProcessorType.SCRATCH);
                case 'f' -> addProcessor(FrameProcessorType.FLICKER);
                case 'w' ->
                        addProcessor(FrameProcessorType.BLACK_AND_WHITE);
                case 'y' -> addProcessor(FrameProcessorType.YELLOW);
                case 'v' -> addProcessor(FrameProcessorType.VIGNETTE);
                case 'd' -> addProcessor(FrameProcessorType.DUST);
                case 'j' -> addProcessor(FrameProcessorType.JITTER);
                case 'm' -> addProcessor(FrameProcessorType.MOTTLE);
                case 'b' -> addProcessor(FrameProcessorType.BLEED);
                case 'p' -> addProcessor(FrameProcessorType.PEPPER);

                default -> setError("Unknown option: -" + shortOption);
            }
        }
    }

    private void addProcessor(FrameProcessorType type) {
        frameProcessorTypes.add(type);
    }

    private void setDisplayId(int requestedDisplayId) {
        if (displayId != null && displayId != requestedDisplayId) {
            setError("Only one display option can be used");
            return;
        }

        displayId = requestedDisplayId;
    }

    /**
     * Retains the first error because it is normally the most useful one.
     */
    private void setError(String message) {
        if (errorMessage == null) {
            errorMessage = message;
        }
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();

        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();

        System.out.println("Frame processors:");
        System.out.println(
                "  -n, --number-frames    Render the frame number onto each frame");
        System.out.println(
                "  -s, --scratch-frames   Render vertical film scratches");
        System.out.println(
                "  -f, --flicker-frames   Randomly dim frames");
        System.out.println(
                "  -w, --black-and-white  Convert frames to black and white");
        System.out.println(
                "  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println(
                "  -v, --vignette-frames  Darken the frame edges");
        System.out.println(
                "  -d, --dust-frames      Render dust and hair marks");
        System.out.println(
                "  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println(
                "  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println(
                "  -b, --bleed-frames     Bleed light into frames");
        System.out.println(
                "  -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println();

        System.out.println(
                "Frame processors are applied in command-line order and may be repeated.");
        System.out.println(
                "Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println(
                "converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
