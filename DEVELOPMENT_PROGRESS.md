# Development progress

This timeline records the main stages of work on COMP3011 Assignment 2. Dates are based on the development notes and may describe overlapping activity.

## 20–25 October — Understand the assignment

- Read and unpacked the Assignment 2 criteria and requirements.
- Worked through the expected player behaviour and the logic needed to meet the specification.
- Revised relevant course and seminar materials to refresh Java, JavaFX, media playback, and related concepts.
- Planned the implementation around the supplied project and its existing code.

## 26 October — Initial commit

- Created the initial project commit and established the starting point for implementation.

## 27–28 October — Personal circumstances

- Stress and personal issues interrupted progress.
- Requested a longer extension to allow time to continue the assignment.

## 3–6 November — Build and project setup

- Worked through Maven plugin and parameter problems through repeated configuration and build attempts.
- Completed the project setup and configuration needed to continue implementation and run the application.

## 7–8 November — Part 1

- Implemented and refined command-line option parsing for the required short and long options.
- Preserved processor selection order and repeated selections.
- Added the required help and error handling, including checking help after an invalid argument while retaining a non-zero error result.
- Connected parsed options to player and processor behaviour.
- Compiled and debugged the Part 1 changes, then checked the relevant TITAN results and command-line cases.

## 8–9 November — Part 2 and final verification

- Implemented bounded concurrent processing across frames while preserving sequential effect order within each frame.
- Worked on frame ownership, ordered delivery, bounded in-flight work, cancellation after playback-position changes, processor safety, and executor shutdown.
- Reviewed TITAN results for both Part 1 and Part 2 and addressed issues found during those checks.
- Prepared the final Part 2 changes and documentation, and ran the available Part 1 and Part 2 build, test, and playback checks.
- Reviewed the remaining interactive and real-media checks as part of final verification.
