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

## Part 2: Single-threaded baseline

Recorded on 9 November 2026 before changing the model. The full-stack run completed with Maven `BUILD SUCCESS`.

**Command used:**

```sh
./mvnw javafx:run \
  '-Djavafx.args=-a -nssnfwyvdjmbp /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

| Measure | Baseline observation |
| --- | --- |
| Computer | Apple M3 Mac (`Mac15,13`), macOS on arm64 |
| Available processors | 8 logical processors (`hw.ncpu`) |
| Applied effects | 13 instances, in order: NUMBER, SCRATCH, SCRATCH, NUMBER, FLICKER, BLACK_AND_WHITE, YELLOW, VIGNETTE, DUST, JITTER, MOTTLE, BLEED, PEPPER |
| Audio stalls | Not independently observed; requires a listener at the computer. |
| Visibly uneven playback | Not independently observed; requires viewing the player during playback. |
| Frame headroom | Initially negative, from about -8.7 seconds to -0.2 seconds; subsequently mostly positive at about +2 to +22 ms per frame, against a 41.7 ms frame interval. |
| Player CPU usage | Approximately 55–78% in sampled `ps` readings (macOS process percentage, where 100% is one logical processor). |

The initial negative headroom readings show the prepared frames were late relative to their target display times. Later positive readings show frames were prepared before their target times. The audio and visible-smoothness fields should be updated after direct observation; console output alone cannot establish either result.

## Part 2: concurrent frame processing

### Incremental implementation

1. Extract the per-frame effect loop so effects remain sequential and preserve command-line order.
2. Give each worker its own processor chain. The copies retain media-name and instance-index seeds, including repeated effects.
3. Deep-clone grabbed image frames before dispatch; JavaCV can reuse the grabber's native buffers.
4. Add `OrderedFrameExecutor`: fixed platform threads, a bounded queue, ordered future delivery and a generation counter. The model submits no more than twice the worker count of frames, counts a prepared frame in that limit, and keeps FFmpeg grabs serialized.
5. Cancel outstanding work on seek, rewind, start-over, stop and new playback. Shutdown cancels and stops the executor without waiting on JavaFX. Worker exceptions reach the model's existing error status.
6. Add headless correctness tests and a verify-phase effect-throughput comparison.

The default worker count is `max(1, min(4, availableProcessors - 2))`; on the eight-processor machine this is four workers and eight in-flight frames. The four-worker cap leaves capacity for decoding, audio and JavaFX. The verify benchmark below found seven workers faster for isolated effects, so this cap is a responsiveness choice pending an end-to-end four-versus-seven playback comparison.

### Maven lifecycle and automated checks

Run each command from the project root. Every lifecycle command should end in `BUILD SUCCESS`:

```sh
./mvnw clean validate
./mvnw test
./mvnw package
./mvnw verify
```

Inspect the test sources and run the original concurrency class directly:

```sh
find src/test -type f -print
./mvnw -Dtest=FrameProcessingConcurrencyTest test
```

The unit suite contains three `FrameProcessingConcurrencyTest` tests for clone ownership, stacked parser order, and concurrent-versus-sequential pixels across `-ssssn`, `-njnjn`, `-yyybyn` and `-nssnfwyvdjmbp`. Five `OrderedFrameExecutorTest` tests directly cover out-of-order completion, the in-flight limit, generation invalidation/cancellation, shutdown and worker exception propagation. `./mvnw verify` also runs one `FrameProcessingBenchmarkIT` integration test. Verify that Maven reports **8 unit tests** and **1 integration test**, with no failures or skips.

Run record, 9 November 2026: `clean validate`, `test`, `package` and `verify` each ended in `BUILD SUCCESS`. Maven reported 3 concurrency tests, 5 ordered-executor tests and 1 verify-phase benchmark, with zero failures, errors or skips. The isolated `-Dtest=FrameProcessingConcurrencyTest` command also ran all three tests successfully.

The benchmark uses the same 24 decoded frames and 13-effect stack for sequential, four-worker and seven-worker processing. It checks equal pixel checksums and prints elapsed times. It uses `~/Downloads/simpsons.mp4` when present, or synthetic frames otherwise. To specify a file:

```sh
./mvnw verify '-Dcomp3011.benchmarkVideo=/Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

This measures effect throughput only. It does not include audio, JavaFX display pacing, live decoding or interactive responsiveness; it cannot alone establish end-to-end playback speedup.

### Part 1 regression checks

```sh
./mvnw javafx:run '-Djavafx.args=--help'
./mvnw javafx:run '-Djavafx.args=--illegal-argument movie.mp4 --help'
```

The first command should print the complete help and end in `BUILD SUCCESS`. The second must print `Unknown option: --illegal-argument` **and** the full help; Maven should report `BUILD FAILURE` because the application exits with code 1. JavaFX may need permission to write its native-library cache.

Run record: both outputs matched. The combined error/help command ended with the expected Maven `BUILD FAILURE` and process exit code 1.

### Playback and effect stacks

Use the same video for these runs:

```sh
./mvnw javafx:run '-Djavafx.args=-ssssn /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
./mvnw javafx:run '-Djavafx.args=-njnjn /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
./mvnw javafx:run '-Djavafx.args=-yyybyn /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
./mvnw javafx:run '-Djavafx.args=-a /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
./mvnw javafx:run '-Djavafx.args=-a -nssnfwyvdjmbp /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4'
```

For repeated stacks, inspect visible numbering and effect order across several frames. For ordinary playback, check continuous synchronized audio and confirm closing the window returns the terminal prompt. For the heavy stack, watch for backwards numbering, stale or corrupt frames, audio stalls, poor UI response, sustained negative headroom, or worker/cancellation exceptions. Initial negative headroom can occur during startup.

During heavy playback, exercise every control:

| Action | Expected result |
|---|---|
| Space | Pause and resume |
| Left / Right | Seek backward / forward five seconds |
| Home | Restart from the beginning |
| A | Toggle audio |
| Resize / maximise | UI remains responsive and playback continues |
| Escape | Player exits cleanly |
| Close button | Player and worker threads terminate |

Also press **Right → Right → Left → Home → Right** rapidly. No frame from an obsolete position should appear after the final seek. After closing, run `jps -l`; the video-player process should be absent, and the terminal prompt should return without a forced quit.

Short launch and frame-headroom runs with no effects, `-w`, each repeated stack and the full stack reported no processing errors. Some were stopped with Ctrl+C after headroom appeared; other runs returned `BUILD SUCCESS` and a prompt. After the ordinary and heavy audio-enabled runs, `jps -l` listed only the JPS process. Direct macOS assistive access to the player window was denied during the automated session, so those runs did not independently prove visual order or audio quality. The project owner's direct observations are recorded below.

### Multicore scaling observation

Activity Monitor was used to observe the Java process while applying repeated
effect stacks to the same 1920x800, 23.98 fps video. These observations were
provided by the project owner.

| Effect workload | Observed Java CPU |
|---|---:|
| Two repeated stacks | 111.9% |
| Three repeated stacks | 178.1% |
| Four repeated stacks | 259.6% |
| Four stacks later in playback | 198.1% |
| Four stacks during pause/seek/resize interaction | Over 300% |

On macOS, approximately 100% CPU represents one fully utilised logical core.
The results therefore demonstrate that frame processing scales beyond one core
as the effect workload increases.

During these observations:

- Video playback remained functional.
- Audio continued playing correctly.
- Effects appeared correctly.
- Pause, resume, seek and maximise remained responsive.
- No visible frame corruption or ordering failure was observed.

CPU usage varied because frame complexity, queue occupancy, playback pacing and
interactive cancellation/refilling changed over time.

### Comparison with the single-threaded baseline

The previously recorded single-threaded full-stack run used the same machine, video and `-a` setting. Its startup headroom ranged about -8.7 to -0.2 seconds, then mostly +2 to +22 ms. A short concurrent full-stack smoke run printed about -1.23, -0.69 and -0.09 seconds at startup, then mostly +8 to +23 ms. Those selected lines are insufficient to claim an end-to-end speedup.

A verify-phase effect benchmark on 9 November 2026 processed 24 frames of that 1920x800 video with identical pixels and the 13-effect stack: **683.5 ms sequential, 187.8 ms with four workers, 133.3 ms with seven workers**. This is one untuned throughput sample; decode, audio and display time are excluded. Repeat runs and compare equivalent playback periods before changing the four-worker cap.

A second `./mvnw verify` pass measured **635.2 ms sequential, 192.7 ms with four workers, 149.8 ms with seven workers** on the same 24 decoded frames. Both runs favoured seven workers for isolated effects. Four remains the playback default because these measurements do not include JavaFX, decoding and audio contention; the project owner's interactive observations were made with four workers.

An isolated worktree at commit `700f081` was also run with the same machine, video, `-a` setting and full stack. Captured logs are `/tmp/comp3011-single-thread-baseline.log` and `/tmp/comp3011-multi-thread-result.log`. The baseline capture had 20 headroom samples, none negative, with a 15.5 ms median. The concurrent capture had 120 samples, including 10 negative startup samples; its last 20 had a 16.9 ms median. The captures covered different lengths and the concurrent run had a longer startup delay, so they are **not** evidence of end-to-end speedup. Repeat equal playback intervals under similar starting conditions for a fair player comparison.

For a controlled *player* comparison, use an isolated worktree at the last single-threaded commit and capture the same playback period:

```sh
git worktree add ../VideoPlayerSingleThread-baseline 700f081
cd ../VideoPlayerSingleThread-baseline
./mvnw javafx:run '-Djavafx.args=-a -nssnfwyvdjmbp /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4' 2>&1 | tee ../single-thread-baseline.log
cd ../VideoPlayerSingleThread
./mvnw javafx:run '-Djavafx.args=-a -nssnfwyvdjmbp /Users/sudiptabhattacharyareet/Downloads/simpsons.mp4' 2>&1 | tee ../multi-thread-result.log
```

Compare negative-headroom counts, typical headroom, audio stalls, visual smoothness, CPU usage and UI response over equivalent intervals. Record the duration and conditions for each run. A pipe through `tee` can mask Maven's exit status in some shells; inspect the player output and process exit separately when using this capture form.
