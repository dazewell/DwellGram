// Exposes .github/scripts/request-copilot-review.ps1 as a tool. The script is
// the single implementation; Claude Code and a plain shell call it directly.
import { joinSession } from "@github/copilot-sdk/extension";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const script = fileURLToPath(new URL("../../scripts/request-copilot-review.ps1", import.meta.url));
const repoRoot = path.resolve(path.dirname(script), "..", "..");

function run(opts, timeoutMs) {
    // -Command rather than -File so the same invocation can switch off colour
    // and set the error view before the script runs. Every value is
    // single-quoted, flags are literals.
    const quote = (s) => `'${String(s).replace(/'/g, "''")}'`;
    const parts = [
        `-PullRequest ${quote(opts.pullRequest)}`,
        `-TimeoutMinutes ${quote(opts.timeout)}`,
        opts.repository ? `-Repository ${quote(opts.repository)}` : "",
        opts.wait ? "-Wait" : "",
        opts.force ? "-Force" : "",
    ].filter(Boolean);
    const command =
        "$PSStyle.OutputRendering = 'PlainText'; $ErrorView = 'ConciseView'; " +
        `& ${quote(script)} ${parts.join(" ")}`;
    return new Promise((resolve) => {
        execFile(
            "pwsh",
            ["-NoProfile", "-NonInteractive", "-Command", command],
            { cwd: repoRoot, timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024, windowsHide: true,
              env: { ...process.env, NO_COLOR: "1" } },
            (error, stdout, stderr) => resolve({ error, stdout, stderr }),
        );
    });
}

await joinSession({
    tools: [
        {
            name: "request_copilot_review",
            description:
                "Request a billed Copilot code review on a pull request in this repo. Call it only when the " +
                "current head is good enough for an external review: architect round 2 has cleared and " +
                "ci.yml is green on the head. At most two per PR; the second only after pushing fixes " +
                "for Important-or-above findings. Skip it on doc- or process-only PRs unless dazewell " +
                "asked. Refuses drafts, a head Copilot already reviewed, a request still in flight, and " +
                "a spent budget. Returns JSON: status, head_sha, reviews_used, and when waited for, " +
                "the review and its inline comments (comment ids are what in-thread replies take). " +
                "Status requested-unconfirmed means the request most likely landed: do not retry.",
            parameters: {
                type: "object",
                properties: {
                    pull_request: { type: "integer", description: "Pull request number." },
                    repository: {
                        type: "string",
                        description: "owner/repo. Defaults to the repository of this checkout.",
                    },
                    wait: {
                        type: "boolean",
                        description: "Block until the review lands and return it. Default true.",
                    },
                    timeout_minutes: {
                        type: "integer",
                        description: "How long to wait for the review. Default 15.",
                    },
                    force: {
                        type: "boolean",
                        description: "Exceed the two-review budget. Only when dazewell explicitly asked for another review.",
                    },
                },
                required: ["pull_request"],
            },
            handler: async (args) => {
                const wait = args.wait !== false;
                const timeout = Number.isInteger(args.timeout_minutes) ? args.timeout_minutes : 15;
                const { error, stdout, stderr } = await run(
                    { pullRequest: args.pull_request, timeout, repository: args.repository, wait, force: args.force === true },
                    (timeout + 5) * 60 * 1000,
                );
                if (error) {
                    return {
                        textResultForLlm: [stderr, stdout].filter((s) => s && s.trim()).join("\n") || String(error),
                        resultType: "failure",
                    };
                }
                return { textResultForLlm: stdout, resultType: "success" };
            },
        },
    ],
});
