# COMP3011 Assignment 2 - Java Video Player

For the assignment specification and setup information, see the [COMP3011 Assignment 2 page](https://learn.adelaide.edu.au/courses/30747/assignments/94505).

## Project overview

This project extends the supplied single-threaded JavaFX video player. Specification Part 1 adds command-line selection of frame-processing effects. Selected effects run in command-line order, and the same effect may be selected more than once.

## Requirements

- JDK 25
- Maven or the included Maven Wrapper (`./mvnw`)
- A local video file supported by FFmpeg/JavaCV

## Build

From the project root, run:

```sh
./mvnw clean test
./mvnw package
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

`CommandLineController` parses launch options and records frame-effect selections in order as `FrameProcessorType` values. `VideoPlayerApp` passes that list to `VideoPlayerController`, which passes it to `VideoPlayerModel`. The model creates and owns a separate `FrameProcessor` instance for each selection. Existing `FrameProcessor` subclasses perform the effects.

This keeps parsing separate from effect creation and execution. The ordered list also preserves repeated selections and provides a straightforward place to add further effect types.

## Testing

Completing these Part 1 manual checks; check TESTING.md for detailed Testing documentations:

- Normal playback without effects, including audio
- Short and long options for the same effect
- Stacked short options
- Mixed short and long options
- Repeated effects and command-line ordering
- Invalid option, missing video, and conflicting monitor handling
- Maven `clean test` and `package` commands

The model prints frame headroom during playback. A positive value means the frame was prepared before its target display time; a negative value means it missed that deadline. See [TESTING.md](TESTING.md) for the Part 1 command-by-command checklist and expected results. There is currently no `src/test` directory, so `mvn test` does not run repository-provided automated tests.

## Current project status

Specification Part 1 is implemented. Specification Part 2, multithreaded frame-processing performance, is the next development stage.

## AI-use disclosure

Generative AI was used as a learning and development aid for understanding Java/Maven errors, command-line parsing design, documentation structure, and test planning. Generated suggestions were reviewed, adapted, compiled, and manually tested.
