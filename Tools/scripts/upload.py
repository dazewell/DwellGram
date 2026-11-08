import json
import os
import re
import contextlib
from asyncio import sleep
import html
from pathlib import Path
from sys import argv
from urllib.parse import quote as urlquote

from pyrogram import Client
from pyrogram.enums import ParseMode
from pyrogram.errors import FloodPremiumWait, FloodWait
from pyrogram.types import InputMediaDocument

api_id = os.environ.get("APP_ID")
api_hash = os.environ.get("APP_HASH")
artifacts_root = Path(os.environ.get("ARTIFACTS_PATH") or "artifacts")
build_type = argv[3] if len(argv) > 3 else None
metadata_chat_id = argv[4] if len(argv) > 4 else None
VARIANTS = (
    ("Official", "org.telegram.messenger.beta"),
    ("Unofficial", "nekox.messenger"),
)

# Self-update (#self-update): the hashtag each package's app searches the update
# channel for. Keep in step with UPDATE_TAG in TMessagesProj/build.gradle.
UPDATE_TAGS = {
    "Official": "updateOfficial",
    "Unofficial": "updateUnofficial",
}

# staging.yml resolves build_type once up front. "test" covers PR-associated
# builds, and "staging" covers true dev/release-candidate builds. Anything else
# (or a missing value, e.g. a local run) falls back to the staging rocket.
BUILD_EMOJI = {
    "staging": "🚀",
    "test": "🧪",
}

# Trailing searchable tag by build kind; appended after budget accounting so it
# always survives as the final caption text.
BUILD_HASHTAG = {
    "staging": "#rc",
    "test": "#test",
}

# A pathologically long PR title or commit subject must not eat the whole
# caption budget on its own; this is a fixed, small share of it.
HEADER_TITLE_BUDGET = 120

# Pyrogram only rides out a FloodWait shorter than the client's sleep_threshold,
# which defaults to 10 seconds (i.e. nothing, once a run of builds has the bot
# throttled). Let it absorb anything up to five minutes on its own; the retry
# wrapper below catches whatever is longer.
SLEEP_THRESHOLD = 300
MAX_FLOOD_WAIT = 900

def find_apk(artifact_path: Path, abi: str) -> Path | None:
    return next((apk for apk in sorted(artifact_path.rglob("*.apk")) if abi in apk.name), None)

def get_commit_info():
    commit_id_raw = os.environ.get("COMMIT_ID") or "unknown"
    commit_id = commit_id_raw[:7]
    # Fall back to the specific commit when we know its id, so the "commit details"
    # link opens the commit the caption names rather than the whole commits list.
    default_commit_url = (
        f"https://github.com/dazewell/DwellGram/commit/{commit_id_raw}"
        if commit_id_raw != "unknown"
        else "https://github.com/dazewell/DwellGram/commits"
    )
    commit_url = os.environ.get("COMMIT_URL") or default_commit_url
    commit_message = os.environ.get("COMMIT_MESSAGE") or "unknown"
    branch = os.environ.get("BRANCH") or "unknown"
    # Git branch names can legally contain '#', '?' and spaces, none of which
    # are safe unencoded in a URL path -- '#' in particular truncates the URL
    # at a fragment, so the link would resolve to the wrong (or no) page. '/'
    # is kept unescaped since it's a legitimate path separator (e.g.
    # "feature/x"); the workflow no longer builds this URL itself so encoding
    # always happens exactly once, here.
    default_branch_url = (
        f"https://github.com/dazewell/DwellGram/tree/{urlquote(branch, safe='/')}"
        if branch != "unknown"
        else "https://github.com/dazewell/DwellGram"
    )
    branch_url = os.environ.get("BRANCH_URL") or default_branch_url
    pr_number = (os.environ.get("PR_NUMBER") or "").strip()
    pr_title = os.environ.get("PR_TITLE") or ""
    default_pr_url = f"https://github.com/dazewell/DwellGram/pull/{pr_number}" if pr_number else ""
    pr_url = os.environ.get("PR_URL") or default_pr_url
    return commit_id, commit_url, commit_message, branch, branch_url, pr_number, pr_title, pr_url

def truncate_text(text: str, budget: int, escaped: bool = False) -> str:
    if budget <= 0:
        return ""
    if tg_len(text) <= budget:
        return text
    suffix = "…"
    text = text.rstrip()
    while text and tg_len(text + suffix) > budget:
        text = text[:-1]
    text = text.rstrip()
    if escaped:
        # A cut can land mid HTML-entity (html.escape() turns "&" into
        # "&amp;"): a truncated "&am" has no ";" and is invalid HTML, which
        # makes Telegram reject the whole caption with "can't parse entities".
        # Only safe to check on already-escaped text -- unescaped text can
        # legitimately end in a bare "&" that was never an entity to begin with.
        entity_start = text.rfind("&")
        if entity_start != -1 and ";" not in text[entity_start:]:
            text = text[:entity_start].rstrip()
    return text + suffix

def get_header(commit_id, commit_url, commit_message, pr_number, pr_title, pr_url) -> str:
    emoji = BUILD_EMOJI.get(build_type, "🚀")
    if pr_number and pr_title:
        # Escape before truncating, not after: tg_len() (which truncate_text
        # relies on) strips anything that looks like an HTML tag, so
        # truncating raw text first would under-count a title containing a
        # literal "<...>" and let it through untruncated, only for the
        # subsequent escape to make it longer than the intended budget again.
        title = truncate_text(html.escape(pr_title), HEADER_TITLE_BUDGET, escaped=True)
        headline = f'{emoji} <b><a href="{html.escape(pr_url)}">#{html.escape(pr_number)}</a> · {title}</b>'
    else:
        # No associated PR (a dispatch on a branch with no PR, or a direct
        # push) — fall back to the commit subject instead of printing a bare
        # "#" or the word "unknown".
        subject = commit_message.splitlines()[0] if commit_message and commit_message != "unknown" else ""
        subject = truncate_text(html.escape(subject), HEADER_TITLE_BUDGET, escaped=True)
        headline = f"{emoji} <b>{subject}</b>" if subject else emoji
    # The meta line links the short commit id -- the branch name used to be
    # shown alongside it, but that duplicated the PR number/title already in
    # the headline above and cluttered a tight 1024-unit caption. Removed
    # temporarily; the commit link below is unchanged, and restoring the branch
    # means threading branch/branch_url back into get_header() (get_commit_info
    # still returns both) and appending the link to this line.
    meta = f'<b>Commit:</b> <a href="{html.escape(commit_url)}"><code>{html.escape(commit_id)}</code></a>'
    return headline + "\n" + meta

def get_caption(commit_msg_budget=None) -> str:
    commit_id, commit_url, commit_message, branch, branch_url, pr_number, pr_title, pr_url = get_commit_info()
    # The header is measured against the full commit message (never the
    # commit_msg_budget-trimmed copy below) so it stays a fixed width across
    # both the overhead-measurement call and the final call — otherwise a
    # budget=0 probe would shrink the fallback subject line and understate
    # the real overhead.
    header = get_header(commit_id, commit_url, commit_message, pr_number, pr_title, pr_url)
    # Escape once, then measure/truncate the escaped text -- escaping AFTER
    # truncating (the previous order) let tg_len() undercount raw "<...>"
    # substrings as stripped tags, pass them through untruncated, and only
    # then expand them back into far more visible characters than the budget
    # allowed (a message full of literal "<tag>" text could blow the caption
    # past 1024 and get the whole post rejected by Telegram).
    escaped_commit_message = html.escape(commit_message)
    if commit_msg_budget is not None:
        escaped_commit_message = truncate_text(escaped_commit_message, commit_msg_budget, escaped=True)
    return f"{header}\n\n<b>Commit Message:</b>\n<blockquote expandable>{escaped_commit_message}</blockquote>"

def get_hashtag() -> str:
    tag = BUILD_HASHTAG.get(build_type, "#rc")
    return f"\n\n{tag}"

def get_document() -> list["InputMediaDocument"]:
    documents = []
    for build_label, _ in VARIANTS:
        apk = find_apk(artifacts_root / build_label, "arm64-v8a")
        if not apk:
            raise FileNotFoundError(
                f"no arm64-v8a APK found for {build_label} under {artifacts_root / build_label}"
            )
        documents.append(InputMediaDocument(media = str(apk)))
    # Telegram caps captions at 1024 chars, measured as visible text in UTF-16
    # units (tg_len) — not Python's len(). Split the budget so the commit
    # message always keeps a share (it used to be starved to "…" by a long
    # generated summary); the summary then takes whatever the message doesn't
    # need. Reserve the trailing hashtag in the same budget so it is always the
    # final visible content.
    limit = 1024
    hashtag = get_hashtag()
    overhead = tg_len(get_caption(commit_msg_budget=0)) + tg_len(hashtag)
    content_budget = max(0, limit - overhead)
    # Measure the same escaped string the caption will actually carry, so the
    # reserve and the final trim (both in get_caption, both escaped-then-cut)
    # agree with each other instead of one working off unescaped text.
    escaped_commit_message = html.escape(get_commit_info()[2])
    msg_reserve = min(tg_len(escaped_commit_message), content_budget // 2)
    ai_summary = get_ai_summary(max_inner=max(0, content_budget - msg_reserve))
    room = limit - overhead - tg_len(ai_summary)
    base_caption = get_caption(commit_msg_budget=max(0, room))
    documents[-1].caption = base_caption + ai_summary + hashtag
    return documents

def get_metadata():
    commit_id = "<code>" + (os.environ.get("COMMIT_ID") or "unknown")[:7] + "</code>"
    commit_message = "<code>" + html.escape(os.environ.get("COMMIT_MESSAGE") or "unknown") + "</code>"
    build_timestamp = "<code>" + (os.environ.get("BUILD_TIMESTAMP") or "-1") + "</code>"
    branch = "<code>" + (os.environ.get("BRANCH") or "unknown") + "</code>"
    return build_timestamp + " " + commit_id + " " + branch + "\n" + commit_message

def get_ai_summary(max_inner=None):
    ai_summary = os.environ.get("AI_SUMMARY", "")
    if not ai_summary:
        return ""
    inner = normalize_message(ai_summary)
    if max_inner is not None and tg_len(inner) > max_inner:
        inner = truncate_to_lines(inner, max_inner)
    return "\n\n<blockquote expandable>" + inner + "</blockquote>"

def tg_len(text: str) -> int:
    # Telegram measures a caption by its visible text in UTF-16 code units:
    # HTML tags are stripped and don't count, and characters above the BMP
    # (emoji like 🐛/🧹) count as two. Python's len() matches neither, so a
    # raw-len budget let the longer-header package overflow 1024 and Telegram
    # dropped its <blockquote> while the shorter one kept it.
    visible = re.sub(r"<[^>]+>", "", text)
    return sum(2 if ord(ch) > 0xFFFF else 1 for ch in visible)

def truncate_to_lines(text: str, budget: int) -> str:
    # Drop whole trailing lines to fit the budget so a cut never lands inside a
    # tag or entity: each summary line carries balanced markup, and a mid-tag
    # slice would make the wrapping <blockquote> unparseable, which is what made
    # one build render the summary as plain text.
    lines = text.split("\n")
    while lines and tg_len("\n".join(lines) + "\n…") > budget:
        lines.pop()
    return ("\n".join(lines).rstrip() + "\n…") if lines else "…"

def normalize_message(text: str) -> str:
    return (text or "").replace("\\n", "\n")

def retry(func):
    async def wrapper(*args, **kwargs):
        delay = 15
        for attempt in range(4):
            last = attempt == 3
            try:
                return await func(*args, **kwargs)
            except (FloodWait, FloodPremiumWait) as e:
                # Retrying a flood wait immediately (what this used to do) only
                # digs the hole deeper, so wait out what Telegram asked for.
                if last or e.value > MAX_FLOOD_WAIT:
                    raise
                print(f"flood wait {e.value}s, sleeping", flush=True)
                await sleep(e.value + 5)
            except Exception as e:
                if last:
                    raise
                print(e, flush=True)
                await sleep(delay)
                delay *= 3
    return wrapper

@retry
async def send_to_channel(client: "Client", cid: str):
    with contextlib.suppress(ValueError):
        cid = int(cid)
    documents = get_document()
    print("Uploading to Telegram:", flush=True)
    for document in documents:
        print(f"- {document.media}", flush=True)
    sent = await client.send_media_group(
        cid,
        media = documents,
    )
    return sent, documents[-1].caption or ""

@retry
async def send_metadata(client: "Client", cid: str):
    with contextlib.suppress(ValueError):
        cid = int(cid)
    await client.send_message(
        chat_id = cid,
        text = get_metadata(),
    )

def get_update_chat_id() -> int | None:
    # The repo variable holds the bare channel id the app is built with; -100 makes
    # it a Bot API chat id. Unset, 0 or malformed means self-update isn't set up.
    raw = (os.environ.get("UPDATE_CHANNEL_ID") or "").strip()
    if raw.startswith("-100"):
        raw = raw[4:]
    if not re.fullmatch(r"[1-9]\d{0,15}", raw):
        return None
    return int("-100" + raw)

def get_app_version(root: Path = Path(".")) -> tuple[str, int] | None:
    # Mirrors verName/verCode in TMessagesProj/build.gradle, which is what the app
    # compares against: the same code, then the build timestamp to break the tie.
    try:
        gradle = (root / "TMessagesProj" / "build.gradle").read_text(encoding="utf-8")
        props = (root / "gradle.properties").read_text(encoding="utf-8")
    except OSError:
        return None
    code = re.search(r"^def verCode = (\d+)\s*$", gradle, re.M)
    name = re.search(r"^APP_VERSION_NAME=(\S+)\s*$", props, re.M)
    if not code or not name:
        return None
    commit = (os.environ.get("COMMIT_ID") or "")[:7]
    return name.group(1) + (f"-{commit}" if commit else ""), int(code.group(1))

def get_update_metadata(tag: str, version: str, version_code: int, build_timestamp: int,
                        document_id: int, message_id: int) -> str:
    # BaseRemoteHelper reads the text after the leading hashtag as JSON.
    payload = {
        "version": version,
        "version_code": version_code,
        "build_timestamp": build_timestamp,
        "can_not_skip": False,
        "message": message_id,
        "document": {"arm64-v8a": document_id},
    }
    return f"#{tag} " + json.dumps(payload, separators=(",", ":"))

def match_variants(messages) -> dict:
    # The album keeps the VARIANTS order; check it by file name anyway, since
    # handing a package the other package's APK is the one thing that must not happen.
    if len(messages) != len(VARIANTS):
        raise ValueError(f"expected {len(VARIANTS)} album messages, got {len(messages)}")
    matched = {}
    for (build_label, _), message in zip(VARIANTS, messages):
        name = getattr(getattr(message, "document", None), "file_name", None) or ""
        if f"-{build_label}-" not in name and not name.endswith(f"-{build_label}.apk"):
            raise ValueError(f"album message {message.id} ({name!r}) is not the {build_label} APK")
        matched[build_label] = message
    return matched

async def publish_update(client: "Client", sent, caption: str = "") -> None:
    # Only staging builds of dev become updates. A PR test build is never offered, and
    # neither is a dispatch on a feature branch, which resolve_build also calls staging.
    if build_type != "staging" or os.environ.get("BRANCH") != "dev":
        return
    chat_id = get_update_chat_id()
    if chat_id is None:
        print("UPDATE_CHANNEL_ID not set; skipping self-update metadata.", flush=True)
        return
    try:
        version = get_app_version()
        build_timestamp = int(os.environ.get("BUILD_TIMESTAMP") or "0")
        if version is None or build_timestamp <= 0:
            raise ValueError(f"missing version ({version}) or build timestamp ({build_timestamp})")
        if sent[0].chat.id != chat_id:
            # The fork re-parses copied captions from plain text, which would drop the
            # changelog's formatting; hand it the original HTML caption instead.
            captions = [""] * (len(sent) - 1) + [caption]
            sent = await client.copy_media_group(chat_id, sent[0].chat.id, sent[0].id, captions=captions)
        matched = match_variants(sent)
        # The last album message carries the caption, which the app shows as the changelog.
        changelog_id = sent[-1].id
        for build_label, _ in VARIANTS:
            await client.send_message(
                chat_id,
                get_update_metadata(UPDATE_TAGS[build_label], version[0], version[1], build_timestamp,
                                    matched[build_label].id, changelog_id),
                parse_mode=ParseMode.DISABLED,
            )
        print(f"Published self-update metadata for {version[0]} to {chat_id}.", flush=True)
    except Exception as e:
        # The APKs are already delivered; a broken update post must not fail that.
        print(f"::warning::self-update metadata not published: {e}", flush=True)

def get_client(bot_token: str):
    return Client(
        "helper_bot",
        api_id=api_id,
        api_hash=api_hash,
        bot_token=bot_token,
        sleep_threshold=SLEEP_THRESHOLD,
    )

async def main():
    bot_token = argv[1]
    chat_id = argv[2]
    client = get_client(bot_token)
    await client.start()
    sent, caption = await send_to_channel(client, chat_id)
    await publish_update(client, sent, caption)
    if metadata_chat_id:
        await send_metadata(client, metadata_chat_id)
    await client.stop()

if __name__ == "__main__":
    from asyncio import run
    run(main())
