package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameProcessingConcurrencyTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 36;

    @TempDir
    Path temporaryDirectory;

    @Test
    void commandLineKeepsStackedEffectOrderAndDuplicates() throws Exception {
        File video = Files.createFile(temporaryDirectory.resolve("sample.mp4")).toFile();
        assertEquals(List.of(FrameProcessorType.SCRATCH, FrameProcessorType.SCRATCH,
                        FrameProcessorType.SCRATCH, FrameProcessorType.SCRATCH, FrameProcessorType.NUMBER),
                new CommandLineController(new String[] {"-ssssn", video.getPath()}).getFrameProcessorTypes());
        assertEquals(List.of(FrameProcessorType.NUMBER, FrameProcessorType.JITTER,
                        FrameProcessorType.NUMBER, FrameProcessorType.JITTER, FrameProcessorType.NUMBER),
                new CommandLineController(new String[] {"-njnjn", video.getPath()}).getFrameProcessorTypes());
        assertEquals(List.of(FrameProcessorType.YELLOW, FrameProcessorType.YELLOW,
                        FrameProcessorType.YELLOW, FrameProcessorType.BLEED,
                        FrameProcessorType.YELLOW, FrameProcessorType.NUMBER),
                new CommandLineController(new String[] {"-yyybyn", video.getPath()}).getFrameProcessorTypes());
    }

    @Test
    void clonedFramesOwnTheirPixels() {
        try (Frame original = sourceFrame(1); Frame copy = original.clone()) {
            ByteBuffer originalPixels = (ByteBuffer) original.image[0];
            ByteBuffer copiedPixels = (ByteBuffer) copy.image[0];
            assertNotSame(originalPixels, copiedPixels);
            byte before = originalPixels.get(0);
            copiedPixels.put(0, (byte) (before + 1));
            assertEquals(before, originalPixels.get(0));
        }
    }

    @Test
    void parallelFramesMatchSequentialEffectsIncludingRepeatedInstances() throws Exception {
        assertParallelMatchesSequential(List.of(
                new FrameScratcher(), new FrameScratcher(), new FrameScratcher(),
                new FrameScratcher(), new FrameNumberer()));
        assertParallelMatchesSequential(List.of(
                new FrameNumberer(), new FrameJitterer(), new FrameNumberer(),
                new FrameJitterer(), new FrameNumberer()));
        assertParallelMatchesSequential(List.of(
                new FrameYellower(), new FrameYellower(), new FrameYellower(),
                new FrameBleeder(), new FrameYellower(), new FrameNumberer()));
        assertParallelMatchesSequential(List.of(
                new FrameNumberer(), new FrameScratcher(), new FrameScratcher(),
                new FrameNumberer(), new FrameFlickerer(), new FrameBlackAndWhiter(),
                new FrameYellower(), new FrameVignetter(), new FrameDuster(),
                new FrameJitterer(), new FrameMottler(), new FrameBleeder(),
                new FramePepperer()));
    }

    private static void assertParallelMatchesSequential(List<FrameProcessor> prototypes) throws Exception {
        try (Frame sample = sourceFrame(0)) {
            InfoVideo video = new InfoVideo("concurrency-test", 20, WIDTH, HEIGHT,
                    sample.imageDepth, sample.imageChannels, sample.imageStride,
                    24.0, 24, 41_667, 0);
            List<FrameProcessor> sequential = copies(prototypes, video);
            List<byte[]> expected = new ArrayList<>();
            for (int number = 1; number <= 8; number++) {
                try (Frame frame = sourceFrame(number)) {
                    apply(sequential, frame, number);
                    expected.add(pixelBytes(frame));
                }
            }

            try (var workers = Executors.newFixedThreadPool(4)) {
                ThreadLocal<List<FrameProcessor>> perWorker = ThreadLocal.withInitial(() -> {
                    try {
                        return copies(prototypes, video);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
              