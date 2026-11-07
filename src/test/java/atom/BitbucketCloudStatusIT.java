package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bitbucket-cloud-status atom, started the way the runner starts it.
 *
 * <p>Every scenario runs against the fixture in {@code tools/fake_bitbucket_cloud.py} and is judged by
 * {@code tools/check.py}: a second implementation that re-derives the workspace/repo, the token, the target URL and the
 * payload from the run's own {@code input.json}, then compares them with the requests the fake received, the status it
 * stored, and what the atom reported. Keeping that oracle is the point — a test that only read the atom's own output
 * would believe it.
 */
class BitbucketCloudStatusIT {

    private static final String SHA = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";
    private static final String CKEY = "ck-123";
    private static final String CSECRET = "cs-456";
    private static final String REPO = "\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"" + SHA + "\"";

    private Atom atom;
    private Process server;
    private Path store;
    private Path requests;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.destroy();
            server = null;
        }
    }

    // ---------------------------------------------------------------- the fake server

    private void serve(String mode, String expectSha, String directToken) throws Exception {
        if (server != null) {
            server.destroy();
            server = null;
        }
        Path dir = Files.createDirectories(
                Path.of(System.getProperty("atom.scratch")).resolve("bitbucket-" + System.nanoTime()));
        Path out = dir.resolve("server.out");
        List<String> command = new ArrayList<>(List.of("python3",
                Path.of(System.getProperty("atom.module")).resolve("tools/fake_bitbucket_cloud.py").toString(),
                "--port", "0", "--dir", dir.toString(), "--mode", mode,
                "--consumer-key", CKEY, "--consumer-secret", CSECRET, "--expect-sha", expectSha));
        if (!directToken.isEmpty()) {
            command.addAll(List.of("--direct-token", directToken));
        }
        server = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(out.toFile()).start();

        String port = "";
        store = null;
        requests = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            List<String> lines = Files.isRegularFile(out) ? Files.readAllLines(out, StandardCharsets.UTF_8) : List.of();
            for (String line : lines) {
                if (line.startsWith("listening ")) {
                    port = line.substring("listening ".length()).trim();
                } else if (line.startsWith("store ")) {
                    store = Path.of(line.substring("store ".length()).trim());
                } else if (line.startsWith("log ")) {
                    requests = Path.of(line.substring("log ".length()).trim());
                }
            }
            if (!port.isEmpty() && store != null && requests != null) {
                base = "http://127.0.0.1:" + port;
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("the fake Bitbucket Cloud did not start: "
                + (Files.isRegularFile(out) ? Files.readString(out, StandardCharsets.UTF_8) : ""));
    }

    private void serve(String mode) throws Exception {
        serve(mode, SHA, "");
    }

    // ---------------------------------------------------------------- the run and its oracle

    /** The shared build environment plus the scenario's own inputs, and the consumer secret. */
    private Atom scenario(String json) {
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("apiUrl", base);
        given.put("tokenUrl", base + "/site/oauth2/access_token");
        given.put("consumerKey", CKEY);
        given.put("JOB_NAME", "parcel");
        given.put("BUILD_NUMBER", "42");
        given.put("BUILD_URL", "http://jenkins/job/parcel/42/display/redirect");
        given.put("GIT_URL", "git@bitbucket.org:acme/parcel.git");
        given.put("GIT_COMMIT", SHA);
        given.put("BUILD_STATUS", "SUCCESS");
        given.putAll(Json.parseObject(json));
        return atom.input(Json.write(given))
                .file(".secrets.json", "{\"consumerSecret\": \"" + CSECRET + "\"}");
    }

    /** A run with the explicit workspace/repo/sha the smoke's first scenarios used, plus {@code extra}. */
    private AtomResult repo(String extra) throws IOException, InterruptedException {
        return scenario("{" + REPO + (extra.isEmpty() ? "" : ", " + extra) + "}").run();
    }

    private void check(AtomResult run, String expect) throws Exception {
        check(run, expect, "", false, false, "");
    }

    private void check(AtomResult run, String expect, String state) throws Exception {
        check(run, expect, state, false, false, "");
    }

    private void check(AtomResult run, String expect, String state, boolean noVerify, boolean noRequest,
            String accessToken) throws Exception {
        List<String> command = new ArrayList<>(List.of("python3",
                Path.of(System.getProperty("atom.module")).resolve("tools/check.py").toString(),
                "--run", run.work().toString(), "--store", store.toString(), "--log", requests.toString(),
                "--sha", SHA, "--consumer-key", CKEY, "--consumer-secret", CSECRET, "--expect", expect));
        if (!state.isEmpty()) {
            command.addAll(List.of("--state", state));
        }
        if (noVerify) {
            command.add("--no-verify");
        }
        if (noRequest) {
            command.add("--no-request");
        }
        if (!accessToken.isEmpty()) {
            command.addAll(List.of("--access-token", accessToken));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "check failed for " + run.work() + ":\n" + output);
    }

    private static void contains(Path file, String text) throws IOException {
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains(text),
                file + " does not mention " + text);
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    @DisplayName("green build, explicit workspace/repo, client_credentials: one status, read back")
    void reportsAGreenBuild() throws Exception {
        serve("normal");
        AtomResult run = repo("");

        check(run, "success", "SUCCESSFUL");
        String output = run.file("output.json");
        assertTrue(output.contains("\"tokenSource\""), output);
        assertTrue(output.contains("client-credentials"), output);
    }

    @Test
    @DisplayName("UNSTABLE is FAILED, and the server stored what the atom reported")
    void mapsUnstableToFailed() throws Exception {
        serve("normal");
        AtomResult run = repo("\"BUILD_STATUS\": \"UNSTABLE\"");

        check(run, "success", "FAILED");
        contains(store, "\"state\": \"FAILED\"");
    }

    @Test
    @DisplayName("an explicit state wins, and key/name/description expand from ${VAR}")
    void expandsTheExplicitFields() throws Exception {
        serve("normal");
        AtomResult run = repo("\"state\": \"INPROGRESS\", \"key\": \"parcel-${BUILD_NUMBER}\","
                + " \"name\": \"parcel build ${BUILD_NUMBER}\", \"description\": \"running on ${JOB_NAME}\"");

        check(run, "success", "INPROGRESS");
        contains(store, "\"key\": \"parcel-42\"");
        contains(store, "\"description\": \"running on parcel\"");
    }

    @Test
    @DisplayName("overrideLatestBuild: one status per job, key=md5(job)")
    void overridesTheLatestBuild() throws Exception {
        serve("normal");
        AtomResult run = repo("\"overrideLatestBuild\": \"true\"");

        check(run, "success", "SUCCESSFUL");
    }

    @Test
    @DisplayName("a pre-issued access token skips the token exchange entirely")
    void usesAPreIssuedToken() throws Exception {
        serve("normal", SHA, "direct-token");
        AtomResult run = scenario("{" + REPO + "}")
                .file(".secrets.json", "{\"accessToken\": \"direct-token\"}").run();

        check(run, "success", "", false, false, "direct-token");
        assertFalse(Files.readString(requests, StandardCharsets.UTF_8).contains("oauth2/access_token"),
                "an access token was supplied but the atom still exchanged one");
    }

    @Test
    @DisplayName("workspace and repo come from a scp-shaped GIT_URL, the key from md5(job#build)")
    void derivesFromAScpUrl() throws Exception {
        serve("normal");
        AtomResult run = scenario("{}").run();

        check(run, "success");
        contains(store, "\"key\": \"" + md5("parcel#42") + "\"");
    }

    @Test
    @DisplayName("workspace and repo come from an https clone URL with a .git suffix")
    void derivesFromAnHttpsUrl() throws Exception {
        serve("normal");
        AtomResult run = scenario("{\"GIT_URL\": \"https://user@bitbucket.org/acme/parcel.git\"}").run();

        check(run, "success");
    }

    @Test
    @DisplayName("no GIT_URL and no workspace is a user error, and nothing is sent")
    void refusesWithoutARepository() throws Exception {
        serve("normal");
        AtomResult run = scenario("{\"GIT_URL\": \"\"}").run();

        check(run, "error:40001", "", false, true, "");
    }

    @Test
    @DisplayName("no sha and no GIT_COMMIT is a user error")
    void refusesWithoutASha() throws Exception {
        serve("normal");
        AtomResult run = scenario("{\"GIT_COMMIT\": \"\"}").run();

        check(run, "error:40001", "", false, true, "");
    }

    @Test
    @DisplayName("no credential at all is a user error")
    void refusesWithoutACredential() throws Exception {
        serve("normal");
        AtomResult run = scenario("{" + REPO + "}").file(".secrets.json", "{}").run();

        check(run, "error:40002", "", false, true, "");
    }

    @Test
    @DisplayName("an unknown state is rejected before anything is sent")
    void refusesAnUnknownState() throws Exception {
        serve("normal");
        AtomResult run = repo("\"state\": \"WAT\"");

        check(run, "error:40003", "", false, true, "");
    }

    @Test
    @DisplayName("the token endpoint rejecting the secret is a user error, with no status POST")
    void reportsBadCredentials() throws Exception {
        serve("bad-credentials");
        AtomResult run = repo("");

        check(run, "error:40002", "", false, true, "");
    }

    @Test
    @DisplayName("a token endpoint that is down is a third-party error")
    void reportsATokenEndpointThatIsDown() throws Exception {
        serve("token-500");
        AtomResult run = repo("");

        check(run, "error:40004", "", false, true, "");
    }

    @Test
    @DisplayName("a consumer without the repository scope is an access error")
    void reportsAMissingScope() throws Exception {
        serve("scope-403");
        AtomResult run = repo("");

        check(run, "error:40006");
    }

    @Test
    @DisplayName("an expired token is an access error")
    void reportsAnExpiredToken() throws Exception {
        serve("expired");
        AtomResult run = repo("");

        check(run, "error:40006");
    }

    @Test
    @DisplayName("a 201 that stores another state is caught by the read-back")
    void catchesALyingState() throws Exception {
        serve("lying-state");
        AtomResult run = repo("");

        check(run, "error:40008");
    }

    @Test
    @DisplayName("a 201 that stores nothing is caught by the read-back")
    void catchesADroppedStatus() throws Exception {
        serve("lying-drop");
        AtomResult run = repo("");

        check(run, "error:40008");
    }

    @Test
    @DisplayName("a read-back that still shows the old state is caught")
    void catchesAStaleReadBack() throws Exception {
        serve("readback-stale");
        AtomResult run = repo("");

        check(run, "error:40008");
    }

    @Test
    @DisplayName("a broken read-back endpoint fails the step instead of claiming success")
    void catchesABrokenReadBack() throws Exception {
        serve("readback-500");
        AtomResult run = repo("");

        check(run, "error:40008");
    }

    @Test
    @DisplayName("verifyAfterWrite=false: one POST, no GET, verified=not-checked")
    void skipsTheReadBackOnRequest() throws Exception {
        serve("normal");
        AtomResult run = repo("\"verifyAfterWrite\": \"false\"");

        check(run, "success", "", true, false, "");
    }

    @Test
    @DisplayName("a commit Bitbucket does not know is a third-party error with the server's body")
    void reportsAnUnknownCommit() throws Exception {
        serve("normal", "0000000000000000000000000000000000000000", "");
        AtomResult run = repo("");

        check(run, "error:40007");
    }

    @Test
    @DisplayName("the consumer secret never reaches the log, the output, or a bearer header")
    void keepsTheSecretOutOfEverything() throws Exception {
        serve("normal");
        AtomResult run = repo("");

        check(run, "success");
        assertFalse(run.console().contains(CSECRET), "the consumer secret reached the build log");
        assertFalse(run.file("output.json").contains(CSECRET), "the consumer secret reached output.json");
        assertFalse(Files.readString(requests, StandardCharsets.UTF_8).contains("Bearer " + CSECRET),
                "the consumer secret was used as a bearer token");
    }
}
