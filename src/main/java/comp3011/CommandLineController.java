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
            if (errorMessage != null) {
                break;
            }

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

        if (errorMessage != null) {
            return;
        }

        if (videoFiles.size() > 1) {
            setError("Usage: VideoPlayer [options] [video-file]");
            return;
        }

        if (videoFiles.size() == 1) {
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
                    addProcessor(