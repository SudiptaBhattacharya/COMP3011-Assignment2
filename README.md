# COMP3011 Assignment 2 - Java Video Player

For the assignment specification and setup information, see the [COMP3011 Assignment 2 page](https://learn.adelaide.edu.au/courses/30747/assignments/94505).

## Project overview

This project extends the supplied JavaFX video player. Part 1 adds command-line selection of frame effects in the requested order, including repeated effects. Part 2 processes independent video frames concurrently on a bounded platform-thread pool while keeping display order and the original controls.

## Requirements

- JDK 25
- Maven or the included Maven Wrapper (`./mvnw`)
- A local video file supported by FFmpeg/JavaCV

## Build

From the project root, run:

```sh
./mvnw clean validate
./mvnw test
./mvnw package
./mvnw verify
```

## Run the application

Replace the placeholder path with the absolute path to a local video file:

```sh
./mvnw javafx:run '-Djavafx.args=[options] /absolute/path/to/video.mp4'
```

For example, to play a video with audio and no effects:

```sh
./mvnw javafx:run '-Djavafx.args=-a /Users/example/Movies/video.mp4'
```

### Existing launch options

| Short | Long | Description |
| --- | --- | --- |
| `-h` | `--help` | Show the help message. |
| `-a` | `--audio` | Play audio. |
| `-x` | `--maximise` | Open the player maximised. |
| `-1` | `--monitor-1` | Open the player on display 1. |
| `-2` | `--monitor-2` | Open the player on display 2. |

### Frame-processor options

| Short | Long | Effect |
| --- | --- | --- |
| `-n` | `--number-frames` | Render the frame number onto each frame. |
| `-s` | `--scratch-frames` | Render vertical film scratches. |
| `-f` | `--flicker-frames` | Randomly dim frames. |
| `-w` | `--black-and-white` | Convert frames to black and white. |
| `-y` | `--yellow-frames` | Apply a warmer colour temperature. |
| `-v` | `--vignette-frames` | Darken the frame edges. |
| `-d` | `--dust-frames` | Render dust and hair marks. |
| `-j` | `--jitter-frames` | Randomly displace frames by a few pixels. |
| `-m` | `--mottle-frames` | Add cloudy emulsion mottling. |
| `-b` | `--bleed-frames` | Bleed light into frames. |
| `-p` | `--pepper-frames` | Pepper frames with dark spots and blotches. |

### Ordering, stacking and repetition

Short options can be stacked in one argument, and short and long options can be mixed. Each occurrence is retained, including repeated effects. The model applies effects in the order supplied on the command line.

Stacked effects (`NUMBER`, `BLACK_AND_WHITE`, `YELLOW`, `VIGNETTE`):

```sh
./mvnw javafx:run '-Djavafx.args=-a -nwyv /Users/example/Movies/video.mp4'
```

Repeated scratches (`SCRATCH`, `SCRATCH`, `NUMBER`):

```sh
./mvnw javafx:run '-Djavafx.args=-a -ssn /Users/example/Movies/video.mp4'
```

Mixed short and long options (`JITTER`, `FLICKER`, `DUST`, `DUST`, `NUMBER`):

```sh
./mvnw javafx:run '-Djavafx.args=-a -jf --dust-frames -d --number-frames /Users/example/Movies/video.mp4'
```

Full Part 1 effect stack:

```sh
./mvnw javafx:run '-Djavafx.args=-a -nssnfwyvdjmbp /Users/example/Movies/video.mp4'
```

## Design summary

`CommandLineController` parses launch options and records frame-effect selections in order as `FrameProcessorType` values. `VideoPlayerApp` passes that list through `VideoPlayerController` to `VideoPlayerModel`. The model retains a separate processor prototype for every selection, including duplicates.

For Part 2, `VideoPlayerModel` decodes on the JavaFX thread and deep-clones each image frame before submitting it to `OrderedFrameExecutor`. JavaCV's grabber can reuse its buffers, so a worker must own its frame data. Four platform workers are used on an eight-processor machine; the count is `max(1, min(4, availableProcessors - 2))`. At most twice the worker count of frames may be in flight. Each worker has an independent processor chain with the original effect-instance seed indices. Effects within a frame run sequentially; separate frames may run concurrently.

Results are collected in submission order, then converted to JavaFX images and displayed according to the playback clock. Seek, restart, stop and new playback cancel pending jobs and advance a generation, preventing old results from appearing. Shutdown cancels work and stops the executor without waiting on the JavaFX thread. The existing audio, sizing and keyboard paths remain wired through the controller.

## Testing

Run the automated and manual checks in [TESTING.md](TESTING.md). The Maven unit tests cover parsing, frame ownership, deterministic concurrent output, ordered completion, the in-flight limit, cancellation, shutdown, and worker failures. `./mvnw verify` also runs an effect-processing comparison on identical frames with one sequential chain, four workers and seven workers. On this machine it uses `~/Downloads/simpsons.mp4` if available; set `-Dcomp3011.benchmarkVideo=/absolute/path/to/video.mp4` to choose another file. If no video is available, the benchmark uses synthetic frames. It checks output equality and reports times; it does not assert a speedup.

The model prints frame headroom during playback. Positive values mean a frame was prepared before its target display time; negative values mean it was late. Headroom, effect throughput, audio continuity and visible smoothness measure different aspects of performance. A short positive sample alone does not establish an end-to-end speedup.

## Current project status

Part 1 and the bounded Part 2 frame-processing architecture are implemented. Direct visual, audio and interactive checks, plus a controlled end-to-end comparison against the single-threaded player, remain part of the verification procedure in [TESTING.md](TESTING.md).

## AI-use disclosure

Generative AI was used as a learning and development aid for command-line parsing, concurrency design, Java/Maven diagnostics, tests and documentation. Suggestions were reviewed against the project code, compiled, and checked with automated tests and playback smoke runs. Direct interactive observations are identified separately in the testing record.
