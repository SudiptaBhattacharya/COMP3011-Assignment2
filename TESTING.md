# Testing

## Part 1: Single-threaded frame processing

Test Part 1 systematically before moving to multithreading. The outcomes below are **expected results to check**, not a record of tests already run. Use the same video file in each playback test so the visual results can be compared.

### 1. Confirm the help text

```sh
./mvnw javafx:run '-Djavafx.args=--help'
```

**Expected:** The original options appear, there is a blank line before `Frame processors:`, and every required effect appears. The help text should match the specification. Maven may report failure because help exits intentionally without opening JavaFX; for this check, verify the help text and intentional exit.

### 2. Test without effects

```sh
./mvnw javafx:run \
  '-Djavafx.args=-a /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

**Expected:** Normal video playback and audio still work, with no frame effects applied.

### 3. Test one effect

Black and white, using the short option:

```sh
./mvnw javafx:run \
  '-Djavafx.args=-a -w /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

Then use the long equivalents:

```sh
./mvnw javafx:run \
  '-Djavafx.args=--audio --black-and-white /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

**Expected:** Both commands produce the same black-and-white visual result.

### 4. Test stacked options

```sh
./mvnw javafx:run \
  '-Djavafx.args=-a -nwyv /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

**Expected processor order:**

1. Frame numbering
2. Black and white
3. Yellow/warm colouring
4. Vignette

### 5. Test repeated effects

```sh
./mvnw javafx:run \
  '-Djavafx.args=-a -ssn /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

**Expected processor instances, in order:** `FrameScratcher`, `FrameScratcher`, `FrameNumberer`. Both `s` options must create separate instances; they must not be collapsed into one.

### 6. Test the specification's mixed example

```sh
./mvnw javafx:run \
  '-Djavafx.args=-a -jf --dust-frames -d --number-frames /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

**Expected processor order:** `JITTER`, `FLICKER`, `DUST`, `DUST`, `NUMBER`.

### 7. Test the full specified stack

```sh
./mvnw javafx:run \
  '-Djavafx.args=-a -nssnfwyvdjmbp /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

**Expected:** All effects run in command-line order, including repeated effects. Playback may slow down in this single-threaded version; record that behaviour as the baseline for Part 2.

### 8. Test invalid inputs

Unknown option:

```sh
./mvnw javafx:run \
  '-Djavafx.args=-q /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

**Expected message:** `Unknown option: -q`

No video:

```sh
./mvnw javafx:run '-Djavafx.args=-a'
```

**Expected message:** `No video file specified.`

Conflicting monitors:

```sh
./mvnw javafx:run \
  '-Djavafx.args=-1 -2 /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

**Expected message:** `Only one display option can be used`

### 9. Run the complete Maven checks

```sh
./mvnw clean test
./mvnw package
```

**Expected:** Each command ends with `BUILD SUCCESS`.
