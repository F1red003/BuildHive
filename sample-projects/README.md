# BuildHive sample projects

From the project root, generate the upload files with Python 3:

```powershell
python sample-projects/generate_samples.py
```

The generator writes the ZIPs and an expected outcomes manifest to `sample-projects/dist/`. You can upload the ZIPs from **Submit Project** in the dashboard. Start with `calculator-pass.zip` to see the full pipeline and download an executable JAR. Run the same generator on Windows or Linux to recreate the fixtures.

| ZIP name | Expected result |
| --- | --- |
| `calculator-pass` | All stages complete. Checkstyle passes, a JUnit test passes, and a runnable JAR is published. |
| `wrapped-project` | Same successful build with the POM and sources inside one top-level folder. |
| `no-tests` | The build succeeds and publishes a JAR without a Surefire test report. |
| `compile-error` | `COMPILATION` fails once on an undefined variable. |
| `test-assertion-failure` | `UNIT_TESTS` fails once on a wrong expected value. |
| `test-runtime-error` | `UNIT_TESTS` fails once when a test throws an exception. |
| `quality-rule-violation` | `STATIC_ANALYSIS` fails once because Checkstyle rejects a wildcard import. |
| `quality-default-pass` | A project without a Checkstyle plugin runs BuildHive's default rules, publishes a Checkstyle report, and builds a JAR. |
| `quality-default-violation` | BuildHive's default rules flag an empty Java statement and stop Packaging. |
| `quality-spotbugs-only` | A SpotBugs plugin declaration leaves BuildHive's default Checkstyle check in effect. The default rules flag the empty Java statement. |
| `quality-invalid-rules` | The configured Checkstyle rules cannot be parsed. Static Analysis fails and the terminal report explains the tool error. |
| `package-error` | `PACKAGING` fails once because the JAR plugin cannot find the configured manifest. |
| `package-no-jar` | Maven can package a POM project, but BuildHive fails `PACKAGING` because there is no JAR to publish. |
| `unresolved-dependency` | `DEPENDENCY_RESOLUTION` fails after three attempts. This demonstrates the current policy of treating dependency resolution failures as retryable. |
| `slow-tests` | Succeeds when left running. The test emits progress about every five seconds for one minute, giving you time to cancel during `UNIT_TESTS`. Its complete attempt log contains 12 progress lines. |
| `verbose-tests` | Succeeds and emits 3,200 numbered lines from `UNIT_TESTS`. The complete attempt log download contains all of them. |
| `empty-archive`, `not-a-zip`, `missing-pom`, `pom-too-deep` | Upload rejected before any pipeline is created. |
| `malformed-pom`, `wrong-pom-root` | Upload rejected because the root POM cannot be parsed as a Maven project. |
| `missing-artifact-id`, `missing-group-id`, `missing-version` | Upload rejected because a required Maven coordinate is missing. |

With the local Compose stack running, check the outcomes against the backend. Pass case names without `.zip` to select a subset, or omit them to run all cases.

```powershell
python scripts/verify_samples.py calculator-pass quality-rule-violation unresolved-dependency
python scripts/verify_samples.py
```

The verifier checks upload rejections, terminal pipeline status, failure stage, attempt count, JAR integrity, and Checkstyle artifacts. Each accepted submission creates a real pipeline in the local history. CI runs the fast sample cases and a slow run for log reconnection.

To check the browser log endpoint through the frontend proxy, run:

```powershell
python scripts/verify_live_logs.py sample-projects/dist/slow-tests.zip
```

The verifier observes progress during Unit Tests, reconnects with the last event ID, and checks that the combined stream matches the full attempt log byte for byte.

To verify cancellation against both workers, run this in the standard Compose stack:

```powershell
python scripts/verify_cancellation.py sample-projects/dist/slow-tests.zip --base-url http://localhost:8080 --containers buildhive-worker-1 buildhive-worker-2
```

For a concurrency demo, submit `calculator-pass.zip` and `wrapped-project.zip` back to back and watch both in **Pipelines**. A ZIP supplies Maven input. Worker loss, lease recovery, parallel dependency updates, and artifact delivery failures depend on cluster state or injected failures and are covered by the scheduler and publication integration tests.
