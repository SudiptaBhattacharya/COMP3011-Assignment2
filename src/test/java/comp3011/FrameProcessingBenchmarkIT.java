package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.Test;

/** A reproducible effect-throughput comparison; it does not measure audio or display pacing. */
class FrameProcessingBenchmarkIT {
    private static final int FRAME_COUNT = 24;

    private record InputFrame(Frame frame, InfoFrame info) {
    }

    private record Input(List<InputFrame> frames, InfoVideo videoInfo, String description) {
    }

    private record TimedResult(List<Long> checksums, long elapsedNs) {
    }

    @Test
    void compareSequentialFourAndSevenWorkers() throws Exception {
        Input input = loadInput();
        try {
            List<FrameProcessor> effects = List.of(
                    new FrameNumberer(), new FrameScratcher(), new FrameScratcher(),
                    new FrameNumberer(), new FrameFlickerer(), new FrameBlackAndWhiter(),
                    new FrameYellower(), new FrameVignetter(), new FrameDuster(),
                    new FrameJitterer(), new FrameMottler(), new FrameBleeder(),
                    new FramePepperer());

            TimedResult sequential = runSequential(input, effects);
            TimedResult four = runConcurrent(input, effects, 4);
            TimedResult seven = runConcurrent(input, effects, 7);
            assertEquals(sequential.checksums(), four.checksums());
            assertEquals(sequential.checksums(), seven.checksums());

            System.out.printf("Effect benchmark input=%s frames=%d size=%dx%d effects=13%n",
                    input.description(), input.frames().size(), input.videoInfo().imageWidth(),
                    input.videoInfo().imageHeight());
            System.out.printf("Effect benchmark sequential=%.1f ms four-workers=%.1f ms seven-workers=%.1f ms%n",
                    milliseconds(sequential.elapsedNs()), milliseconds(four.elapsedNs()),
                    milliseconds(seven.elapsedNs()));
        } finally {
            for (InputFrame frame : input.frames()) {
                frame.frame().close();
            }
        }
    }

    private static Input loadInput() throws Exception {
        String configured = System.getProperty("comp3011.benchmarkVideo");
        File file = configured == null
                ? Path.of(System.getProperty("user.home"), "Downloads", "simpsons.mp4").toFile()
                : new File(configured);
        return file.isFile() ? videoInput(file) : syntheticInput();
    }

    private static Input videoInput(File file) throws Exception {
        List<InputFrame> frames = new ArrayList<>();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
            grabber.start();
            while (frames.size() < FRAME_COUNT) {
                Frame borrowed = grabber.grabImage();
                if (borrowed == null) {
                    break;
                }
                frames.add(new InputFrame(borrowed.clone(),
                        new InfoFrame(grabber.getFrameNumber(), grabber.getTimestamp())));
            }
            if (frames.isEmpty()) {
                throw new IllegalArgumentException("Benchmark video has no image frames: " + file);
            }
            Frame first = frames.getFirst().frame();
            double rate = grabber.getFrameRate();
            InfoVideo video = new InfoVideo(mediaName(file), grabber.getLengthInVideoFrames(),
                    first.imageWidth, first.imageHeight, first.imageDepth,
                    first.imageChannels, first.imageStride, rate, (int) Math.round(rate),
                    rate > 0 ? (int) Math.round(1_000_000.0 / rate) : 0,
                    grabber.getPixelFormat());
            return new Input(frames, video, file.getPath());
        } catch (Exception e) {
            for (InputFrame frame : frames) {
                frame.frame().close();
            }
            throw e;
        }
    }

    private static Input syntheticInput() {
        int width = 320;
        int height = 180;
        List<InputFrame> frames = new ArrayList<>();
        for (int number = 1; number <= FRAME_COUNT; number++) {
            Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
            ByteBuffer pixels = (ByteBuffer) frame.image[0];
            for (int index = 0; index < frame.imageStride * height; index++) {
                pixels.put(index, (byte) ((index * 31 + number * 17) & 0xff));
            }
            frames.add(new InputFrame(frame, new InfoFrame(number, number * 41_667L)));
        }
        Frame first = frames.getFirst().frame();
        InfoVideo video = new InfoVideo("synthetic-benchmark", FRAME_COUNT, width, height,
                first.imageDepth, first.imageChannels, first.imageStride,
                24.0, 24, 41_667, 0);
        return new Input(frames, video, "synthetic");
    }

    private static TimedResult runSequential(Input input, List<FrameProcessor> effects) throws Exception {
        List<FrameProcessor> chain = copies(effects, input.videoInfo());
        List<Long> checksums = new ArrayList<>();
        long started = System.nanoTime();
        for (InputFrame source : input.frames()) {
            try (Frame frame = source.frame().clone()) {
                apply(chain, frame, source.info());
                checksums.add(checksum(frame));
            }
        }
        return new TimedResult(checksums, System.nanoTime() - started);
    }

    private static TimedResult runConcurrent(Input input, List<FrameProcessor> effects, int workers)
            throws Exception {
        ThreadLocal<List<FrameProcessor>> chains = ThreadLocal.withInitial(() -> {
            try {
                return copies(effects, input.videoInfo());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        List<Long> checksums = new ArrayList<>();
        long started = System.nanoTime();
        try (var executor = new OrderedFrameExecutor<Long>(workers, workers * 2)) {
            int submitted = 0;
            while (submitted < input.frames().size() || executor.outstanding() > 0) {
                while (submitted < input.frames().size()
                        && executor.outstanding() < workers * 2) {
                    InputFrame source = input.frames().get(submitted++);
                    Frame owned = source.frame().clone();
                    executor.submit(() -> {
                        try (owned) {
                            apply(chains.get(), owned, source.info());
                            return checksum(owned);
                        }
                    }, owned::close);
                }
                var completed = executor.pollCompleted();
                if (completed == null) {
                    Thread.sleep(1);
                } else {
                    checksums.add(completed.value());
                }
            }
        }
        return new TimedResult(checksums, System.nanoTime() - started);
    }

    private static List<FrameProcessor> copies(List<FrameProcessor> effects, InfoVideo video) throws Exception {
        List<FrameProcessor> chain = new ArrayList<>();
        for (FrameProcessor effect : effects) {
            FrameProcessor copy = effect.copyForWorker();
            copy.initialise(video);
            chain.add(copy);
        }
        return chain;
    }

    private static void apply(List<FrameProcessor> effects, Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor effect : effects) {
            effect.process(frame, info);
        }
    }

    private static long checksum(Frame frame) {
        ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
        pixels.clear();
        byte[] bytes = new byte[frame.imageStride * frame.imageHeight];
        pixels.get(bytes);
        CRC32 checksum = new CRC32();
        checksum.update(bytes);
        return checksum.getValue();
    }

    private static String mediaName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static double milliseconds(long nanoseconds) {
        return nanoseconds / 1_000_000.0;
    }
}
