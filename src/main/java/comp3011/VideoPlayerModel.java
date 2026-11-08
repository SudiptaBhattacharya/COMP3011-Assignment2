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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing. Independent frames are processed on a bounded pool of
 * platform threads. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    private static final int WORKER_COUNT = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
    private static final int MAX_IN_FLIGHT_FRAMES = WORKER_COUNT * 2;

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final Queue<SubmittedFrame> pendingFrames = new ArrayDeque<>();
    // Platform threads suit CPU-bound effects; virtual threads do not add CPU capacity.
    private final ThreadPoolExecutor frameExecutor = new ThreadPoolExecutor(
            WORKER_COUNT, WORKER_COUNT, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_IN_FLIGHT_FRAMES),
            Thread.ofPlatform().name("frame-effect-", 0).factory());
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private ProcessingContext processingContext;
    private boolean inputExhausted;
    private boolean showSeekPreview;
    private long playbackGeneration;
    private long nextSequenceNumber;
    private boolean prepareNextFrameQueued; // True only when the event loop needs to call our prepareNextFrame method.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler,
            List<FrameProcessorType> frameProcessorTypes) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = new ArrayList<>();
        for (FrameProcessorType type : frameProcessorTypes) {
            frameProcessors.add(switch (type) {
                case NUMBER -> new FrameNumberer();
                case SCRATCH -> new FrameScratcher();
                case FLICKER -> new FrameFlickerer();
                case BLACK_AND_WHITE -> new FrameBlackAndWhiter();
                case YELLOW -> new FrameYellower();
                case VIGNETTE -> new FrameVignetter();
                case DUST -> new FrameDuster();
                case JITTER -> new FrameJitterer();
                case MOTTLE -> new FrameMottler();
                case BLEED -> new FrameBleeder();
                case PEPPER -> new FramePepperer();
            });
        }
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            showSeekPreview = false;
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
        frameExecutor.shutdownNow();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        invalidatePendingFrames();
        pendingAudio.clear();
        preparedFrame = null;
        inputExhausted = false;
        showSeekPreview = pauseRequested;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            long pauseDurationNs = now - pauseStartedNs;
            playbackStartNs += pauseDurationNs;
            if (preparedFrame != null) {
                PreparedFrame frame = preparedFrame;
                preparedFrame = new PreparedFrame(frame.image(), frame.frameNumber(),
                        frame.mediaTimestampUs(), frame.logicalTimestampUs(),
                        frame.targetTimeNs() + pauseDurationNs, frame.preparedAtNs());
            }
        }
        pauseStartedNs = 0;
    }

    private void prepareNextFrame() {
        if (!playbackOpen || inputExhausted || inFlightFrameCount() >= MAX_IN_FLIGHT_FRAMES) {
            return;
        }

        try {
            submitNextVideoFrame();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void submitNextVideoFrame() throws Exception {
        while (playbackOpen) {
            Frame frame = grabFrame();
            if (frame == null) {
                inputExhausted = true;
                if (firstTimestampUs == NO_SEEK_REQUEST) {
                    finishPlayback();
                }
                return;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                processingContext = new ProcessingContext(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()), frameProcessors);
                frameProcessorsInitialised = true;
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);
            long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
            long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;

            // FFmpeg may reuse its native image buffer on the next grab. A deep clone
            // gives the worker exclusive ownership while decoding continues.
            Frame ownedFrame = frame.clone();
            FrameJob job = new FrameJob(ownedFrame, info, processingContext,
                    nextSequenceNumber++, playbackGeneration, logicalTimestampUs, relativeTimestampUs);
            try {
                pendingFrames.add(new SubmittedFrame(job, frameExecutor.submit(job)));
            } catch (RuntimeException e) {
                job.release();
                throw e;
            }
            return;
        }
    }

    // The GUI heartbeat never waits for a worker. It takes only the oldest
    // completed result, so parallel processing cannot reorder displayed frames.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        writeDueAudio(now);
        collectPreparedFrame();
        if (!playbackOpen) {
            return;
        }
        if (preparedFrame != null && (showSeekPreview
                || (!pauseRequested && preparedFrame.targetTimeNs() <= now))) {
            displayPreparedFrame(now);
            showSeekPreview = false;
        }

        if (inputExhausted && inFlightFrameCount() == 0 && pendingAudio.isEmpty()) {
            finishPlayback();
            return;
        }

        if (!pauseRequested && !inputExhausted
                && inFlightFrameCount() < MAX_IN_FLIGHT_FRAMES && !prepareNextFrameQueued) {
            prepareNextFrameQueued = true;
            long queuedGeneration = playbackGeneration;
            Platform.runLater(() -> {
                if (queuedGeneration != playbackGeneration) {
                    return;
                }
                prepareNextFrameQueued = false;
                if (!pauseRequested) {
                    prepareNextFrame();
                }
            });
        }
    }

    private int inFlightFrameCount() {
        return pendingFrames.size() + (preparedFrame == null ? 0 : 1);
    }

    private void collectPreparedFrame() {
        if (preparedFrame != null || pendingFrames.isEmpty()) {
            return;
        }

        SubmittedFrame submitted = pendingFrames.peek();
        if (!submitted.future().isDone()) {
            return;
        }

        pendingFrames.remove();
        try {
            ProcessedFrame result = submitted.future().get();
            if (result.generation() != playbackGeneration) {
                submitted.job().release();
                return;
            }

            Image image;
            try {
                image = converter.convert(submitted.job().frame());
            } finally {
                submitted.job().release();
            }
            preparedFrame = new PreparedFrame(image, result.info().frameNumber(),
                    result.info().timestampUs(), result.logicalTimestampUs(),
                    playbackStartNs + result.relativeTimestampUs() * 1_000L, System.nanoTime());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handlePlaybackError(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            handlePlaybackError(cause instanceof Exception exception
                    ? exception : new Exception("Frame processing failed", cause));
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        invalidatePendingFrames();
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;
        processingContext = null;
        inputExhausted = false;
        showSeekPreview = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private void invalidatePendingFrames() {
        playbackGeneration++;
        for (SubmittedFrame submitted : pendingFrames) {
            submitted.job().cancel();
            submitted.future().cancel(true);
        }
        pendingFrames.clear();
        frameExecutor.purge();
        preparedFrame = null;
        prepareNextFrameQueued = false;
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    /**
     * Applies every selected processor to one frame in command-line order.
     *
     * Processing remains sequential within an individual frame because every
     * processor can modify the output produced by the preceding processor.
     */
    private void processFrame(Frame frame, InfoFrame frameInfo, ProcessingContext context) throws Exception {
        for (FrameProcessor processor : context.processors()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Frame processing cancelled");
            }
            processor.process(frame, frameInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private static final class ProcessingContext {
        private final ThreadLocal<List<FrameProcessor>> workerProcessors;

        ProcessingContext(InfoVideo videoInfo, List<FrameProcessor> prototypes) {
            List<FrameProcessor> orderedPrototypes = List.copyOf(prototypes);
            workerProcessors = ThreadLocal.withInitial(() -> {
                List<FrameProcessor> copies = new ArrayList<>(orderedPrototypes.size());
                try {
                    for (FrameProcessor prototype : orderedPrototypes) {
                        FrameProcessor copy = prototype.copyForWorker();
                        copy.initialise(videoInfo);
                        copies.add(copy);
                    }
                } catch (Exception e) {
                    throw new CompletionException("Frame processor initialisation failed", e);
                }
                return List.copyOf(copies);
            });
        }

        List<FrameProcessor> processors() {
            return workerProcessors.get();
        }
    }

    private final class FrameJob implements Callable<ProcessedFrame> {
        private static final int NEW = 0;
        private static final int RUNNING = 1;
        private static final int DONE = 2;
        private static final int RELEASED = 3;

        private final Frame frame;
        private final InfoFrame info;
        private final ProcessingContext context;
        private final long sequenceNumber;
        private final long generation;
        private final long logicalTimestampUs;
        private final long relativeTimestampUs;
        private final AtomicInteger lifecycle = new AtomicInteger(NEW);
        private volatile boolean cancelled;

        FrameJob(Frame frame, InfoFrame info, ProcessingContext context,
                long sequenceNumber, long generation, long logicalTimestampUs, long relativeTimestampUs) {
            this.frame = frame;
            this.info = info;
            this.context = context;
            this.sequenceNumber = sequenceNumber;
            this.generation = generation;
            this.logicalTimestampUs = logicalTimestampUs;
            this.relativeTimestampUs = relativeTimestampUs;
        }

        Frame frame() {
            return frame;
        }

        @Override
        public ProcessedFrame call() throws Exception {
            if (!lifecycle.compareAndSet(NEW, RUNNING)) {
                return null;
            }
            try {
                // Effects on one frame depend on preceding effects; separate frames do not.
                processFrame(frame, info, context);
                ProcessedFrame result = new ProcessedFrame(sequenceNumber, generation,
                        info, logicalTimestampUs, relativeTimestampUs);
                lifecycle.set(DONE);
                if (cancelled) {
                    release();
                }
                return result;
            } catch (Exception | Error e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                lifecycle.set(DONE);
                release();
                throw e;
            }
        }

        void cancel() {
            cancelled = true;
            release();
        }

        void release() {
            int state = lifecycle.get();
            while (state == NEW || state == DONE) {
                if (lifecycle.compareAndSet(state, RELEASED)) {
                    frame.close();
                    return;
                }
                state = lifecycle.get();
            }
        }
    }

    private record SubmittedFrame(FrameJob job, Future<ProcessedFrame> future) {
    }

    private record ProcessedFrame(long sequenceNumber, long generation, InfoFrame info,
            long logicalTimestampUs, long relativeTimestampUs) {
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
