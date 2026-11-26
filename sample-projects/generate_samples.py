"""Generate deterministic BuildHive fixture ZIPs using only Python's standard library."""

import json
from pathlib import Path
from textwrap import dedent
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


ROOT = Path(__file__).resolve().parent
MAIN = "src/main/java/demo/fixture/Calculator.java"
TEST = "src/test/java/demo/fixture/CalculatorTest.java"

APP = """\
package demo.fixture;

public class Calculator {
    public static int add(int left, int right) {
        return left + right;
    }

    public static void main(String[] args) {
        System.out.println(add(2, 3));
    }
}
"""

CHECKSTYLE_RULES = """\
<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
    "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
    <module name="TreeWalker">
        <module name="AvoidStarImport"/>
    </module>
</module>
"""


def pom(name, *, tests=True, packaging="jar", dependency="", broken_package=False, analyzer="checkstyle"):
    junit = """\
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>""" if tests else ""
    manifest_file = "<manifestFile>${project.basedir}/missing.mf</manifestFile>" if broken_package else ""
    analysis_plugin = """\
            <plugin>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <version>3.4.0</version>
                <configuration>
                    <configLocation>${project.basedir}/quality-rules.xml</configLocation>
                </configuration>
            </plugin>""" if analyzer == "checkstyle" else ""
    if analyzer == "spotbugs":
        analysis_plugin = """\
            <plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>4.8.6.6</version>
            </plugin>"""
    return f"""\
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>demo.fixture</groupId>
    <artifactId>{name}</artifactId>
    <version>1.0.0</version>
    <packaging>{packaging}</packaging>
    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
{junit}
{dependency}
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
{analysis_plugin}
            <plugin>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.1</version>
                <configuration>
                    <archive>
                        <manifest><mainClass>demo.fixture.Calculator</mainClass></manifest>
                        {manifest_file}
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
"""


def test_source(body):
    method = "\n".join(f"        {line}" for line in dedent(body).strip().splitlines())
    return f"""\
package demo.fixture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CalculatorTest {{
    @Test
    void addsNumbers() throws InterruptedException {{
{method}
    }}
}}
"""


def project(name, *, source=APP, test_body="assertEquals(5, Calculator.add(2, 3));", **pom_options):
    files = {
        "pom.xml": pom(name, tests=test_body is not None, **pom_options),
        "quality-rules.xml": CHECKSTYLE_RULES,
        MAIN: source,
    }
    if test_body is not None:
        files[TEST] = test_source(test_body)
    return files


def write_archive(destination, files):
    if isinstance(files, bytes):
        destination.write_bytes(files)
        return
    with ZipFile(destination, "w") as archive:
        for name, contents in sorted(files.items()):
            archive.writestr(ZipInfo(name), contents.encode("utf-8"), compress_type=ZIP_DEFLATED)


def generate(destination):
    cases = {}

    def add(name, files, **expected):
        write_archive(destination / f"{name}.zip", files)
        cases[name] = expected

    def successful(name, **options):
        add(name, project(name, **options), status="SUCCEEDED")

    def failed(name, stage, *, infrastructure=False, **options):
        add(name, project(name, **options), status="FAILED", stage=stage,
            classification="INFRASTRUCTURE_ERROR" if infrastructure else "USER_CODE_ERROR",
            attempts=3 if infrastructure else 1)

    def rejected(name, files, message):
        add(name, files, status="REJECTED", message=message)

    successful("calculator-pass")
    add("wrapped-project", {
        f"calculator/{path}": content for path, content in project("wrapped-project").items()
    }, status="SUCCEEDED")
    successful("no-tests", test_body=None)
    failed("compile-error", "COMPILATION", source=APP.replace("left + right", "unknownVariable"))
    failed("test-assertion-failure", "UNIT_TESTS", test_body="assertEquals(6, Calculator.add(2, 3));")
    failed("test-runtime-error", "UNIT_TESTS", test_body='throw new IllegalStateException("fixture error");')
    failed("quality-rule-violation", "STATIC_ANALYSIS",
           source=APP.replace("package demo.fixture;", "package demo.fixture;\n\nimport java.util.*;"))
    cases["calculator-pass"]["quality"] = "PASSED"
    cases["quality-rule-violation"]["quality"] = "VIOLATIONS"
    name = "quality-default-pass"
    add(name, project(name, analyzer=None), status="SUCCEEDED", quality="PASSED")
    name = "quality-default-violation"
    default_violation = APP.replace("return left + right;", ";\n        return left + right;")
    add(name, project(name, source=default_violation, analyzer=None), status="FAILED",
        stage="STATIC_ANALYSIS", classification="USER_CODE_ERROR", attempts=1, quality="VIOLATIONS")
    name = "quality-spotbugs-only"
    add(name, project(name, source=default_violation, analyzer="spotbugs"), status="FAILED",
        stage="STATIC_ANALYSIS", classification="USER_CODE_ERROR", attempts=1, quality="VIOLATIONS")
    name = "quality-invalid-rules"
    files = project(name)
    files["quality-rules.xml"] = "<invalid-checkstyle-configuration"
    add(name, files, status="FAILED", stage="STATIC_ANALYSIS", classification="USER_CODE_ERROR",
        attempts=1, quality="TOOL_ERROR")
    failed("package-error", "PACKAGING", broken_package=True)
    failed("package-no-jar", "PACKAGING", packaging="pom", test_body=None)
    failed("unresolved-dependency", "DEPENDENCY_RESOLUTION", infrastructure=True, dependency="""\
        <dependency>
            <groupId>demo.fixture.missing</groupId>
            <artifactId>unpublished-library</artifactId>
            <version>1.0.0</version>
        </dependency>""")
    successful("slow-tests", test_body="""
        for (int step = 1; step <= 12; step++) {
            System.out.println("BuildHive fixture progress " + step);
            Thread.sleep(5_000);
        }
        assertEquals(5, Calculator.add(2, 3));
        """)
    successful("verbose-tests", test_body="""
        for (int line = 1; line <= 3_200; line++) {
            System.out.println("BuildHive fixture output " + line);
        }
        assertEquals(5, Calculator.add(2, 3));
        """)

    missing_pom = "must contain a root or top-level 'pom.xml'"
    rejected("empty-archive", {}, missing_pom)
    rejected("not-a-zip", b"This fixture contains plain text, despite its .zip extension.\n", missing_pom)
    rejected("missing-pom", {MAIN: APP}, missing_pom)
    rejected("pom-too-deep", {"outer/inner/pom.xml": pom("pom-too-deep")}, missing_pom)
    rejected("malformed-pom", {"pom.xml": "<project><artifactId>broken</project>"}, "Failed to parse pom.xml")
    rejected("wrong-pom-root", {"pom.xml": "<configuration/>"}, "Root element of pom.xml must be <project>")
    coordinates = {
        "artifactId": "<artifactId>missing-artifact-id</artifactId>",
        "groupId": "<groupId>demo.fixture</groupId>",
        "version": "<version>1.0.0</version>",
    }
    for field, name in [("artifactId", "missing-artifact-id"), ("groupId", "missing-group-id"),
                        ("version", "missing-version")]:
        rejected(name, {"pom.xml": pom(name).replace(coordinates[field], "", 1)},
                 f"pom.xml is missing required <{field}>")

    (destination / "cases.json").write_text(json.dumps(cases, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(cases)} fixtures in {destination}")


if __name__ == "__main__":
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    generate(output)
