package com.jenkinschina.atom.bitbucketcloudstatus;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomService;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Report a build state to a Bitbucket <em>Cloud</em> commit — port of {@code bitbucket-build-status-notifier} 1.4.2.
 *
 * <p>What the plugin does (read out of {@code BitbucketBuildStatusHelper}, {@code BitbucketBuildStatusResource},
 * {@code BitbucketBuildStatusSerializer}, {@code BitbucketApiService} and the step): it turns the job's SCM URL into
 * {@code owner} + {@code repo}, posts to
 * {@code https://api.bitbucket.org/2.0/repositories/{owner}/{repo}/commit/{sha}/statuses/build} with exactly
 * {@code state}, {@code key}, {@code url} and — only when non-empty — {@code name} and {@code description}; the
 * credential is a Bitbucket OAuth consumer, exchanged at {@code https://bitbucket.org/site/oauth2/access_token} with
 * HTTP Basic + {@code grant_type=client_credentials}, and the status call carries {@code Authorization: Bearer <token>}.
 * The default {@code key} is {@code md5Hex("<job full name>#<build number>")} and the default {@code name} is
 * {@code "<job full name> #<build number>"}; {@code overrideLatestBuild} switches both to {@code md5Hex(job)} and
 * {@code job} so Bitbucket keeps one status per job instead of one per build.
 *
 * <p>Two deliberate differences. First, the plugin never looks at the response — it prints the body and returns — so a
 * 201 from a proxy that dropped the request looks like success. This atom reads the status back with
 * {@code GET .../statuses/build/{key}} and requires the state and url it posted. Second, the plugin takes owner and
 * repo from Jenkins' SCM object (job model, not available to an atom); here they are inputs, inferred from
 * {@code GIT_URL} only when absent.
 */
@AtomService("bitbucket-cloud-status")
public class BitbucketCloudStatusAtom implements AtomTask {

    private static final String DEFAULT_API = "https://api.bitbucket.org";
    private static final String DEFAULT_TOKEN_URL = "https://bitbucket.org/site/oauth2/access_token";
    private static final Pattern SCP_LIKE = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://(?:[^@/]+@)?([^/:]+)(?::\\d+)?/(.+)$");
    private static final Pattern SCP = Pattern.compile("^(?:[^@/]+@)?([^/:]+):(?!//)(.+)$");
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)}");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String api = context.getParam("apiUrl", DEFAULT_API).trim();
        if (api.isEmpty()) {
            api = DEFAULT_API;
        }
        api = api.replaceAll("/+$", "");
        if (!api.startsWith("http://") && !api.startsWith("https://")) {
            return AtomResult.error(ErrorType.USER, 40001, "apiUrl must be an absolute http(s) URL: " + api);
        }
        String tokenUrl = context.getParam("tokenUrl", DEFAULT_TOKEN_URL).trim();
        if (tokenUrl.isEmpty()) {
            tokenUrl = DEFAULT_TOKEN_URL;
        }

        String gitUrl = first(context, "GIT_URL");
        String owner = first(context, "workspace", "owner", "account");
        String repo = first(context, "repoSlug", "repo");
        if (owner.isEmpty() || repo.isEmpty()) {
            String[] parsed = splitRepository(gitUrl);
            if (owner.isEmpty()) {
                owner = parsed[0];
            }
            if (repo.isEmpty()) {
                repo = parsed[1];
            }
        }
        if (owner.isEmpty() || repo.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "workspace and repoSlug are required (or run after a checkout so GIT_URL names the repository),"
                            + " got GIT_URL=" + (gitUrl.isEmpty() ? "<unset>" : gitUrl));
        }

        String sha = first(context, "sha", "commitId", "GIT_COMMIT").trim();
        if (sha.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "sha is required (or run after a checkout so GIT_COMMIT is set)");
        }

        boolean overrideLatest = context.getBooleanParam("overrideLatestBuild", false);
        String rawState = context.getParam("state", "").trim();
        if (rawState.isEmpty()) {
            rawState = context.getParam("BUILD_STATUS", "").trim();
        }
        String state = mapState(rawState);
        if (state == null) {
            return AtomResult.error(ErrorType.USER, 40003,
                    "state '" + rawState + "' is not one of SUCCESSFUL / FAILED / INPROGRESS"
                            + " (the plugin forwards unknown states and lets Bitbucket answer 400; failing here is clearer)");
        }

        String job = first(context, "JOB_NAME");
        String number = first(context, "BUILD_NUMBER");
        String key = expand(context, context.getParam("key", "").trim());
        String name = expand(context, context.getParam("name", "").trim());
        String description = expand(context, context.getParam("description", "").trim());
        String url = expand(context, first(context, "buildUrl", "BUILD_URL")).trim();
        if (key.isEmpty()) {
            key = overrideLatest ? md5(job) : md5(job + "#" + number);
        }
        if (name.isEmpty()) {
            name = overrideLatest ? job : (number.isEmpty() ? job : job + " #" + number);
        }
        if (url.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "url/buildUrl is required: Bitbucket needs a link for the status (BUILD_URL is set inside a build)");
        }

        String accessToken = orEmpty(context.getSecret("accessToken")).trim();
        String consumerKey = context.getParam("consumerKey", "").trim();
        String consumerSecret = orEmpty(context.getSecret("consumerSecret")).trim();
        String tokenSource;
        if (!accessToken.isEmpty()) {
            tokenSource = "access-token";
        } else if (!consumerKey.isEmpty() && !consumerSecret.isEmpty()) {
            tokenSource = "client-credentials";
        } else {
            return AtomResult.error(ErrorType.USER, 40002,
                    "bind a Bitbucket OAuth consumer (consumerKey + consumerSecret) or an accessToken:"
                            + " Bitbucket Cloud rejects anonymous build status");
        }

        String target = api + "/2.0/repositories/" + encodePath(owner) + "/" + encodePath(repo)
                + "/commit/" + encodePath(sha) + "/statuses/build";
        String readBackUrl = target + "/" + encodePath(key);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        if (accessToken.isEmpty()) {
            try {
                accessToken = exchangeToken(client, tokenUrl, consumerKey, consumerSecret);
            } catch (TokenException e) {
                return AtomResult.error(e.type, e.code, e.getMessage());
            }
            if (accessToken.isEmpty()) {
                return AtomResult.error(ErrorType.THIRD_PART, 40004,
                        "the token endpoint answered without an access_token — a Bitbucket OAuth consumer must be"
                                + " allowed the repository scope");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", state);
        payload.put("key", key);
        payload.put("url", url);
        if (!name.isEmpty()) {
            payload.put("name", name);
        }
        if (!description.isEmpty()) {
            payload.put("description", description);
        }
        String body = Json.write(payload);

        context.log("[bitbucket-cloud-status] " + owner + "/" + repo + "@" + sha + " -> " + state
                + " (key=" + key + ") at " + target);
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(URI.create(target))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AtomResult.error(ErrorType.THIRD_PART, 40005, "posting the build status was interrupted");
        } catch (Exception e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40005,
                    "cannot reach " + target + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        int code = response.statusCode();
        if (code == 401 || code == 403) {
            return AtomResult.error(ErrorType.THIRD_PART, 40006, "Bitbucket answered " + code
                    + " — the consumer needs the repository scope and a token that has not expired: "
                    + excerpt(response.body()));
        }
        if (code / 100 != 2) {
            return AtomResult.error(ErrorType.THIRD_PART, 40007,
                    "Bitbucket answered " + code + " for " + target + ": " + excerpt(response.body()));
        }

        String verified = "not-checked";
        if (context.getBooleanParam("verifyAfterWrite", true)) {
            try {
                Map<String, Object> seen = readBack(client, readBackUrl, accessToken);
                String seenState = String.valueOf(seen.get("state"));
                String seenUrl = String.valueOf(seen.get("url"));
                String seenKey = String.valueOf(seen.get("key"));
                if (!state.equals(seenState)) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40008,
                            "Bitbucket answered " + code + " but reports state " + seenState + " for key " + seenKey
                                    + " (sent " + state + ")");
                }
                if (!url.equals(seenUrl)) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40008,
                            "Bitbucket reports url " + seenUrl + " for key " + seenKey + ", sent " + url);
                }
                verified = seenState;
            } catch (ReadBackException e) {
                return AtomResult.error(ErrorType.THIRD_PART, 40008, e.getMessage());
            }
        }

        return AtomResult.success()
                .data("status", String.valueOf(code))
                .data("state", state)
                .data("key", key)
                .data("apiUrl", target)
                .data("verified", verified)
                .data("repo", owner + "/" + repo)
                .data("tokenSource", tokenSource);
    }

    /** OAuth2 client_credentials, exactly as {@code BitbucketApiService.getAccessToken} sends it. */
    private static String exchangeToken(HttpClient client, String tokenUrl, String key, String secret)
            throws TokenException {
        String basic = Base64.getEncoder()
                .encodeToString((key + ":" + secret).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(URI.create(tokenUrl))
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials",
                            StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenException(ErrorType.THIRD_PART, 40004, "the token exchange was interrupted");
        } catch (Exception e) {
            throw new TokenException(ErrorType.THIRD_PART, 40004,
                    "cannot reach the token endpoint " + tokenUrl + ": " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
        }
        int code = response.statusCode();
        if (code == 401 || code == 403) {
            throw new TokenException(ErrorType.USER, 40002,
                    "the token endpoint rejected the consumer key/secret (" + code + "): " + excerpt(response.body()));
        }
        if (code / 100 != 2) {
            throw new TokenException(ErrorType.THIRD_PART, 40004,
                    "the token endpoint answered " + code + ": " + excerpt(response.body()));
        }
        try {
            Object token = Json.parseObject(response.body()).get("access_token");
            return token == null ? "" : String.valueOf(token).trim();
        } catch (RuntimeException e) {
            throw new TokenException(ErrorType.THIRD_PART, 40004,
                    "the token response is not the JSON Bitbucket documents: " + excerpt(response.body()));
        }
    }

    private static Map<String, Object> readBack(HttpClient client, String url, String token)
            throws ReadBackException {
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReadBackException("reading the status back was interrupted");
        } catch (Exception e) {
            throw new ReadBackException("cannot read the status back from " + url + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (response.statusCode() == 404) {
            throw new ReadBackException("Bitbucket accepted the write but has no status for this key: "
                    + excerpt(response.body()));
        }
        if (response.statusCode() / 100 != 2) {
            throw new ReadBackException("reading the status back answered " + response.statusCode() + ": "
                    + excerpt(response.body()));
        }
        try {
            return Json.parseObject(response.body());
        } catch (RuntimeException e) {
            throw new ReadBackException("the status is not the JSON Bitbucket documents: " + excerpt(response.body()));
        }
    }

    /** Jenkins result / step-state names in, the three Bitbucket Cloud states out; {@code null} = reject. */
    static String mapState(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "", "INPROGRESS", "IN_PROGRESS", "BUILDING", "RUNNING", "PENDING" -> "INPROGRESS";
            case "SUCCESS", "SUCCESSFUL", "OK", "PASSED" -> "SUCCESSFUL";
            case "FAILED", "FAILURE", "ERROR", "UNSTABLE", "WARNING", "ABORTED", "CANCELED", "CANCELLED",
                    "NOT_BUILT", "NOTBUILT" -> "FAILED";
            default -> null;
        };
    }

    /**
     * {@code git@host:owner/repo.git}, {@code ssh://git@host/owner/repo.git}, {@code https://host/owner/repo.git},
     * {@code https://user@host/scm/owner/repo.git} (Bitbucket Server / Data Center clone URL) → {owner, repo}.
     * Empty strings when the URL does not name a repository; the caller reports that.
     */
    static String[] splitRepository(String url) {
        String path = "";
        if (url != null && !url.isBlank()) {
            String candidate = url.trim();
            Matcher scmLike = SCP_LIKE.matcher(candidate);
            Matcher scp = SCP.matcher(candidate);
            if (candidate.contains("://") && scmLike.matches()) {
                path = scmLike.group(2);
            } else if (!candidate.contains("://") && scp.matches()) {
                path = scp.group(2);
            } else if (candidate.startsWith("/")) {
                path = candidate;
            }
        }
        path = path.replaceAll("^/+", "").replaceAll("/+$", "");
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        String[] parts = path.split("/");
        if (parts.length < 2) {
            return new String[] {"", ""};
        }
        return new String[] {parts[parts.length - 2], parts[parts.length - 1]};
    }

    /** {@code ${NAME}} is replaced by the build parameter / environment variable of that name, as Jenkins does. */
    static String expand(AtomContext context, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(context.getParam(matcher.group(1), "")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** {@code DigestUtils.md5Hex}, which is what the plugin uses to build its keys. */
    static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 is required by the plugin's key format", e);
        }
    }

    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String first(AtomContext context, String... names) {
        for (String name : names) {
            String value = context.getParam(name, "");
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String excerpt(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class TokenException extends Exception {
        private final ErrorType type;
        private final int code;

        TokenException(ErrorType type, int code, String message) {
            super(message);
            this.type = type;
            this.code = code;
        }
    }

    private static final class ReadBackException extends Exception {
        ReadBackException(String message) {
            super(message);
        }
    }
}
